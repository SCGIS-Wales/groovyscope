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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.Timeout

/** The editing side of a session: document sync, hover, completion, signature help, rename and refusals. */
@Timeout(90)
class LspSessionSpec extends Specification {

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
    LanguageServer remote
    String main = Fixtures.uri('basic-app', MAIN_REL)
    String greeter = Fixtures.uri('basic-app', GREETER_REL)

    def setup() {
        IntConsumer onExit = { int code -> clientOut.close() }
        serving = executor.submit({
            LspServerRunner.serve(serverIn, serverOut, Paths.get('/tmp/cwd'), onExit, EngineConfig.DEFAULTS)
        } as Callable<GroovyscopeLanguageServer>)
        def launcher = LSPLauncher.createClientLauncher(client, clientIn, clientOut)
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

    private Position at(String needle, int occurrence = 1, int offset = 0) {
        lsp(Fixtures.pos('basic-app', MAIN_REL, needle, occurrence, offset))
    }

    def 'hover, completion, signature help and type definition answer over the wire'() {
        when:
        def hover = remote.textDocumentService.hover(new HoverParams(new TextDocumentIdentifier(main), at("greeter.greet('Ada')", 1, 8))).get(30, TimeUnit.SECONDS)
        def completion = remote.textDocumentService.completion(new CompletionParams(new TextDocumentIdentifier(main), at("greeter.greet('Ada')", 1, 8))).get(30, TimeUnit.SECONDS)
        def signature = remote.textDocumentService.signatureHelp(new SignatureHelpParams(new TextDocumentIdentifier(main), at("greeter.greet('Ada')", 1, 15))).get(30, TimeUnit.SECONDS)
        def typeDefinition = remote.textDocumentService.typeDefinition(new TypeDefinitionParams(new TextDocumentIdentifier(main), at('p.adult'))).get(30, TimeUnit.SECONDS)
        def nothing = remote.textDocumentService.hover(new HoverParams(new TextDocumentIdentifier(main), new Position(0, 0))).get(30, TimeUnit.SECONDS)

        then:
        hover.contents.getRight().value.contains('String greet(String name)')
        completion.getRight().items*.label.contains('greeting')
        signature.signatures*.label == ['String greet(String name)']
        typeDefinition.getLeft()*.uri == [Fixtures.uri('basic-app', 'src/main/groovy/app/model/Person.groovy')]
        nothing == null || nothing.contents.getRight().value.contains('script Main')
    }

    def 'rename succeeds for a property and is refused for a dynamically called method'() {
        given:
        def greetingPosition = lsp(Fixtures.pos('basic-app', GREETER_REL, 'String greeting', 1, 7))
        def greetPosition = lsp(Fixtures.pos('basic-app', GREETER_REL, 'String greet(String name)', 1, 7))

        when:
        def prepared = remote.textDocumentService.prepareRename(new PrepareRenameParams(new TextDocumentIdentifier(greeter), greetingPosition)).get(30, TimeUnit.SECONDS)
        def edit = remote.textDocumentService.rename(new RenameParams(new TextDocumentIdentifier(greeter), greetingPosition, 'salutation')).get(30, TimeUnit.SECONDS)

        then:
        prepared.getSecond().placeholder == 'greeting'
        edit.changes[greeter].size() == 2

        when:
        remote.textDocumentService.rename(new RenameParams(new TextDocumentIdentifier(greeter), greetPosition, 'welcome')).get(30, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause instanceof ResponseErrorException
        e.cause.message.contains('name only')

        when:
        remote.textDocumentService.prepareRename(new PrepareRenameParams(new TextDocumentIdentifier(greeter), new Position(0, 0))).get(30, TimeUnit.SECONDS)

        then:
        thrown(ExecutionException)
    }

    def 'incremental edits, saves, closes and watched-file events keep the snapshot current'() {
        given:
        String original = Fixtures.text('basic-app', MAIN_REL)
        def dynamicCall = Fixtures.pos('basic-app', MAIN_REL, "unknownThing.greet('x')")
        def lineEnd = new Position(dynamicCall.line(), dynamicCall.column() + "unknownThing.greet('x')".length())
        def greetPosition = lsp(Fixtures.pos('basic-app', GREETER_REL, 'String greet(String name)', 1, 7))

        when: 'the dynamic call is replaced through an incremental change'
        remote.textDocumentService.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(main, 'groovy', 1, original)))
        remote.textDocumentService.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(main, 2),
            [new TextDocumentContentChangeEvent(new Range(lsp(dynamicCall), lineEnd), "typed.greet('Bob')")]))
        def edit = remote.textDocumentService.rename(new RenameParams(new TextDocumentIdentifier(greeter), greetPosition, 'welcome')).get(30, TimeUnit.SECONDS)

        then: 'rename is no longer refused'
        edit.changes[main].size() == 2

        when: 'a full-content change restores the file, then save, close and a watched-file event arrive'
        remote.textDocumentService.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(main, 3),
            [new TextDocumentContentChangeEvent(original)]))
        remote.textDocumentService.didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(main)))
        remote.textDocumentService.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(main)))
        remote.workspaceService.didChangeWatchedFiles(new DidChangeWatchedFilesParams([new FileEvent(main, FileChangeType.Changed)]))
        remote.workspaceService.didChangeConfiguration(new DidChangeConfigurationParams([:]))
        remote.textDocumentService.rename(new RenameParams(new TextDocumentIdentifier(greeter), greetPosition, 'welcome')).get(30, TimeUnit.SECONDS)

        then: 'the on-disk file with the dynamic call is back, so rename is refused again'
        thrown(ExecutionException)
    }

    def 'incremental changes are applied by range'() {
        expect:
        GroovyscopeTextDocumentService.applyIncremental('hello world', new TextDocumentContentChangeEvent(new Range(new Position(0, 6), new Position(0, 11)), 'there')) == 'hello there'
        GroovyscopeTextDocumentService.applyIncremental('a\nb\nc', new TextDocumentContentChangeEvent(new Range(new Position(1, 0), new Position(2, 0)), '')) == 'a\nc'
    }
}
