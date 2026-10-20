/*
 * Copyright 2026 SCGIS-Wales and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.groovyscope.engine.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/**
 * Streams over a connected socket channel that let one thread read while another writes. The streams from
 * {@link java.nio.channels.Channels} hold the channel's blocking lock for a whole read before Java 19 (JDK-8279339),
 * so a daemon on Java 17 could not answer a request while its reader waited for the next one. These call the
 * channel's own {@code read} and {@code write}, which take separate locks. Closing either stream closes the channel.
 */
final class ChannelStreams {

    private ChannelStreams() {
    }

    static InputStream input(SocketChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                if (length == 0) {
                    return 0;
                }
                return channel.read(ByteBuffer.wrap(buffer, offset, length));
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    static OutputStream output(SocketChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                ByteBuffer pending = ByteBuffer.wrap(buffer, offset, length);
                while (pending.hasRemaining()) {
                    channel.write(pending);
                }
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }
}
