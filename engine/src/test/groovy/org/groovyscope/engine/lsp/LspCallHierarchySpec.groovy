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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.Timeout

/** Call hierarchy, semantic tokens and the impact and call-graph requests over a real session. */
@Timeout(120)
class LspCallHierarchySpec extends Specification {

    interface GraphRemote extends LanguageServer {
        @JsonRequest('groovyscope/impact')
        CompletableFuture<List<Map<String, Object>>> impact(Map<String, Object> params)

        @JsonRequest('groovyscope/callGraph')
        CompletableFuture<Map<String, Object>> callGraph(Map<String, Object> params)

        @JsonRequest('groovyscope/symbols')
        CompletableFuture<Map<String, Object>> symbols(Map<String, Object> params)

        @JsonRequest('groovyscope/definition')
        CompletableFuture<Map<String, Object>> definition(Map<String, Object> params)

        @JsonRequest('groovyscope/references')
        CompletableFuture<Map<String, Object>> references(Map<String, Object> params)

        @JsonRequest('groovyscope/dynamicCode')
        CompletableFuture<Map<String, Object>> dynamicCode(Map<String, Object> params)
    }

    static final String MAIN_REL = 'src/main/groovy/app/Main.groovy'
    static final String GREETER_REL = 'src/main/groovy/app/Greeter.groovy'

    ExecutorService executor = Executors.newSingleThreadExecutor()
    Pipe toServer = Pipe.open()
    Pipe toClient = Pipe.open()
    InputStream serverIn = Channels.newInputStream(toServer.source())
    OutputStream serverOut = Channels.newOutputStream(toClient.sink())
    InputStream clientIn = Channels.newInputStream(toClient.source())
    OutputStream clientOut = Channels.newOutputStream(toServer.sink())
    RecordingLanguageClient client = new RecordingLanguageClient()
    Future<GroovyscopeLanguageServer> serving
    GraphRemote remote
    String main = Fixtures.uri('basic-app', MAIN_REL)
    String greeter = Fixtures.uri('basic-app', GREETER_REL)

    def setup() {
        IntConsumer onExit = { int code -> clientOut.close() }
        serving = executor.submit({
            LspServerRunner.serve(serverIn, serverOut, Paths.get('/tmp/cwd'), onExit, EngineConfig.DEFAULTS)
        } as Callable<GroovyscopeLanguageServer>)
        Launcher<GraphRemote> launcher = new LSPLauncher.Builder<GraphRemote>()
            .setLocalService(client)
            .setRemoteInterface(GraphRemote)
            .setInput(clientIn)
            .setOutput(clientOut)
            .create()
        launcher.startListening()
        remote = launcher.remoteProxy
        remote.initialize(new InitializeParams(rootUri: Fixtures.project('basic-app').toUri().toString())).get(60, TimeUnit.SECONDS)
        remote.initialized(new InitializedParams())
    }

    def cleanup() {
        remote.shutdown().get(60, TimeUnit.SECONDS)
        remote.exit()
        serving.get(60, TimeUnit.SECONDS)
        [serverIn, serverOut, clientIn, clientOut].each { Closeable c -> try { c.close() } catch (IOException ignored) { } }
        executor.shutdownNow()
    }

    private static Position lsp(org.groovyscope.engine.model.Position position) {
        new Position(position.line(), position.column())
    }

    def 'call hierarchy and semantic tokens over the wire'() {
        given:
        def greetDeclaration = lsp(Fixtures.pos('basic-app', GREETER_REL, 'String greet(String name)', 1, 7))

        when:
        def items = remote.textDocumentService.prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(greeter), greetDeclaration)).get(60, TimeUnit.SECONDS)
        def incoming = remote.textDocumentService.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items[0])).get(60, TimeUnit.SECONDS)
        def outgoing = remote.textDocumentService.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items[0])).get(60, TimeUnit.SECONDS)
        def tokens = remote.textDocumentService.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(greeter))).get(60, TimeUnit.SECONDS)

        then:
        items*.name == ['greet']
        incoming*.from*.uri.toSet().containsAll([main, greeter])
        incoming.every { !it.fromRanges.empty }
        outgoing.empty || outgoing.every { it.to.uri != null }
        tokens.data.size() % 5 == 0 && tokens.data.size() > 0
        remote.textDocumentService.prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(main), new Position(0, 0))).get(60, TimeUnit.SECONDS).empty
    }

    def 'impact and call graph requests'() {
        given:
        def callPosition = Fixtures.pos('basic-app', MAIN_REL, "greeter.greet('Ada')", 1, 8)

        when:
        def byName = remote.impact([symbol: 'app.Greeter.greet', change: 'signature']).get(60, TimeUnit.SECONDS)
        def atCursor = remote.impact([uri: main, line: callPosition.line(), character: callPosition.column(), change: 'behaviour']).get(60, TimeUnit.SECONDS)
        def graph = remote.callGraph([symbol: 'Greeter.greet', direction: 'in', depth: 2]).get(60, TimeUnit.SECONDS)

        then:
        byName.size() == 2
        byName.every { it.riskLevel in ['low', 'medium', 'high'] && it.counts.direct >= 0 }
        atCursor.size() == 1
        atCursor[0].symbol.detail == 'String greet(String name)'
        graph.nodes.size() >= 3
        graph.edges.every { it.confidence in ['STATIC', 'INFERRED', 'DYNAMIC_UNKNOWN'] }

        when:
        remote.impact([symbol: 'x', change: 'nonsense']).get(60, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause.message.contains('unknown change kind')

        when:
        remote.callGraph([depth: 1]).get(60, TimeUnit.SECONDS)

        then:
        thrown(ExecutionException)
    }
    def 'symbols, definition, references and dynamic code requests used by the MCP server'() {
        given:
        def callPosition = Fixtures.pos('basic-app', MAIN_REL, "greeter.greet('Ada')", 1, 8)
        def declaration = Fixtures.pos('basic-app', GREETER_REL, 'String greet(String name)', 1, 7)

        when:
        def symbols = remote.symbols([query: 'greet']).get(60, TimeUnit.SECONDS)
        def definition = remote.definition([uri: main, line: callPosition.line(), character: callPosition.column()]).get(60, TimeUnit.SECONDS)
        def nowhere = remote.definition([uri: main, line: 0, character: 0]).get(60, TimeUnit.SECONDS)
        def references = remote.references([uri: greeter, line: declaration.line(), character: declaration.column(), includeDeclaration: true]).get(60, TimeUnit.SECONDS)
        def dynamic = remote.dynamicCode([uris: [greeter]]).get(60, TimeUnit.SECONDS)
        def everything = remote.dynamicCode([:]).get(60, TimeUnit.SECONDS)

        then:
        symbols.count >= 2 && symbols.symbols*.name.every { it.toLowerCase().contains('greet') }
        definition.symbol.name == 'greet'
        definition.confidence in ['STATIC', 'INFERRED']
        definition.inWorkspace == true
        nowhere == null
        references.references.size() >= 3
        references.references.any { it.uri == greeter }
        references.counts.values().sum() == references.references.size()
        dynamic.findings*.kind == ['untyped-class']
        dynamic.counts == ['untyped-class': 1]
        everything.findings.size() > dynamic.findings.size()

        when:
        remote.references([line: 1]).get(60, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause.message.contains('needs a uri')
    }
}
