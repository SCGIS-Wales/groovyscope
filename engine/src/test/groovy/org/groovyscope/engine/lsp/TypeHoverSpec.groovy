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

import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

/** Hover names every type a local is assigned, so the mixing is seen before an edit. */
@Timeout(120)
class TypeHoverSpec extends Specification {

    static final String PROJECT = 'edit-gate'
    static final String TYPES = 'src/main/groovy/demo/Types.groovy'

    @Shared GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ int code -> } as IntConsumer,
            Fixtures.project(PROJECT), EngineConfig.DEFAULTS)
    @Shared String root = Fixtures.project(PROJECT).toString()

    Map hover(String needle, int occurrence = 1) {
        def position = Fixtures.pos(PROJECT, TYPES, needle, occurrence)
        server.hover([root: root, uri: Fixtures.uri(PROJECT, TYPES), line: position.line(),
                      character: position.column()]).get(60, TimeUnit.SECONDS) as Map
    }

    def 'a local assigned more than once lists every type it holds, with lines, in the request and the markdown'() {
        when:
        Map found = hover('sum', 3)

        then:
        found.inferredTypes == [[type: 'Integer', line: 10], [type: 'String', line: 12]]
        found.markdown.contains('*assigned:* Integer (line 10), String (line 12)')
    }

    def 'a local assigned once, and a parameter, get no inferredTypes'() {
        expect:
        !hover('flag', 2).containsKey('inferredTypes')
        !hover('code', 2).containsKey('inferredTypes')
    }

    def 'the editor hover keeps the markdown'() {
        given:
        def position = Fixtures.pos(PROJECT, TYPES, 'sum', 3)
        server.initialize(new org.eclipse.lsp4j.InitializeParams(rootUri: Fixtures.project(PROJECT).toUri().toString()))
                .get(60, TimeUnit.SECONDS)

        when:
        def lsp = server.textDocumentService.hover(new HoverParams(new TextDocumentIdentifier(Fixtures.uri(PROJECT, TYPES)),
                new Position(position.line(), position.column()))).get(60, TimeUnit.SECONDS)

        then:
        lsp.contents.getRight().value.contains('*assigned:* Integer (line 10), String (line 12)')
    }
}
