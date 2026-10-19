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
package org.groovyscope.engine.probes

import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Probe 0.4 of the improvement plan: what LSP4J does with a request still running when the client's connection
 * closes. It completes the listening future and leaves the request's future alone (neither cancelled nor
 * completed), so the engine must cancel the requests of a closed connection itself (E-15).
 */
@Timeout(30)
class LspCloseProbeSpec extends Specification {

    interface Blocking {
        @JsonRequest('probe/block')
        CompletableFuture<String> block()
    }

    static class BlockingServer implements Blocking {
        final CountDownLatch started = new CountDownLatch(1)
        volatile CompletableFuture<String> pending

        @Override
        CompletableFuture<String> block() {
            pending = new CompletableFuture<String>()
            started.countDown()
            pending
        }
    }

    interface Nothing {
    }

    def 'closing the client connection does not cancel the request still running on the server'() {
        given:
        Pipe toServer = Pipe.open()
        Pipe toClient = Pipe.open()
        def server = new BlockingServer()
        def serverLauncher = Launcher.createLauncher(server, Nothing, Channels.newInputStream(toServer.source()),
                Channels.newOutputStream(toClient.sink()))
        def listening = serverLauncher.startListening()
        OutputStream clientOut = Channels.newOutputStream(toServer.sink())
        def clientLauncher = Launcher.createLauncher(new Object(), Blocking, Channels.newInputStream(toClient.source()),
                clientOut)
        clientLauncher.startListening()

        when:
        clientLauncher.remoteProxy.block()
        server.started.await(10, TimeUnit.SECONDS)
        clientOut.close()
        listening.get(10, TimeUnit.SECONDS)

        then: 'the server stopped listening, and the request is still open'
        listening.done
        !server.pending.cancelled
        !server.pending.done

        cleanup:
        server.pending?.cancel(true)
        [toServer.source(), toServer.sink(), toClient.source(), toClient.sink()].each { it.close() }
    }
}
