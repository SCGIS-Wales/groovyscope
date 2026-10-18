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
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.Timeout

/** Drives a real JSON-RPC session over in-memory pipes: the same path Kiro CLI uses over stdio. */
@Timeout(60)
class LspServerRunnerSpec extends Specification {

    ExecutorService executor = Executors.newSingleThreadExecutor()
    Pipe toServer = Pipe.open()
    Pipe toClient = Pipe.open()
    InputStream serverIn = Channels.newInputStream(toServer.source())
    OutputStream serverOut = Channels.newOutputStream(toClient.sink())
    InputStream clientIn = Channels.newInputStream(toClient.source())
    OutputStream clientOut = Channels.newOutputStream(toServer.sink())
    List<Integer> exitCodes = new CopyOnWriteArrayList<>()
    RecordingLanguageClient client = new RecordingLanguageClient()
    Future<GroovyscopeLanguageServer> serving
    LanguageServer remote

    def setup() {
        IntConsumer onExit = { int code ->
            exitCodes << code
            clientOut.close()
        }
        serving = executor.submit({
            LspServerRunner.serve(serverIn, serverOut, Paths.get('/tmp/cwd'), onExit, EngineConfig.DEFAULTS)
        } as Callable<GroovyscopeLanguageServer>)
        def launcher = LSPLauncher.createClientLauncher(client, clientIn, clientOut)
        launcher.startListening()
        remote = launcher.remoteProxy
    }

    def cleanup() {
        [serverIn, serverOut, clientIn, clientOut].each { Closeable c -> safeClose(c) }
        executor.shutdownNow()
    }

    def 'a full initialize, initialized, shutdown, exit lifecycle completes over streams'() {
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
    }

    def 'navigation requests and diagnostics flow end to end'() {
        given:
        String root = Fixtures.project('basic-app').toUri().toString()
        String main = Fixtures.uri('basic-app', 'src/main/groovy/app/Main.groovy')
        String greeter = Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy')
        String spec = Fixtures.uri('basic-app', 'src/test/groovy/app/GreeterSpec.groovy')
        def callPosition = Fixtures.pos('basic-app', 'src/main/groovy/app/Main.groovy', "greeter.greet('Ada')", 1, 8)

        when:
        remote.initialize(new InitializeParams(rootUri: root)).get(10, TimeUnit.SECONDS)
        remote.initialized(new InitializedParams())
        remote.textDocumentService.didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(main, 'groovy', 1, Fixtures.text('basic-app', 'src/main/groovy/app/Main.groovy'))))
        remote.textDocumentService.didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(spec, 'groovy', 1, Fixtures.text('basic-app', 'src/test/groovy/app/GreeterSpec.groovy'))))
        def definition = remote.textDocumentService.definition(new DefinitionParams(
            new TextDocumentIdentifier(main), new Position(callPosition.line(), callPosition.column()))).get(30, TimeUnit.SECONDS)
        def references = remote.textDocumentService.references(new ReferenceParams(
            new TextDocumentIdentifier(main), new Position(callPosition.line(), callPosition.column()),
            new ReferenceContext(false))).get(30, TimeUnit.SECONDS)
        def symbols = remote.textDocumentService.documentSymbol(new DocumentSymbolParams(
            new TextDocumentIdentifier(greeter))).get(30, TimeUnit.SECONDS)
        def workspaceSymbols = remote.workspaceService.symbol(new WorkspaceSymbolParams('Gree')).get(30, TimeUnit.SECONDS)
        waitForDiagnostics(spec)

        then:
        definition.getLeft()*.uri == [greeter]
        references.size() >= 3
        symbols[0].getRight().name == 'Greeter'
        symbols[0].getRight().children*.name.contains('greet')
        workspaceSymbols.getRight()*.name.contains('Greeter')
        client.received.find { it instanceof PublishDiagnosticsParams && it.uri == spec }.diagnostics.size() >= 1

        cleanup:
        remote.shutdown().get(10, TimeUnit.SECONDS)
        remote.exit()
        serving.get(10, TimeUnit.SECONDS)
    }

    private void waitForDiagnostics(String uri) {
        long deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (client.received.any { it instanceof PublishDiagnosticsParams && it.uri == uri }) {
                return
            }
            Thread.sleep(50)
        }
        assert false : 'no diagnostics published for ' + uri
    }

    private static void safeClose(Closeable closeable) {
        try {
            closeable.close()
        } catch (IOException ignored) {
            // already closed by the exit handler
        }
    }
}
