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
package org.groovyscope.engine.daemon

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/** A daemon connection reads and writes at the same time, on every supported Java (JDK-8279339 before 19). */
@Timeout(60)
class ChannelStreamsSpec extends Specification {

    def 'a write goes out while a read on the same channel is blocked'() {
        given:
        Path dir = Files.createTempDirectory(Paths.get('/tmp'), 'gs')
        Path socket = dir.resolve('s.sock')
        def server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        def client = SocketChannel.open(UnixDomainSocketAddress.of(socket))
        def accepted = server.accept()
        def input = ChannelStreams.input(accepted)
        def output = ChannelStreams.output(accepted)

        when: 'the reader blocks first, then the same side writes'
        def reading = CompletableFuture.supplyAsync { input.read() }
        Thread.sleep(200) // let the reader block; the write below must not wait for it
        output.write('pong'.bytes)
        def reply = ByteBufferRead.readFully(client, 4)
        client.write(java.nio.ByteBuffer.wrap([42] as byte[]))

        then:
        new String(reply) == 'pong'
        reading.get(10, TimeUnit.SECONDS) == 42
        input.read(new byte[0], 0, 0) == 0

        when:
        client.close()

        then:
        input.read() == -1

        cleanup:
        accepted?.close()
        server?.close()
        Files.deleteIfExists(socket)
        Files.deleteIfExists(dir)
    }

    static class ByteBufferRead {
        static byte[] readFully(SocketChannel channel, int size) {
            def buffer = java.nio.ByteBuffer.allocate(size)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break
                }
            }
            buffer.array()
        }
    }
}
