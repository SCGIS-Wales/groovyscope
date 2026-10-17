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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FoldingRangeKind
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Timeout

/**
 * Implementation, document highlights, folding, inlay hints, range formatting, fixable diagnostics and source
 * actions over a real JSON-RPC session.
 */
@Timeout(120)
class LspEditorFeaturesSpec extends spock.lang.Specification {

    static final String MESSY_REL = 'src/main/groovy/sample/Messy.groovy'
    static final String SHAPES_REL = 'src/main/groovy/app/Shapes.groovy'

    ExecutorService executor = Executors.newSingleThreadExecutor()
    Pipe toServer = Pipe.open()
    Pipe toClient = Pipe.open()
    InputStream serverIn = Channels.newInputStream(toServer.source())
    OutputStream serverOut = Channels.newOutputStream(toClient.sink())
    InputStream clientIn = Channels.newInputStream(toClient.source())
    OutputStream clientOut = Channels.newOutputStream(toServer.sink())
    RecordingLanguageClient client = new RecordingLanguageClient()
    Future<GroovyscopeLanguageServer> serving
    LanguageServer remote
    InitializeResult initialized

    private void connect(String project) {
        IntConsumer onExit = { int code -> clientOut.close() }
        serving = executor.submit({
            LspServerRunner.serve(serverIn, serverOut, Paths.get('/tmp/cwd'), onExit, EngineConfig.DEFAULTS)
        } as Callable<GroovyscopeLanguageServer>)
        Launcher<LanguageServer> launcher = new LSPLauncher.Builder<LanguageServer>()
            .setLocalService(client)
            .setRemoteInterface(LanguageServer)
            .setInput(clientIn)
            .setOutput(clientOut)
            .create()
        launcher.startListening()
        remote = launcher.remoteProxy
        initialized = remote.initialize(new InitializeParams(rootUri: Fixtures.project(project).toUri().toString()))
            .get(60, TimeUnit.SECONDS)
        remote.initialized(new InitializedParams())
    }

    def cleanup() {
        if (remote != null) {
            remote.shutdown().get(60, TimeUnit.SECONDS)
            remote.exit()
            serving.get(60, TimeUnit.SECONDS)
        }
        [serverIn, serverOut, clientIn, clientOut].each { Closeable c -> try { c.close() } catch (IOException ignored) { } }
        executor.shutdownNow()
    }

    private static Position pos(String project, String relative, String needle, int occurrence = 1, int offset = 0) {
        def at = Fixtures.pos(project, relative, needle, occurrence, offset)
        new Position(at.line(), at.column())
    }

    def 'initialize advertises the editor features and source action kinds'() {
        when:
        connect('lint-sample')
        def capabilities = initialized.capabilities

        then:
        capabilities.implementationProvider.getLeft()
        capabilities.documentHighlightProvider.getLeft()
        capabilities.foldingRangeProvider.getLeft()
        capabilities.inlayHintProvider.getLeft()
        capabilities.documentRangeFormattingProvider.getLeft()
        capabilities.codeActionProvider.getRight().codeActionKinds ==
                [CodeActionKind.QuickFix, 'source.fixAll.groovyscope', CodeActionKind.SourceOrganizeImports]
    }

    def 'implementation finds implementing methods and classes'() {
        given:
        connect('basic-app')
        String shapes = Fixtures.uri('basic-app', SHAPES_REL)
        def identifier = new TextDocumentIdentifier(shapes)

        when:
        def methods = remote.textDocumentService.implementation(new ImplementationParams(identifier,
                pos('basic-app', SHAPES_REL, 'area()', 1, 1))).get(60, TimeUnit.SECONDS).getLeft()
        def classes = remote.textDocumentService.implementation(new ImplementationParams(identifier,
                pos('basic-app', SHAPES_REL, 'Shape {', 1, 1))).get(60, TimeUnit.SECONDS).getLeft()

        then:
        methods.size() == 1
        methods[0].uri == shapes
        methods[0].range.start.line == pos('basic-app', SHAPES_REL, 'area()', 2).line
        classes*.range*.start*.line == [pos('basic-app', SHAPES_REL, 'class Circle').line]
    }

