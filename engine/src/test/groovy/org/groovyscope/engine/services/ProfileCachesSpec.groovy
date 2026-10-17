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
package org.groovyscope.engine.services

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.lint.CodeNarcAnalyzer
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.profiles.ProfilesSpec
import org.groovyscope.engine.profiles.RemoteCompiler
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** Seam and emulation caches are keyed correctly and bounded; rule sets resolve against the workspace. */
@Timeout(180)
class ProfileCachesSpec extends Specification {

    static final String DEMO = 'plugins/demo/src/main/groovy/demo/DemoPlugin.groovy'

    @TempDir Path temp

    private static LanguageServices services(Path root, String... profiles) {
        EngineConfig config = EngineConfig.fromJson('{"profiles": [' + profiles.join(',') + ']}')
        new LanguageServices(new WorkspaceModel(root, config), new GroovyCompiler())
    }

    private static String groovyJar() {
        Path.of(GroovySystem.protectionDomain.codeSource.location.toURI()).toString()
    }

    def 'test seams are cached per profile, not once for whichever profile asked first'() {
        given:
        def services = services(Fixtures.project('profile-sample'), ProfilesSpec.profileJson(),
                ProfilesSpec.profileJson(name: 'other', contract: [testSources: '**/nothing/**']))
        def profiles = services.workspace().config().profiles()

        when:
        def first = services.quality().seams(profiles[0])

        then:
        !first.empty
        services.quality().seams(profiles[1]).empty
        services.quality().seams(profiles[0]) == first
    }

    def 'a relative emulation jar resolves against the workspace root and its result is cached'() {
        given:
        Path root = Fixtures.project('profile-sample')
        String relative = root.relativize(Path.of(groovyJar())).toString()
        def services = services(root, ProfilesSpec.profileJson(
                target: [groovy: '5.1', groovy6Checkers: 'advisory', compiledBy: [], emulationJars: [relative]]))
        String demo = Fixtures.uri('profile-sample', DEMO)

        when:
        def found = services.quality().check([demo], null, ['practices'] as Set).diagnostics()[demo]
                .findAll { it.code() == 'RP050' }

        then:
        !relative.startsWith('/')
        !found.empty
        services.quality().emulationCacheSize() == 1
    }

    def 'an emulation compile that could not run is reported and retried, never cached'() {
        given:
        Path junk = Files.writeString(temp.resolve('junk.jar'), 'not a jar')
        def services = services(Fixtures.project('profile-sample'), ProfilesSpec.profileJson(
                target: [groovy: '5.1', compiledBy: [], emulationJars: [junk.toString()]]))
        String demo = Fixtures.uri('profile-sample', DEMO)

        when:
        def found = services.quality().check([demo], null, ['practices'] as Set).diagnostics().values().flatten()
                .findAll { it.code() == 'RP050' }

        then:
        found.any { it.message().startsWith(RemoteCompiler.COULD_NOT_RUN) }
        services.quality().emulationCacheSize() == 0
    }

    def 'the emulation cache keeps only the most recently used results'() {
        given:
        Map<String, Integer> cache = QualityServices.lru(3)

        when:
        (1..5).each { cache.put("k$it".toString(), it) }
        cache.get('k3')
        cache.put('k6', 6)

        then:
        cache.keySet() as List == ['k5', 'k3', 'k6']
        QualityServices.EMULATION_CACHE_LIMIT == 8
        QualityServices.couldNotRun(['u': [new Diagnostic('u', Range.of(0, 0, 0, 1), Diagnostic.Severity.WARNING,
                'profile', 'RP050', RemoteCompiler.COULD_NOT_RUN + 'boom')]])
        !QualityServices.couldNotRun(['u': []])
    }

    def 'rule sets: relative paths resolve against the workspace root and remote locations are refused'() {
        given:
        Path root = Files.createDirectories(temp.resolve('ws'))
        Files.createDirectories(root.resolve('config'))
        Files.writeString(root.resolve('config/rules.groovy'), "ruleset { ruleset('rulesets/basic.xml') }\n")
        def analyzer = new CodeNarcAnalyzer()

        expect:
        CodeNarcAnalyzer.resolve('config/rules.groovy', root, CodeNarcAnalyzer.BUNDLED_DEFAULT) ==
                root.resolve('config/rules.groovy').toUri().toString()
        CodeNarcAnalyzer.resolve('https://example.invalid/rules.groovy', root, CodeNarcAnalyzer.BUNDLED_DEFAULT) ==
                CodeNarcAnalyzer.BUNDLED_DEFAULT
        CodeNarcAnalyzer.resolve('HTTP://example.invalid/rules.groovy', root, CodeNarcAnalyzer.BUNDLED_JENKINS) ==
                CodeNarcAnalyzer.BUNDLED_JENKINS
        CodeNarcAnalyzer.resolve('', root, CodeNarcAnalyzer.BUNDLED_JENKINS) == CodeNarcAnalyzer.BUNDLED_JENKINS
        CodeNarcAnalyzer.resolve('org/groovyscope/engine/lint/jenkins.groovy', root, CodeNarcAnalyzer.BUNDLED_DEFAULT) ==
                'org/groovyscope/engine/lint/jenkins.groovy'
        analyzer.ruleSet('config/rules.groovy', root, CodeNarcAnalyzer.BUNDLED_DEFAULT).rules.size() > 5
        analyzer.ruleSet('https://example.invalid/rules.groovy', root, CodeNarcAnalyzer.BUNDLED_JENKINS)
                .is(analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_JENKINS, root, CodeNarcAnalyzer.BUNDLED_JENKINS))
    }

    def 'a workspace lint.ruleset relative to the root is used by check'() {
        given:
        Path root = Files.createDirectories(temp.resolve('lint-ws'))
        Files.createDirectories(root.resolve('src/main/groovy'))
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A {\n    void run() { println "x" ; }\n}\n')
        Files.writeString(root.resolve('rules.groovy'),
                "ruleset { ruleset('rulesets/unnecessary.xml') { include 'UnnecessarySemicolon' } }\n")
        def config = EngineConfig.fromJson('{"lint": {"ruleset": "rules.groovy"}}')
        def services = new LanguageServices(new WorkspaceModel(root, config), new GroovyCompiler())
        String uri = WorkspaceModel.toUri(root.resolve('src/main/groovy/A.groovy'))

        expect:
        services.quality().check([uri], null, ['lint'] as Set).diagnostics()[uri]*.code() == ['UnnecessarySemicolon']
    }
}
