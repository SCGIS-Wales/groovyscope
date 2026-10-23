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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.Timeout

/** The practice requests (fix, explain), diagnostic links and hover notes over a real JSON-RPC session. */
@Timeout(120)
class LspPracticesSpec extends Specification {

    interface PracticesRemote extends LanguageServer {
        @JsonRequest('groovyscope/check')
        CompletableFuture<Map<String, Object>> check(Map<String, Object> params)

        @JsonRequest('groovyscope/fix')
        CompletableFuture<Map<String, Object>> fix(Map<String, Object> params)

        @JsonRequest('groovyscope/explain')
        CompletableFuture<Map<String, Object>> explain(Map<String, Object> params)
    }

    ExecutorService executor = Executors.newSingleThreadExecutor()
    Pipe toServer = Pipe.open()
    Pipe toClient = Pipe.open()
    InputStream serverIn = Channels.newInputStream(toServer.source())
    OutputStream serverOut = Channels.newOutputStream(toClient.sink())
    InputStream clientIn = Channels.newInputStream(toClient.source())
    OutputStream clientOut = Channels.newOutputStream(toServer.sink())
    RecordingLanguageClient client = new RecordingLanguageClient()
    Future<GroovyscopeLanguageServer> serving
    PracticesRemote remote
    String numbers = Fixtures.uri('practices-sample', 'src/main/groovy/sample/Numbers.groovy')

    def setup() {
        IntConsumer onExit = { int code -> clientOut.close() }
        serving = executor.submit({
            LspServerRunner.serve(serverIn, serverOut, Paths.get('/tmp/cwd'), onExit, EngineConfig.DEFAULTS)
        } as Callable<GroovyscopeLanguageServer>)
        Launcher<PracticesRemote> launcher = new LSPLauncher.Builder<PracticesRemote>()
            .setLocalService(client)
            .setRemoteInterface(PracticesRemote)
            .setInput(clientIn)
            .setOutput(clientOut)
            .create()
        launcher.startListening()
        remote = launcher.remoteProxy
        remote.initialize(new InitializeParams(rootUri: Fixtures.project('practices-sample').toUri().toString())).get(60, TimeUnit.SECONDS)
        remote.initialized(new InitializedParams())
    }

    def cleanup() {
        remote.shutdown().get(60, TimeUnit.SECONDS)
        remote.exit()
        serving.get(60, TimeUnit.SECONDS)
        [serverIn, serverOut, clientIn, clientOut].each { Closeable c -> try { c.close() } catch (IOException ignored) { } }
        executor.shutdownNow()
    }

    def 'practice findings carry links, explain answers, fix returns edits, hover explains the finding'() {
        when:
        def checked = remote.check([uris: [numbers], include: ['practices']]).get(60, TimeUnit.SECONDS)
        def explained = remote.explain([code: 'GS002']).get(60, TimeUnit.SECONDS)
        def unknown = remote.explain([code: 'Nope']).get(60, TimeUnit.SECONDS)
        def fixed = remote.fix([uris: [numbers], codes: ['GS001']]).get(60, TimeUnit.SECONDS)
        def hover = remote.textDocumentService.hover(new HoverParams(new TextDocumentIdentifier(numbers), new Position(4, 30))).get(60, TimeUnit.SECONDS)
        def actions = remote.textDocumentService.codeAction(new CodeActionParams(new TextDocumentIdentifier(numbers),
                new Range(new Position(4, 0), new Position(4, 1)), new CodeActionContext([]))).get(60, TimeUnit.SECONDS)

        then:
        def first = (checked.diagnostics[numbers] as List).find { !it.code.startsWith('GS09') }
        first.code == 'GS001'
        first.codeDescription.href.endsWith('docs/practices.md#23-numeric-pitfalls')
        explained.title == 'Elvis on a value where 0 or empty is valid'
        unknown == null
        fixed.applied[numbers] == ['GS001']
        (fixed.edits[numbers] as List)[0].newText == 'total.intdiv(parts)'
        hover.contents.getRight().value.contains('**GS001** Integer division with /')
        actions.collect { it.getRight().title }.contains('Use intdiv')
    }
}
