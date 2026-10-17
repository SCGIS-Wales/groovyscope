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
package org.groovyscope.engine.compile

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

/** GS100: unresolved imports with no classpath configured fold into one warning per file. */
class MissingDependenciesSpec extends Specification {

    @TempDir
    Path dir

    static final String SOURCE = '''package app

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import static com.acme.util.Strings.trimAll
import java.util.List

class Service {
    Logger log = LoggerFactory.getLogger(Service)
    Undeclared other

    String run(List<String> items) {
        trimAll(items.join(','))
    }
}
'''

    private LanguageServices services(List<String> classpath, Map<String, String> severities = [:],
            Map<String, String> files = ['Service.groovy': SOURCE]) {
        Path src = Files.createDirectories(dir.resolve('src/main/groovy/app'))
        files.each { name, text -> Files.writeString(src.resolve(name), text) }
        def config = new EngineConfig(['src/main/groovy'], classpath, [], false, EngineConfig.Lint.DEFAULTS,
                EngineConfig.TypeCheck.DEFAULTS, EngineConfig.Format.DEFAULTS, true, EngineConfig.Groovy.DEFAULTS,
                new EngineConfig.Practices(true, severities))
        new LanguageServices(new WorkspaceModel(dir, config), new GroovyCompiler())
    }

    private String uri(String name) {
        dir.resolve("src/main/groovy/app/${name}").toUri().toString()
    }

    def 'with no classpath, unresolved imports and their uses become one GS100 warning'() {
        when:
        def diagnostics = services([]).diagnostics()[uri('Service.groovy')]

        then:
        diagnostics.size() == 1
        with(diagnostics[0]) {
            code() == 'GS100'
            source() == 'groovyscope'
            severity() == Diagnostic.Severity.WARNING
            range().start().line() == 2
            message() == 'missing dependencies (no classpath configured): org.slf4j, com.acme.util; run `groovyscope classpath`'
        }
    }

    def 'a file without unresolved imports keeps its errors'() {
        when:
        def diagnostics = services([], [:], ['Fine.groovy': 'package app\n\nclass Fine {\n    Missing m\n}\n'])
                .diagnostics()[uri('Fine.groovy')]

        then:
        diagnostics*.code() == ['syntax']
        diagnostics[0].message().startsWith('unable to resolve class Missing')
        diagnostics[0].severity() == Diagnostic.Severity.ERROR
    }

    def 'with a classpath, unresolved imports stay errors'() {
        given:
        Path lib = Files.createDirectories(dir.resolve('lib'))

        when:
        def diagnostics = services([lib.toString()]).diagnostics()[uri('Service.groovy')]

        then:
        diagnostics.size() > 1
        diagnostics.every { it.code() != 'GS100' && it.severity() == Diagnostic.Severity.ERROR }
        diagnostics.any { it.message().startsWith('unable to resolve class org.slf4j.Logger') }
    }

    def 'GS100 switched off drops the folded errors without a finding'() {
        expect:
        services([], [GS100: 'off']).diagnostics()[uri('Service.groovy')].isEmpty()
    }

    def 'the published diagnostics carry the folded finding'() {
        when:
        def published = services([]).quality().publishedDiagnostics()[uri('Service.groovy')]

        then:
        published.count { it.code() == 'GS100' } == 1
        published.every { !it.message().startsWith('unable to resolve class') }
    }

    def 'packages are cut at the first capitalised segment and the list is capped'() {
        expect:
        MissingDependencies.packageOf('org.slf4j.Logger') == 'org.slf4j'
        MissingDependencies.packageOf('com.acme.Outer.Inner') == 'com.acme'
        MissingDependencies.packageOf('com.acme.util.helper') == 'com.acme.util'
        MissingDependencies.packageOf('Bare') == 'Bare'
        MissingDependencies.message((1..10).collect { "p${it}".toString() } as LinkedHashSet)
                .contains('p1, p2, p3, p4, p5, p6, p7, p8, …;')
    }

    def 'other diagnostics and documents without text pass through unchanged'() {
        given:
        def unrelated = new Diagnostic('file:///a.groovy', Range.of(0, 0, 0, 1), Diagnostic.Severity.ERROR,
                GroovyCompiler.SOURCE, 'syntax', 'unexpected token')

        expect:
        MissingDependencies.fold('file:///a.groovy', [unrelated], null, Diagnostic.Severity.WARNING) == [unrelated]
        MissingDependencies.fold('file:///a.groovy', [], 'x', Diagnostic.Severity.WARNING) == []
        MissingDependencies.fold('file:///a.groovy', [unrelated], 'import a.B\n', Diagnostic.Severity.WARNING) == [unrelated]
    }
}
