/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.system_tests;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionAcquiredInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.*;

import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.*;
import static uk.co.real_logic.artio.Reply.State.COMPLETED;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.Timing.DEFAULT_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.Timing.withTimeout;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.SOLE_LIBRARY;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class ExternallyControlledSystemTest extends AbstractGatewayToGatewaySystemTest
{
    private final FakeSessionProxy fakeSessionProxy = new FakeSessionProxy();
    private SessionWriter acceptingSessionWriter = null;
    private final FakeHandler acceptingHandler = new FakeHandler(acceptingOtfAcceptor)
    {
        public SessionHandler onSessionAcquired(final Session session, final SessionAcquiredInfo acquiredInfo)
        {
            acceptingSessionWriter = acceptingLibrary.sessionWriter(
                session.id(),
                session.connectionId(),
                session.sequenceIndex());

            return super.onSessionAcquired(session, acquiredInfo);
        }
    };

    private int sessionProxyRequests = 0;

    @Before
    public void launch()
    {
        mediaDriver = launchMediaDriver();

        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID);
        acceptingConfig.deleteLogFileDirOnStart(true);
        acceptingConfig.initialAcceptedSessionOwner(SOLE_LIBRARY);

        acceptingEngine = FixEngine.launch(acceptingConfig);
        initiatingEngine = launchInitiatingEngine(libraryAeronPort);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler)
            .sessionProxyFactory(this::sessionProxyFactory);

        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);
    }

    @Test(timeout = 10_000L)
    public void shouldRoundTripMessagesViaExternalSystem()
    {
        connectSessions();

        awaitForwardingOfAcceptingSession();

        assertNotNull(acceptingSessionWriter);

        messagesCanBeExchanged();

        assertEquals(1, sessionProxyRequests);
        assertEquals(1, fakeSessionProxy.sentHeartbeats);
        assertEquals(1, fakeSessionProxy.sentLogons);
        assertEquals(0, fakeSessionProxy.sentResendRequests);
    }

    @Test(timeout = 10_000L)
    public void shouldReconnectConnections()
    {
        shouldRoundTripMessagesViaExternalSystem();

        disconnectSessions();
        acceptingSession = null;
        initiatingSession = null;

        connectSessions();

        awaitForwardingOfAcceptingSession();
        messagesCanBeExchanged();

        assertEquals(1, sessionProxyRequests);
        assertEquals(2, fakeSessionProxy.sentHeartbeats);
        assertEquals(2, fakeSessionProxy.sentLogons);
        assertEquals(0, fakeSessionProxy.sentResendRequests);
    }

    @Test(timeout = 10_000L)
    public void shouldBeAbleToContinueProcessingAFollowersSession()
    {
        writeMessageWithSessionWriter();

        fakeSessionProxy.sequenceNumberAdjustment = 1;

        shouldRoundTripMessagesViaExternalSystem();

        final FixMessage resentNewOrderSingle = awaitMessageFromSessionWriter(3, 1);
        assertEquals("Y", resentNewOrderSingle.possDup());
    }

    @Test(timeout = 10_000L)
    public void shouldBeAbleToAdjustSequenceNumbersFromTheControlSystem()
    {
        connectSessions();
        awaitForwardingOfAcceptingSession();

        // Write a message from the control system mid-stream
        writeMessageWith(acceptingSessionWriter, 2);
        fakeSessionProxy.sequenceNumberAdjustment = 1;

        awaitMessageFromSessionWriter(2, 2);

        messagesCanBeExchanged();

        assertEquals(1, sessionProxyRequests);
        assertEquals(1, fakeSessionProxy.sentHeartbeats);
        assertEquals(1, fakeSessionProxy.sentLogons);
        assertEquals(0, fakeSessionProxy.sentResendRequests);
    }

    private FixMessage awaitMessageFromSessionWriter(final int lastReceivedMsgSeqNum, final int newOrderSingleSeqNum)
    {
        final FixMessage receivedNewOrderSingle = withTimeout("Unable to find NOS", () ->
        {
            testSystem.poll();
            return initiatingOtfAcceptor.receivedMessage("D").findFirst();
        }, DEFAULT_TIMEOUT_IN_MS);
        assertEquals(newOrderSingleSeqNum, receivedNewOrderSingle.messageSequenceNumber());
        assertEquals(lastReceivedMsgSeqNum, initiatingSession.lastReceivedMsgSeqNum());
        return receivedNewOrderSingle;
    }

    private void writeMessageWithSessionWriter()
    {
        final HeaderEncoder headerEncoder = new HeaderEncoder()
            .senderCompID(INITIATOR_ID)
            .targetCompID(ACCEPTOR_ID);

        final Reply<SessionWriter> reply = acceptingLibrary.followerSession(headerEncoder, DEFAULT_TIMEOUT_IN_MS);
        testSystem.awaitCompletedReplies(reply);
        assertEquals(COMPLETED, reply.state());

        final SessionWriter sessionWriter = reply.resultIfPresent();

        writeMessageWith(sessionWriter, 1);
    }

    private void writeMessageWith(final SessionWriter sessionWriter, final int msgSeqNum)
    {
        final NewOrderSingleEncoder newOrderSingle = new NewOrderSingleEncoder();
        final DecimalFloat price = new DecimalFloat(100);
        final DecimalFloat orderQty = new DecimalFloat(2);
        final UtcTimestampEncoder time = new UtcTimestampEncoder();

        final int timeLength = time.encode(System.currentTimeMillis());

        newOrderSingle
            .clOrdID("A")
            .side(Side.BUY)
            .transactTime(time.buffer(), timeLength)
            .ordType(OrdType.MARKET)
            .price(price);

        newOrderSingle.instrument().symbol("MSFT");
        newOrderSingle.orderQtyData().orderQty(orderQty);

        newOrderSingle
            .header()
            .senderCompID(ACCEPTOR_ID)
            .targetCompID(INITIATOR_ID)
            .sendingTime(time.buffer(), timeLength)
            .msgSeqNum(msgSeqNum);

        assertTrue(acceptingLibrary.isConnected());
        assertThat(sessionWriter.send(newOrderSingle, msgSeqNum), greaterThan(0L));
    }

    private void awaitForwardingOfAcceptingSession()
    {
        Timing.assertEventuallyTrue(
            "Couldn't acquire session",
            () ->
            {
                testSystem.poll();
                acceptingSession = acceptingHandler.lastSession();

                return acceptingSession != null;
            });

        acceptingHandler.resetSession();
        final CompositeKey compositeKey = acceptingSession.compositeKey();
        assertEquals(INITIATOR_ID, compositeKey.remoteCompId());
        assertEquals(ACCEPTOR_ID, compositeKey.localCompId());
        assertNotNull("unable to acquire accepting session", acceptingSession);
    }

    private SessionProxy sessionProxyFactory(
        final int sessionBufferSize,
        final GatewayPublication gatewayPublication,
        final SessionIdStrategy sessionIdStrategy,
        final SessionCustomisationStrategy customisationStrategy,
        final EpochClock clock,
        final long connectionId,
        final int libraryId,
        final ErrorHandler errorHandler,
        final EpochFractionFormat epochFractionPrecision)
    {
        sessionProxyRequests++;
        return fakeSessionProxy;
    }

    class FakeSessionProxy implements SessionProxy
    {
        private final SessionIdStrategy sessionIdStrategy = SessionIdStrategy.senderAndTarget();
        private final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
        private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
        private final LogonEncoder logon = new LogonEncoder();
        private final LogoutEncoder logout = new LogoutEncoder();
        private final List<HeaderEncoder> headers = asList(logon.header(), heartbeat.header(), logout.header());

        private int sentLogons = 0;
        private int sentHeartbeats = 0;
        private int sentResendRequests = 0;

        private int sequenceNumberAdjustment = 0;

        private boolean seqNumResetRequested = false;

        public void fixDictionary(final FixDictionary dictionary)
        {
        }

        public void setupSession(final long sessionId, final CompositeKey sessionKey)
        {
            requireNonNull(sessionKey, "sessionKey");

            for (final HeaderEncoder header : headers)
            {
                sessionIdStrategy.setupSession(sessionKey, header);
            }
        }

        public void connectionId(final long connectionId)
        {
        }

        public long sendResendRequest(
            final int msgSeqNo,
            final int beginSeqNo,
            final int endSeqNo,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            sentResendRequests++;
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendResendRequest");
            return 0;
        }

        public long sendRequestDisconnect(final long connectionId, final DisconnectReason reason)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendRequestDisconnect");
            return 0;
        }

        public long sendLogon(
            final int msgSeqNo,
            final int heartbeatIntervalInS,
            final String username,
            final String password,
            final boolean resetSeqNumFlag,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            final int adjustedMsgSeqNo = msgSeqNo + sequenceNumberAdjustment;
            sentLogons++;

            final HeaderEncoder header = logon.header();
            setupHeader(header, adjustedMsgSeqNo);

            logon
                .heartBtInt(heartbeatIntervalInS)
                .resetSeqNumFlag(resetSeqNumFlag)
                .encryptMethod(0);

            if (notNullOrEmpty(username))
            {
                logon.username(username);
            }
            if (notNullOrEmpty(password))
            {
                logon.password(password);
            }
            seqNumResetRequested = logon.resetSeqNumFlag();

            return acceptingSessionWriter.send(logon, adjustedMsgSeqNo);
        }

        public long sendLogout(final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            final int adjustedMsgSeqNo = msgSeqNo + sequenceNumberAdjustment;
            final HeaderEncoder header = logout.header();
            setupHeader(header, adjustedMsgSeqNo);

            return acceptingSessionWriter.send(logout, adjustedMsgSeqNo);
        }

        public long sendLogout(
            final int msgSeqNo,
            final int sequenceIndex,
            final int rejectReason,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.logout");
            return 0;
        }

        public long sendLowSequenceNumberLogout(
            final int msgSeqNo,
            final int expectedSeqNo,
            final int receivedSeqNo,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendLowSequenceNumberLogout");
            return 0;
        }

        public long sendIncorrectBeginStringLogout(
            final int msgSeqNo,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendIncorrectBeginStringLogout");
            return 0;
        }

        public long sendNegativeHeartbeatLogout(
            final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendNegativeHeartbeatLogout");
            return 0;
        }

        public long sendReceivedMessageWithoutSequenceNumber(
            final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendReceivedMessageWithoutSequenceNumber");
            return 0;
        }

        public long sendRejectWhilstNotLoggedOn(
            final int msgSeqNo, final RejectReason reason, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendRejectWhilstNotLoggedOn");
            return 0;
        }

        public long sendHeartbeat(final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            return sendHeartbeat(msgSeqNo, null, 0, sequenceIndex, lastMsgSeqNumProcessed);
        }

        public long sendHeartbeat(
            final int msgSeqNo,
            final char[] testReqId,
            final int testReqIdLength,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            final int adjustedMsgSeqNo = msgSeqNo + sequenceNumberAdjustment;
            sentHeartbeats++;

            final HeaderEncoder header = heartbeat.header();
            setupHeader(header, adjustedMsgSeqNo);

            if (testReqId != null)
            {
                heartbeat.testReqID(testReqId, testReqIdLength);
            }
            else
            {
                heartbeat.resetTestReqID();
            }

            return acceptingSessionWriter.send(heartbeat, adjustedMsgSeqNo);
        }

        public long sendReject(
            final int msgSeqNo,
            final int refSeqNum,
            final int refTagId,
            final char[] refMsgType,
            final int refMsgTypeLength,
            final int rejectReason,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendReject");
            return 0;
        }

        public long sendTestRequest(
            final int msgSeqNo, final CharSequence testReqID, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendTestRequest");
            return 0;
        }

        public long sendSequenceReset(
            final int msgSeqNo, final int newSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendSequenceReset");
            return 0;
        }

        public void libraryConnected(final boolean libraryConnected)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.libraryConnected");
        }

        public boolean seqNumResetRequested()
        {
            return seqNumResetRequested;
        }

        private void setupHeader(final HeaderEncoder header, final int msgSeqNo)
        {
            final UtcTimestampEncoder timestampEncoder = this.timestampEncoder;
            header.sendingTime(timestampEncoder.buffer(), timestampEncoder.update(System.currentTimeMillis()));
            header.msgSeqNum(msgSeqNo);
        }

        private boolean notNullOrEmpty(final String string)
        {
            return string != null && string.length() > 0;
        }
    }
}
