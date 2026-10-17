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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** groovyscope/* requests only ever read files inside the workspace root. */
@Timeout(120)
class ContainmentSpec extends Specification {

    @TempDir Path base
    Path root
    Path inside
    Path outside
    Path link
    GroovyscopeLanguageServer server

    def setup() {
        root = Files.createDirectories(base.resolve('ws/src/main/groovy/app')).parent.parent.parent.parent
        inside = Files.writeString(root.resolve('src/main/groovy/app/Inside.groovy'),
                'package app\nclass Inside {\n    String name() { \'in\' }\n}\n')
        outside = Files.writeString(Files.createDirectories(base.resolve('elsewhere')).resolve('Secret.groovy'),
                'class Secret {\n    String token() {  \'s\' ;  }\n}\n')
        link = Files.createSymbolicLink(root.resolve('src/main/groovy/app/Linked.groovy'), outside)
        server = new GroovyscopeLanguageServer({ }, root, EngineConfig.DEFAULTS)
    }

    private static String uri(Path path) {
        WorkspaceModel.toUri(path)
    }

    private Map<String, Object> params(Map<String, Object> extra) {
        [root: root.toString()] + extra
    }

    def 'URIs outside the root, directly or through a link, are dropped from targets'() {
        when:

        def services = server.check(params(uris: [uri(inside)])).get(60, TimeUnit.SECONDS)
        def current = server.services()

        then:
        services.summary.files == 1
        GroovyscopeLanguageServer.targets(current, [uris: [uri(inside), uri(outside), uri(link), 'untitled:x']]) == [uri(inside)]
        !current.workspace().sources().containsKey(uri(link))
        current.workspace().sources().containsKey(uri(inside))
    }

    def 'requests naming only outside documents answer with nothing instead of the whole workspace'() {
        given:
        def outsideOnly = [uris: [uri(outside), uri(link)]]

        when:
        def check = server.check(params(outsideOnly)).get(60, TimeUnit.SECONDS)
        def format = server.format(params(outsideOnly)).get(60, TimeUnit.SECONDS)
        def fix = server.fix(params(outsideOnly)).get(60, TimeUnit.SECONDS)
        def dynamic = server.dynamicCode(params(outsideOnly)).get(60, TimeUnit.SECONDS)

        then:
        check.diagnostics == [:]
        check.summary.files == 0
        format.edits == [:]
        format.checked == 0
        fix.edits == [:]
        dynamic.findings == []
    }

    def 'requests about one outside document report nothing about it'() {
        given:
        def at = { Path path -> params(uri: uri(path), line: 1, character: 11) }

        when:
        def profile = server.profile(at(outside)).get(60, TimeUnit.SECONDS)
        def definition = server.definition(at(outside)).get(60, TimeUnit.SECONDS)
        def references = server.references(at(link)).get(60, TimeUnit.SECONDS)
        def impact = server.impact(at(outside)).get(60, TimeUnit.SECONDS)
        def graph = server.callGraph(at(outside)).get(60, TimeUnit.SECONDS)
        def insideDefinition = server.definition(params(uri: uri(inside), line: 2, character: 12)).get(60, TimeUnit.SECONDS)

        then:
        profile == [governed: false]
        definition == null
        references.references == []
        impact == []
        graph.roots == [] && graph.nodes == [] && graph.edges == []
        insideDefinition != null
    }

    def 'the text of a file outside the root is never read, unless an editor opened it'() {
        given:
        def workspace = new WorkspaceModel(root, EngineConfig.DEFAULTS)

        expect:
        workspace.text(uri(inside)) != null
        workspace.text(uri(outside)) == null
        workspace.text(uri(link)) == null
        workspace.contains(uri(inside))
        !workspace.contains(uri(outside))
        !workspace.contains('untitled:x')
        workspace.contains(root.resolve('src/New.groovy'))

        when:
        workspace.open(uri(outside), 'class Open {}')

        then:
        workspace.text(uri(outside)) == 'class Open {}'
    }

    def 'a root that is not an existing directory is refused'() {
        when:
        server.check([root: base.resolve('missing').toString()]).get(10, TimeUnit.SECONDS)

        then:
        def failure = thrown(ExecutionException)
        failure.cause instanceof ResponseErrorException
        (failure.cause as ResponseErrorException).responseError.code == ResponseErrorCode.InvalidParams.value

        when:
        server.check([root: inside.toString()]).get(10, TimeUnit.SECONDS)

        then:
        def file = thrown(ExecutionException)
        (file.cause as ResponseErrorException).responseError.message.contains('not an existing directory')
        server.services() == null
    }

    def 'status reports the configuration hash'() {
        given:
        def hashed = new GroovyscopeLanguageServer({ }, root, EngineConfig.DEFAULTS.withConfigHash('abc'))

        expect:
        server.status().get(10, TimeUnit.SECONDS).configHash == ''
        hashed.status().get(10, TimeUnit.SECONDS).configHash == 'abc'
    }
}
