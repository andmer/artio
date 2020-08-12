/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.messages.ReplayCompleteEncoder;

import static uk.co.real_logic.artio.LogTag.REPLAY;

abstract class ReplayerSession implements ControlledFragmentHandler
{
    private final int maxClaimAttempts;
    private final IdleStrategy idleStrategy;

    final long connectionId;
    final BufferClaim bufferClaim;

    final ExclusivePublication publication;
    final ReplayQuery replayQuery;
    final int beginSeqNo;
    final int endSeqNo;
    final long sessionId;
    final int sequenceIndex;
    final Replayer replayer;

    ReplayOperation replayOperation;

    protected ReplayerSession(
        final long connectionId,
        final BufferClaim bufferClaim,
        final IdleStrategy idleStrategy,
        final int maxClaimAttempts,
        final ExclusivePublication publication,
        final ReplayQuery replayQuery,
        final int beginSeqNo,
        final int endSeqNo,
        final long sessionId,
        final int sequenceIndex,
        final Replayer replayer)
    {
        this.connectionId = connectionId;
        this.bufferClaim = bufferClaim;
        this.idleStrategy = idleStrategy;
        this.maxClaimAttempts = maxClaimAttempts;
        this.publication = publication;
        this.replayQuery = replayQuery;
        this.beginSeqNo = beginSeqNo;
        this.endSeqNo = endSeqNo;
        this.sessionId = sessionId;
        this.sequenceIndex = sequenceIndex;
        this.replayer = replayer;
    }

    void query()
    {
        replayOperation = replayQuery.query(
            sessionId,
            beginSeqNo,
            sequenceIndex,
            endSeqNo,
            sequenceIndex,
            REPLAY,
            messageTracker());
    }

    abstract MessageTracker messageTracker();

    boolean claimBuffer(final int newLength)
    {
        for (int i = 0; i < maxClaimAttempts; i++)
        {
            final long position = publication.tryClaim(newLength, bufferClaim);
            if (position > 0)
            {
                idleStrategy.reset();
                return true;
            }
            else if (Pressure.isBackPressured(position))
            {
                idleStrategy.idle();
            }
            else
            {
                return false;
            }
        }

        return false;
    }

    boolean sendCompleteMessage()
    {
        if (claimBuffer(MessageHeaderEncoder.ENCODED_LENGTH + ReplayCompleteEncoder.BLOCK_LENGTH))
        {
            replayer.replayCompleteEncoder.wrapAndApplyHeader(
                bufferClaim.buffer(),
                bufferClaim.offset(),
                replayer.messageHeaderEncoder)
                .connection(connectionId);

            bufferClaim.commit();

            return true;
        }
        else
        {
            return false;
        }
    }

    abstract boolean attemptReplay();

    void close()
    {
        if (replayOperation != null)
        {
            replayOperation.close();
        }
    }
}
