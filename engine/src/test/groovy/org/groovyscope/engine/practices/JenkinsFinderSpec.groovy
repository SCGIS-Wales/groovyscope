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

import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** GS030 to GS033 on Jenkins pipeline code, and GS038 (mutable static state) everywhere. */
class JenkinsFinderSpec extends Specification {

    static final Set<String> CODES = ['GS030', 'GS031', 'GS032', 'GS033', 'GS038'] as Set
    static final Diagnostic.Severity ERROR = Diagnostic.Severity.ERROR
    static final Diagnostic.Severity WARNING = Diagnostic.Severity.WARNING
    static final Diagnostic.Severity INFORMATION = Diagnostic.Severity.INFORMATION

    @Shared
    LanguageServices jenkins = Fixtures.services('jenkins-checks')

    @Shared
    LanguageServices plain = Fixtures.services('static-state')

    def cleanupSpec() {
        jenkins?.close()
        plain?.close()
    }

    private static List<PracticeFinder.Practice> practices(LanguageServices services, String project, String path) {
        services.quality().practices(Fixtures.uri(project, path)).findAll { it.diagnostic().code() in CODES }
    }

    private static List<List> found(LanguageServices services, String project, String path) {
        practices(services, project, path).collect {
            [it.diagnostic().code(), it.diagnostic().range().start().line() + 1, it.diagnostic().severity()]
        }.sort { a, b -> a[1] <=> b[1] ?: a[0] <=> b[0] }
    }

    def 'pipeline steps: secrets interpolated into a shell, steps in @NonCPS, state live across a step'() {
        when:
        def findings = found(jenkins, 'jenkins-checks', 'vars/publishRelease.groovy')
        def messages = practices(jenkins, 'jenkins-checks', 'vars/publishRelease.groovy')*.diagnostic()
                .sort { it.range().start().line() }*.message()

        then: 'a bound credential is proven (error); a secret looking env name is only a guess (information)'
        findings == [
            ['GS030', 6, ERROR],
            ['GS030', 10, ERROR],
            ['GS030', 13, INFORMATION],
            ['GS032', 18, WARNING],
            ['GS032', 32, WARNING],
            ['GS031', 40, WARNING],
        ]
        messages[0].contains('DEPLOY_PASSWORD') && !messages[0].contains('DEPLOY_USER')
        messages[0].contains('single quotes')
        messages[3].contains('matcher') && messages[3].contains('sh')
        messages[5].contains('echo')
    }

    def 'a shared library: @CompileStatic and new File are reported, steps.readFile is not'() {
        expect:
        found(jenkins, 'jenkins-checks', 'src/org/acme/Checksums.groovy') == [['GS033', 5, WARNING]]
        found(jenkins, 'jenkins-checks', 'src/org/acme/Workspace.groovy') == [['GS033', 11, WARNING]]
        found(jenkins, 'jenkins-checks', 'Jenkinsfile').empty
        found(jenkins, 'jenkins-checks', 'src/com/cloudbees/groovy/cps/NonCPS.groovy').empty
    }

    def 'mutable static state is an error in Jenkins code'() {
        expect:
        found(jenkins, 'jenkins-checks', 'src/org/acme/Cache.groovy') == [['GS038', 4, ERROR], ['GS038', 5, ERROR]]
        practices(jenkins, 'jenkins-checks', 'src/org/acme/Cache.groovy')[0].diagnostic().message()
                .contains('every build')
    }

    def 'mutable static state elsewhere is a warning; constants, enums and test sources are left alone'() {
        expect:
        found(plain, 'static-state', 'src/main/groovy/sample/Registry.groovy') == [
            ['GS038', 6, WARNING],
            ['GS038', 7, WARNING],
            ['GS038', 8, WARNING],
            ['GS038', 9, INFORMATION],
        ]
        found(plain, 'static-state', 'src/test/groovy/sample/RegistryFixture.groovy').empty
    }

    def 'the Jenkins checks need Jenkins code, and every code can be switched off'() {
        given:
        def off = EngineConfig.fromJson('{"practices": {"severity": {"GS030": "off", "GS038": "off"}}}')
        def silenced = Fixtures.services('jenkins-checks', off)

        expect:
        !found(silenced, 'jenkins-checks', 'vars/publishRelease.groovy').any { it[0] == 'GS030' }
        found(silenced, 'jenkins-checks', 'src/org/acme/Cache.groovy').empty
        found(plain, 'static-state', 'src/main/groovy/sample/Registry.groovy').every { it[0] == 'GS038' }

        cleanup:
        silenced?.close()
    }
}
