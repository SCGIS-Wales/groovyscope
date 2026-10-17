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
package org.groovyscope.engine.lsp

import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import spock.lang.Specification
import spock.lang.Timeout

/** Drives a real JSON-RPC session over in-memory pipes: the same path Kiro CLI uses over stdio. */
@Timeout(30)
class LspServerRunnerSpec extends Specification {

    ExecutorService executor = Executors.newSingleThreadExecutor()

    def cleanup() {
        executor.shutdownNow()
    }

    def 'a full initialize, initialized, shutdown, exit lifecycle completes over streams'() {
        given: 'two pipes: client -> server and server -> client'
        Pipe toServer = Pipe.open()
        Pipe toClient = Pipe.open()
        def serverIn = Channels.newInputStream(toServer.source())
        def serverOut = Channels.newOutputStream(toClient.sink())
        def clientIn = Channels.newInputStream(toClient.source())
        def clientOut = Channels.newOutputStream(toServer.sink())
        List<Integer> exitCodes = new CopyOnWriteArrayList<>()
        IntConsumer onExit = { int code ->
            exitCodes << code
            clientOut.close()
        }

        and: 'the server listening on its pipe'
        Future<GroovyscopeLanguageServer> serving = executor.submit({
            LspServerRunner.serve(serverIn, serverOut, Paths.get('/tmp/cwd'), onExit)
        } as java.util.concurrent.Callable<GroovyscopeLanguageServer>)

        and: 'a client on the other end'
        def client = new RecordingLanguageClient()
        def launcher = LSPLauncher.createClientLauncher(client, clientIn, clientOut)
        launcher.startListening()
        LanguageServer remote = launcher.remoteProxy

        when:
        def result = remote.initialize(new InitializeParams(rootUri: 'file:///work/root')).get(10, TimeUnit.SECONDS)
        remote.initialized(new InitializedParams())
        remote.shutdown().get(10, TimeUnit.SECONDS)
        remote.exit()
        def server = serving.get(10, TimeUnit.SECONDS)

        then:
        result.serverInfo.name == 'groovyscope-engine'
        server.shutdownRequested()
        server.workspaceRoot() == Paths.get('/work/root')
        exitCodes == [0]

        cleanup:
        [serverIn, serverOut, clientIn, clientOut].each { Closeable c -> safeClose(c) }
    }

    private static void safeClose(Closeable closeable) {
        try {
            closeable.close()
        } catch (IOException ignored) {
            // already closed by the exit handler
        }
    }
}
