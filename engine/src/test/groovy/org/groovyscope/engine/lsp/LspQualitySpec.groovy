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
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
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

/** Formatting, quick fixes, pull diagnostics and the check/format RPC over a real JSON-RPC session. */
@Timeout(120)
class LspQualitySpec extends Specification {

    interface QualityRemote extends LanguageServer {
        @JsonRequest('groovyscope/check')
        CompletableFuture<Map<String, Object>> check(Map<String, Object> params)

        @JsonRequest('groovyscope/format')
        CompletableFuture<Map<String, Object>> format(Map<String, Object> params)
    }

    static final String MESSY_REL = 'src/main/groovy/sample/Messy.groovy'

    ExecutorService executor = Executors.newSingleThreadExecutor()
    Pipe toServer = Pipe.open()
    Pipe toClient = Pipe.open()
    InputStream serverIn = Channels.newInputStream(toServer.source())
    OutputStream serverOut = Channels.newOutputStream(toClient.sink())
    InputStream clientIn = Channels.newInputStream(toClient.source())
    OutputStream clientOut = Channels.newOutputStream(toServer.sink())
    RecordingLanguageClient client = new RecordingLanguageClient()
    Future<GroovyscopeLanguageServer> serving
    QualityRemote remote
    String messy = Fixtures.uri('lint-sample', MESSY_REL)

    def setup() {
        IntConsumer onExit = { int code -> clientOut.close() }
        serving = executor.submit({
            LspServerRunner.serve(serverIn, serverOut, Paths.get('/tmp/cwd'), onExit, EngineConfig.DEFAULTS)
        } as Callable<GroovyscopeLanguageServer>)
        Launcher<QualityRemote> launcher = new LSPLauncher.Builder<QualityRemote>()
            .setLocalService(client)
            .setRemoteInterface(QualityRemote)
            .setInput(clientIn)
            .setOutput(clientOut)
            .create()
        launcher.startListening()
        remote = launcher.remoteProxy
        remote.initialize(new InitializeParams(rootUri: Fixtures.project('lint-sample').toUri().toString())).get(60, TimeUnit.SECONDS)
        remote.initialized(new InitializedParams())
    }

    def cleanup() {
        remote.shutdown().get(60, TimeUnit.SECONDS)
        remote.exit()
        serving.get(60, TimeUnit.SECONDS)
        [serverIn, serverOut, clientIn, clientOut].each { Closeable c -> try { c.close() } catch (IOException ignored) { } }
        executor.shutdownNow()
    }

    def 'formatting, quick fixes and pulled diagnostics answer over the wire'() {
        given:
        String text = Fixtures.text('lint-sample', MESSY_REL)
        int semicolonLine = text.readLines().findIndexOf { it.contains('def untyped = 1;') }
        def identifier = new TextDocumentIdentifier(messy)

        when:
        def edits = remote.textDocumentService.formatting(new DocumentFormattingParams(identifier, new FormattingOptions(4, true))).get(60, TimeUnit.SECONDS)
        def report = remote.textDocumentService.diagnostic(new DocumentDiagnosticParams(identifier)).get(60, TimeUnit.SECONDS)
        def actions = remote.textDocumentService.codeAction(new CodeActionParams(identifier,
            new Range(new Position(semicolonLine, 0), new Position(semicolonLine, 1)), new CodeActionContext([]))).get(60, TimeUnit.SECONDS)

        then:
        edits.size() == 1
        edits[0].newText.contains('String name = "plain"\n')
        report.getRelatedFullDocumentDiagnosticReport().items*.code*.getLeft().contains('UnnecessarySemicolon')
        actions*.getRight()*.title.contains('Remove unnecessary semicolon')
        actions.find { it.getRight().title == 'Remove unnecessary semicolon' }.getRight().edit.changes[messy].size() == 1
    }

    def 'check and format are available as custom requests'() {
        when:
        def checked = remote.check([uris: [messy], include: ['lint']]).get(60, TimeUnit.SECONDS)
        def formatted = remote.format([uris: [messy]]).get(60, TimeUnit.SECONDS)
        def everything = remote.format([:]).get(60, TimeUnit.SECONDS)

        then:
        checked.summary.files == 1
        checked.summary.warnings >= 1
        (checked.diagnostics[messy] as List)*.code.contains('UnnecessarySemicolon')
        formatted.changed == 1
        formatted.checked == 1
        (formatted.edits[messy] as List).size() == 1
        everything.checked == 4
    }
}
