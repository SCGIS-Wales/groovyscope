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

import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.IntConsumer
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.groovyscope.engine.config.EngineConfig
import spock.lang.Specification

class GroovyscopeLanguageServerSpec extends Specification {

    static final Path FALLBACK = Paths.get('/tmp/fallback')

    List<Integer> exitCodes = []
    GroovyscopeLanguageServer server = new GroovyscopeLanguageServer(
        { int code -> exitCodes << code } as IntConsumer, FALLBACK, EngineConfig.DEFAULTS)

    def 'initialize reports the server name, version and navigation capabilities'() {
        when:
        def result = server.initialize(new InitializeParams()).get()

        then:
        result.serverInfo.name == GroovyscopeLanguageServer.SERVER_NAME
        result.serverInfo.version ==~ /\d+\.\d+\.\d+.*/
        result.capabilities.definitionProvider.getLeft()
        result.capabilities.referencesProvider.getLeft()
        result.capabilities.hoverProvider.getLeft()
        result.capabilities.documentSymbolProvider.getLeft()
        result.capabilities.workspaceSymbolProvider.getLeft()
        result.capabilities.renameProvider.getRight().prepareProvider
        result.capabilities.completionProvider.triggerCharacters == ['.']
        result.capabilities.signatureHelpProvider.triggerCharacters == ['(', ',']
        server.services() != null
        server.workspaceRoot() == FALLBACK
    }

    def 'exit after shutdown yields exit code 0'() {
        when:
        server.shutdown().get()
        server.exit()

        then:
        server.shutdownRequested()
        exitCodes == [0]
    }

    def 'exit without shutdown yields exit code 1'() {
        when:
        server.exit()

        then:
        !server.shutdownRequested()
        exitCodes == [1]
    }

    def 'connect stores the client'() {
        given:
        def client = new RecordingLanguageClient()

        when:
        server.connect(client)

        then:
        server.client().is(client)
    }

    def 'workspace root prefers the first workspace folder'() {
        given:
        def params = new InitializeParams(
            workspaceFolders: [new WorkspaceFolder(uri: 'file:///work/one'), new WorkspaceFolder(uri: 'file:///work/two')],
            rootUri: 'file:///work/root')

        when:
        server.initialize(params).get()

        then:
        server.workspaceRoot() == Paths.get('/work/one')
    }

    def 'workspace root falls back to rootUri, rootPath, then cwd'() {
        expect:
        GroovyscopeLanguageServer.resolveRoot(params, FALLBACK) == expected

        where:
        params                                                              | expected
        new InitializeParams(rootUri: 'file:///work/root')                  | Paths.get('/work/root')
        new InitializeParams(rootPath: '/work/path')                        | Paths.get('/work/path')
        new InitializeParams(rootUri: 'untitled:///nope', rootPath: '  ')   | FALLBACK
        new InitializeParams(workspaceFolders: [new WorkspaceFolder(uri: 'http://example.com/x')]) | FALLBACK
        new InitializeParams()                                              | FALLBACK
        null                                                                | FALLBACK
    }

    def 'toPath ignores blank, non-file and malformed URIs'() {
        expect:
        GroovyscopeLanguageServer.toPath(uri) == null

        where:
        uri << [null, '', '   ', 'http://host/x', 'file:::bad uri with spaces', ':nope']
    }

    def 'requests before initialize are rejected as not initialized'() {
        when:
        server.textDocumentService.hover(new org.eclipse.lsp4j.HoverParams(
            new org.eclipse.lsp4j.TextDocumentIdentifier('file:///x.groovy'), new org.eclipse.lsp4j.Position(0, 0))).get()

        then:
        def e = thrown(java.util.concurrent.ExecutionException)
        e.cause instanceof org.eclipse.lsp4j.jsonrpc.ResponseErrorException
    }
}
