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
package org.groovyscope.engine.lint

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.index.TextLines
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

class CodeNarcAnalyzerSpec extends Specification {

    @Shared
    CodeNarcAnalyzer analyzer = new CodeNarcAnalyzer()

    @TempDir
    Path temp

    def 'the bundled profiles load and the default one flags the messy fixture'() {
        given:
        String uri = Fixtures.uri('lint-sample', 'src/main/groovy/sample/Messy.groovy')
        String text = Fixtures.text('lint-sample', 'src/main/groovy/sample/Messy.groovy')

        when:
        def findings = analyzer.analyze(uri, text, analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_DEFAULT))
        def codes = findings*.code() as Set

        then:
        codes.containsAll(['UnnecessarySemicolon', 'UnnecessaryGroovyImport', 'TrailingWhitespace', 'ConsecutiveBlankLines'])
        findings.every { it.source() == CodeNarcAnalyzer.SOURCE && it.uri() == uri && !it.message().isBlank() }
        findings.findAll { it.code() == 'UnnecessarySemicolon' }*.range()*.start()*.line().containsAll([2, 6])
        findings == findings.sort(false) { it.range().start() }

        and: 'the jenkins profile loads too'
        analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_JENKINS).rules.size() > 10
    }

    private List<Diagnostic> lintFixture(String relative, String profile) {
        analyzer.analyze(Fixtures.uri('lint-profiles', relative), Fixtures.text('lint-profiles', relative),
            analyzer.ruleSet(profile))
    }

    def 'a Gradle build script draws no def or type noise from the Gradle profile'() {
        when:
        def gradle = lintFixture('build.gradle', CodeNarcAnalyzer.BUNDLED_GRADLE)
        def strict = lintFixture('build.gradle', CodeNarcAnalyzer.BUNDLED_DEFAULT)

        then: 'the default profile would flag the fixture, so it is a fair test'
        strict*.code().contains('NoDef')

        and:
        gradle.findAll { CodeNarcAnalyzer.priorityOf(it) <= 2 }.empty
        !(gradle*.code() as Set).any { it == 'NoDef' || it == 'CompileStatic' || it.endsWith('TypeRequired') }
    }

    def 'the default profile runs the JUnit rules but leaves Spock specifications alone'() {
        when:
        def junit = lintFixture('SampleTest.groovy', CodeNarcAnalyzer.BUNDLED_DEFAULT)*.code()
        def spock = lintFixture('SampleSpec.groovy', CodeNarcAnalyzer.BUNDLED_DEFAULT)*.code()

        then:
        junit.contains('JUnitAssertAlwaysSucceeds')
        !spock.any { it.startsWith('JUnit') || it.startsWith('Spock') }
    }

    def 'ImplementationAsType is advice in the default profile'() {
        when:
        def found = analyzer.analyze('file:///w/A.groovy', 'class A {\n    ArrayList<String> names() {\n        []\n    }\n}\n',
            analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_DEFAULT)).findAll { it.code() == 'ImplementationAsType' }

        then:
        found.size() == 1
        CodeNarcAnalyzer.priorityOf(found[0]) == 3
    }

    def 'the Jenkins profile measures size'() {
        expect:
        lintFixture('vars/longStep.groovy', CodeNarcAnalyzer.BUNDLED_JENKINS)*.code().contains('MethodSize')
    }

    def 'a tidy file has no priority 1 or 2 findings'() {
        given:
        String uri = Fixtures.uri('lint-sample', 'src/main/groovy/sample/Tidy.groovy')

        when:
        def findings = analyzer.analyze(uri, Fixtures.text('lint-sample', 'src/main/groovy/sample/Tidy.groovy'),
            analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_DEFAULT))

        then:
        findings.every { it.severity() == Diagnostic.Severity.INFORMATION }
    }

    def 'rule sets come from files or classpath and are cached'() {
        given:
        Path custom = temp.resolve('custom.groovy')
        Files.writeString(custom, "ruleset { ruleset('rulesets/unnecessary.xml') }")

        expect:
        analyzer.ruleSet(custom.toString()).is(analyzer.ruleSet(custom.toString()))
        analyzer.ruleSet(custom.toString()).rules.size() > 5
        CodeNarcAnalyzer.resolve(null) == CodeNarcAnalyzer.BUNDLED_DEFAULT
        CodeNarcAnalyzer.resolve('file:/x/y.groovy') == 'file:/x/y.groovy'
        CodeNarcAnalyzer.resolve('org/groovyscope/engine/lint/jenkins.groovy') == 'org/groovyscope/engine/lint/jenkins.groovy'
        CodeNarcAnalyzer.resolve(custom.toString()).startsWith('file:')
    }

    def 'a missing rule set is an error the caller sees'() {
        when:
        analyzer.ruleSet('/definitely/missing/rules.groovy')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('cannot load CodeNarc rule set')
    }

    def 'priorities map to severities and line ranges skip indentation'() {
        expect:
        CodeNarcAnalyzer.severity(1) == Diagnostic.Severity.ERROR
        CodeNarcAnalyzer.severity(2) == Diagnostic.Severity.WARNING
        CodeNarcAnalyzer.severity(3) == Diagnostic.Severity.INFORMATION
        CodeNarcAnalyzer.lineRange(new TextLines('    x = 1\n'), 0).start().column() == 4
        CodeNarcAnalyzer.lineRange(new TextLines('\n'), 0).end().column() == 1
        CodeNarcAnalyzer.lineRange(new TextLines('a\n'), 5).start().line() == 5
    }

    def 'unparseable text yields a single warning instead of an exception'() {
        when:
        def findings = analyzer.analyze('file:///x.groovy', 'class {', analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_DEFAULT))

        then:
        findings.size() <= 2
        findings.every { it.severity() != null }
    }

    def 'a rule set naming rules this CodeNarc does not know still lints, and the names are reported once (Q-15)'() {
        given:
        Path file = temp.resolve('unknown-rules.groovy')
        Files.writeString(file, '''\
ruleset {
    NoSuchRuleAnywhere
    AnotherMissingRule(priority: 1)
    GoneRule {
        priority = 2
    }
    UnnecessarySemicolon
}
''')
        def analyzer = new CodeNarcAnalyzer()

        when:
        def found = analyzer.analyze('file:///w/A.groovy', 'int a = 1;\n', analyzer.ruleSet(file.toString()))

        then:
        found*.code() == ['UnnecessarySemicolon']
        analyzer.configProblems().size() == 1
        analyzer.configProblems()[0].contains('AnotherMissingRule, GoneRule, NoSuchRuleAnywhere')
    }

    def 'lint.rules can switch on a rule the profile leaves out, and a misspelt name is reported (Q-27)'() {
        given:
        def analyzer = new CodeNarcAnalyzer()
        Path file = temp.resolve('small.groovy')
        Files.writeString(file, 'ruleset {\n    UnnecessarySemicolon\n}\n')
        def overrides = [Println: org.groovyscope.engine.config.EngineConfig.Lint.RULE_ON, UnnecesarySemicolon: 3]

        when:
        def set = analyzer.ruleSet(file.toString(), null, CodeNarcAnalyzer.BUNDLED_DEFAULT, overrides)
        def found = analyzer.analyze('file:///w/A.groovy', 'class A { void a() { println "x"; } }\n', set)

        then:
        found*.code().toSet() == ['UnnecessarySemicolon', 'Println'] as Set
        analyzer.configProblems().any { it.startsWith('lint.rules') && it.contains('UnnecesarySemicolon') }
    }

    def 'lint.rules reads on, off and priorities'() {
        expect:
        org.groovyscope.engine.config.EngineConfig.Lint.parseRules(com.google.gson.JsonParser.parseString(
                '{"A": "on", "B": "off", "C": {"priority": 2}}')) == [A: -1, B: 0, C: 2]
    }
}
