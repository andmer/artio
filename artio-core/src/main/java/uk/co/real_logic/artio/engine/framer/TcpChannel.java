/*
 * Copyright 2015-2020 Real Logic Limited.
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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.LangUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class TcpChannel implements AutoCloseable
{
    private final SocketChannel socketChannel;
    private final String remoteAddress;

    public TcpChannel(final SocketChannel socketChannel) throws IOException
    {
        this.socketChannel = socketChannel;
        remoteAddress = socketChannel.getRemoteAddress().toString();
    }

    public String remoteAddress()
    {
        return remoteAddress;
    }

    public SelectionKey register(final Selector sel, final int ops, final Object att) throws ClosedChannelException
    {
        return socketChannel.register(sel, ops, att);
    }

    public int write(final ByteBuffer src) throws IOException
    {
        return socketChannel.write(src);
    }

    public int read(final ByteBuffer dst) throws IOException
    {
        return socketChannel.read(dst);
    }

    public void close()
    {
        if (socketChannel.isOpen())
        {
            try
            {
                socketChannel.close();
            }
            catch (final IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
            }
        }
    }
}
