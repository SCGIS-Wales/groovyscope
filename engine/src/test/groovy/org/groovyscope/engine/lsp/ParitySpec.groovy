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

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.log.EngineLog
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.TempDir

/** The MCP-facing requests that mirror LSP features, and the engine log. */
class ParitySpec extends Specification {

    static final String GREETER = 'src/main/groovy/app/Greeter.groovy'

    @TempDir Path temp

    GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ }, Fixtures.project('basic-app'), EngineConfig.DEFAULTS)

    Map params(String needle, int occurrence = 1, int offset = 1) {
        def at = Fixtures.pos('basic-app', GREETER, needle, occurrence, offset)
        [root: Fixtures.project('basic-app').toString(), uri: Fixtures.uri('basic-app', GREETER),
         line: at.line(), character: at.column()]
    }

    def 'hover, type definition and document symbols answer like their LSP counterparts'() {
        when:
        def hover = server.hover(params('greet(String')).get(60, TimeUnit.SECONDS)
        def type = server.typeDefinition(params('greeting =')).get(60, TimeUnit.SECONDS)
        def outline = server.documentSymbols([root: params('greet').root, uri: params('greet').uri]).get(60, TimeUnit.SECONDS)
        def outsideHover = server.hover([root: params('greet').root, uri: temp.toUri().toString() + 'X.groovy', line: 0,
                                         character: 0]).get(60, TimeUnit.SECONDS)

        then:
        hover.markdown.contains('greet')
        type == null || type.symbol.name == 'String'
        outline.symbols*.name == ['Greeter']
        outline.symbols[0].children*.name.containsAll(['greet', 'getCount'])
        outsideHover == null
    }

    def 'rename returns edits for every reference, and refuses bad names and outside documents'() {
        when:
        def renamed = server.rename(params('String name', 1, 8) + [newName: 'person']).get(60, TimeUnit.SECONDS)
        def dynamic = server.rename(params('greet(String') + [newName: 'salute']).get(60, TimeUnit.SECONDS)
        def invalid = server.rename(params('greet(String') + [newName: '1nope']).get(60, TimeUnit.SECONDS)
        def outside = server.rename([root: params('greet').root, uri: temp.toUri().toString() + 'X.groovy', line: 0,
                                     character: 0, newName: 'x']).get(60, TimeUnit.SECONDS)

        then:
        renamed.edits.keySet().any { it.endsWith('Greeter.groovy') }
        renamed.edits.values().flatten().every { it.newText == 'person' }
        renamed.edits.values().flatten().size() == 2
        dynamic.refused.contains('matched by name only')
        invalid.refused.contains('not a valid identifier')
        outside.refused.contains('outside the workspace')
    }

    def 'requests without their required parameters are rejected'() {
        when:
        server."$method"([root: params('greet').root]).get(60, TimeUnit.SECONDS)

        then:
        def failure = thrown(Exception)
        failure.message.contains('needs')

        where:
        method << ['hover', 'typeDefinition', 'documentSymbols', 'rename']
    }

    def 'the engine log honours its level and writes to a file'() {
        given:
        def captured = new ByteArrayOutputStream()
        EngineLog.redirect(new PrintStream(captured, true, 'UTF-8'))
        Path file = temp.resolve('logs/engine.log')

        when:
        EngineLog.configure('info', file.toString())
        EngineLog.debug('hidden %s', 1)
        EngineLog.info('shown %s', 2)
        EngineLog.error(new IllegalStateException('boom'), 'failed')
        EngineLog.configure('nonsense', null)
        EngineLog.info('not shown')

        then:
        def text = captured.toString('UTF-8')
        !text.contains('hidden') && text.contains('INFO') && text.contains('shown 2')
        text.contains('IllegalStateException: boom')
        Files.readString(file).contains('shown 2')
        !text.contains('not shown')
        EngineLog.parse('DEBUG') == EngineLog.Level.DEBUG
        EngineLog.parse(null) == EngineLog.Level.WARNING

        cleanup:
        EngineLog.redirect(System.err)
        EngineLog.configure(null, null)
    }

    def 'JSON-RPC traffic is logged with method and timing, errors as warnings'() {
        given:
        def captured = new ByteArrayOutputStream()
        EngineLog.redirect(new PrintStream(captured, true, 'UTF-8'))
        EngineLog.configure('debug', null)
        def seen = []
        def consumer = MessageLogging.wrapper().apply({ message -> seen << message } as org.eclipse.lsp4j.jsonrpc.MessageConsumer)

        when:
        consumer.consume(new RequestMessage(jsonrpc: '2.0', id: '7', method: 'groovyscope/check'))
        consumer.consume(new ResponseMessage(jsonrpc: '2.0', id: '7', result: [:]))
        consumer.consume(new RequestMessage(jsonrpc: '2.0', id: '8', method: 'groovyscope/fix'))
        consumer.consume(new ResponseMessage(jsonrpc: '2.0', id: '8', error: new ResponseError(1, 'bad', null)))
        consumer.consume(new NotificationMessage(jsonrpc: '2.0', method: 'textDocument/didOpen'))

        then:
        seen.size() == 5
        def text = captured.toString('UTF-8')
        text.contains('<- request 7 groovyscope/check')
        text =~ /-> answer 7 groovyscope\/check in \d+ ms/
        text.contains('WARNING') && text.contains('-> error 8 groovyscope/fix')
        text.contains('notification textDocument/didOpen')

        cleanup:
        EngineLog.redirect(System.err)
        EngineLog.configure(null, null)
    }
}
