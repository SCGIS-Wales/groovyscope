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
package org.groovyscope.engine.practices

import java.nio.file.Path
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.search.SearchFixtures
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** GS050 to GS052 on Gradle build scripts, which reach the practices whether or not they are compiled. */
class GradleFinderSpec extends Specification {

    static final List<String> CODES = ['GS050', 'GS051', 'GS052']
    static final String BUILD = Fixtures.uri('gradle-checks', 'build.gradle')
    static final String CLEAN = Fixtures.uri('gradle-checks', 'clean/build.gradle')
    static final String SETTINGS = Fixtures.uri('gradle-checks', 'settings.gradle')

    @Shared
    LanguageServices services = Fixtures.services('gradle-checks')

    @TempDir
    Path temp

    private static List<List> found(List<Diagnostic> diagnostics) {
        diagnostics.findAll { it.code() in CODES }.collect { [it.code(), it.range().start().line() + 1] }
    }

    def 'build scripts outside the source roots are discovered apart from the compiled sources'() {
        given:
        def model = new WorkspaceModel(Fixtures.project('gradle-checks'), EngineConfig.DEFAULTS)
        def walked = new WorkspaceModel(Fixtures.project('gradle-multi'), EngineConfig.DEFAULTS)

        expect:
        model.buildScripts().keySet() == [BUILD, CLEAN, SETTINGS] as Set
        model.buildScripts()[BUILD].contains('mavenLocal()')
        !model.sources().keySet().any { it.endsWith('.gradle') }
        walked.buildScripts().isEmpty() // the whole tree is walked there, build scripts included
    }

    def 'eager tasks, dynamic versions and insecure repositories are found; the clean scripts give nothing'() {
        when:
        def checked = services.quality().check([], null, ['practices'] as Set)

        then:
        found(checked.diagnostics()[BUILD]) == [['GS052', 7], ['GS052', 8], ['GS051', 12], ['GS051', 13],
                                                ['GS051', 14], ['GS050', 18], ['GS050', 24]]
        found(checked.diagnostics()[CLEAN]).empty
        found(checked.diagnostics()[SETTINGS]).empty
        checked.diagnostics()[BUILD].find { it.code() == 'GS052' && it.message().contains('mavenLocal') }
            .severity() == Diagnostic.Severity.WARNING
        checked.diagnostics()[BUILD].find { it.code() == 'GS052' && it.message().contains('http://') }
            .severity() == Diagnostic.Severity.ERROR
        checked.diagnostics()[BUILD].find { it.code() == 'GS051' }.message().contains('5.0.+')
        checked.diagnostics()[BUILD].find { it.code() == 'GS050' }.message().contains('tasks.register')
    }

    def 'a check of one build script, its lint with the Gradle profile, and its practices on request'() {
        when:
        def checked = services.quality().check([BUILD], null, ['practices', 'lint'] as Set)

        then:
        found(checked.diagnostics()[BUILD]).size() == 7
        checked.diagnostics()[BUILD].any { it.source() == 'codenarc' && it.code() == 'UnnecessarySemicolon' }
        !checked.diagnostics()[BUILD].any { it.code() in ['NoDef', 'CompileStatic', 'VariableTypeRequired'] }
        found(services.quality().practices(BUILD)*.diagnostic()).size() == 7
        services.quality().check([CLEAN], null, ['compile'] as Set).diagnostics()[CLEAN].empty
    }

    def 'a build script compiled with the workspace gets the same checks'() {
        given:
        String text = "task hello {\n}\nrepositories {\n    mavenLocal()\n}\n"
        def snapshot = SearchFixtures.compile(temp, ['build.gradle': text])
        String uri = SearchFixtures.uri(temp, 'build.gradle')

        when:
        def practices = PracticeFinder.find(uri, snapshot.result().modules().get(uri), text, EngineConfig.DEFAULTS)

        then:
        found(practices*.diagnostic()) == [['GS050', 1], ['GS052', 4]]
    }

    def 'only build scripts are read as Gradle'() {
        given:
        String text = "task hello {\n}\n"
        def snapshot = SearchFixtures.compile(temp, ['src/main/groovy/Tasks.groovy': text])
        String uri = SearchFixtures.uri(temp, 'src/main/groovy/Tasks.groovy')

        expect:
        found(PracticeFinder.find(uri, snapshot.result().modules().get(uri), text, EngineConfig.DEFAULTS)*.diagnostic())
            .empty
    }

    def 'the catalogue explains each code'() {
        expect:
        CODES.every { Explanations.explain(it).isPresent() }
    }
}