    def 'document highlights, folding ranges and inlay hints answer over the wire'() {
        given:
        connect('lint-sample')
        String messy = Fixtures.uri('lint-sample', MESSY_REL)
        def identifier = new TextDocumentIdentifier(messy)
        int resultLine = pos('lint-sample', MESSY_REL, 'def result').line

        when:
        def highlights = remote.textDocumentService.documentHighlight(new DocumentHighlightParams(identifier,
                pos('lint-sample', MESSY_REL, 'def result', 1, 5))).get(60, TimeUnit.SECONDS)
        def folds = remote.textDocumentService.foldingRange(new FoldingRangeRequestParams(identifier)).get(60, TimeUnit.SECONDS)
        def hints = remote.textDocumentService.inlayHint(new InlayHintParams(identifier,
                new Range(new Position(0, 0), new Position(40, 0)))).get(60, TimeUnit.SECONDS)
        def none = remote.textDocumentService.inlayHint(new InlayHintParams(identifier,
                new Range(new Position(0, 0), new Position(1, 0)))).get(60, TimeUnit.SECONDS)

        then:
        highlights.size() == 3
        highlights.find { it.range.start.line == resultLine }.kind == DocumentHighlightKind.Write
        highlights.findAll { it.range.start.line != resultLine }*.kind as Set == [DocumentHighlightKind.Read] as Set
        folds.find { it.kind == FoldingRangeKind.Imports }.with { [startLine, endLine] } == [2, 3]
        folds.any { it.kind == FoldingRangeKind.Region && it.startLine == pos('lint-sample', MESSY_REL, 'class Messy').line }
        hints.size() == 1
        hints[0].label.getLeft() == ': ArrayList'
        hints[0].kind == InlayHintKind.Type
        hints[0].position == pos('lint-sample', MESSY_REL, 'def result', 1, 10)
        none.isEmpty()
    }

    def 'range formatting only touches lines inside the range'() {
        given:
        connect('lint-sample')
        def identifier = new TextDocumentIdentifier(Fixtures.uri('lint-sample', MESSY_REL))
        int nameLine = pos('lint-sample', MESSY_REL, 'String name').line

        when:
        def edits = remote.textDocumentService.rangeFormatting(new DocumentRangeFormattingParams(identifier,
                new FormattingOptions(4, true), new Range(new Position(nameLine, 0), new Position(nameLine + 1, 0))))
                .get(60, TimeUnit.SECONDS)

        then:
        edits.size() == 1
        edits[0].range.start.line == nameLine
        edits[0].range.end.line == nameLine
        edits[0].newText == '    String name = "plain"'
    }

    def 'fixable diagnostics carry data and the source actions fix all and organize imports'() {
        given:
        connect('lint-sample')
        String messy = Fixtures.uri('lint-sample', MESSY_REL)
        def identifier = new TextDocumentIdentifier(messy)
        def everywhere = new Range(new Position(0, 0), new Position(0, 1))

        when:
        def report = remote.textDocumentService.diagnostic(new DocumentDiagnosticParams(identifier)).get(60, TimeUnit.SECONDS)
        def items = report.getRelatedFullDocumentDiagnosticReport().items
        def sources = remote.textDocumentService.codeAction(new CodeActionParams(identifier, everywhere,
                new CodeActionContext([], ['source']))).get(60, TimeUnit.SECONDS)*.getRight()
        def imports = remote.textDocumentService.codeAction(new CodeActionParams(identifier, everywhere,
                new CodeActionContext([], [CodeActionKind.SourceOrganizeImports]))).get(60, TimeUnit.SECONDS)*.getRight()
        def quickOnly = remote.textDocumentService.codeAction(new CodeActionParams(identifier, everywhere,
                new CodeActionContext([], [CodeActionKind.QuickFix]))).get(60, TimeUnit.SECONDS)*.getRight()

        then:
        items.find { it.code.getLeft() == 'NoDef' }.data.toString() == '{"fixable":true}'
        items.find { it.code.getLeft() == 'ExplicitArrayListInstantiation' }.data == null
        sources*.kind as Set == ['source.fixAll.groovyscope', CodeActionKind.SourceOrganizeImports] as Set
        sources.find { it.kind == 'source.fixAll.groovyscope' }.edit.changes[messy].size() >= 1
        imports.size() == 1
        imports[0].edit.changes[messy]*.range*.start*.line == [2, 3]
        imports[0].edit.changes[messy].every { it.newText == '' && it.range.end.line == it.range.start.line + 1 }
        quickOnly.every { it.kind == CodeActionKind.QuickFix }
    }

    def 'pushed diagnostics mark fixable findings too'() {
        when:
        connect('lint-sample')
        String messy = Fixtures.uri('lint-sample', MESSY_REL)
        PublishDiagnosticsParams pushed = null
        for (int i = 0; i < 600 && pushed == null; i++) {
            pushed = client.received.find { it instanceof PublishDiagnosticsParams && it.uri == messy } as PublishDiagnosticsParams
            if (pushed == null) {
                Thread.sleep(100)
            }
        }

        then:
        pushed != null
        pushed.diagnostics.any { it.data != null && it.data.toString() == '{"fixable":true}' }
        !pushed.diagnostics.any { it.code.getLeft() == 'UnnecessarySemicolon' }
    }
}
