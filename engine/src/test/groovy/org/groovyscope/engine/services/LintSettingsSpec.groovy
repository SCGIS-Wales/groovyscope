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

import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.lint.CodeNarcAnalyzer
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

/** {@code lint.rules} overrides and the {@code lint.publishMaxPriority} threshold. */
class LintSettingsSpec extends Specification {

    static final String MESSY = 'src/main/groovy/sample/Messy.groovy'

    private static EngineConfig config(String lintJson) {
        EngineConfig.fromJson('{"workspace": {"sourceRoots": ["src/main/groovy", "vars"]}, "lint": ' + lintJson + '}')
    }

    private static List<Diagnostic> published(EngineConfig config) {
        Fixtures.services('lint-sample', config).quality().publishedDiagnostics()[Fixtures.uri('lint-sample', MESSY)]
    }

    private static List<Diagnostic> checked(EngineConfig config) {
        String uri = Fixtures.uri('lint-sample', MESSY)
        Fixtures.services('lint-sample', config).quality().check([uri], null, ['lint'] as Set).diagnostics()[uri]
    }

    def 'lint.rules and lint.publishMaxPriority are read from the configuration'() {
        when:
        def lint = config('{"rules": {"UnnecessarySemicolon": "off", "NoDef": {"priority": 3}, "Bad": {"priority": 7}, "Odd": 5}, "publishMaxPriority": 3}').lint()

        then:
        lint.rules() == [UnnecessarySemicolon: EngineConfig.Lint.RULE_OFF, NoDef: 3]
        lint.publishMaxPriority() == 3
        EngineConfig.DEFAULTS.lint().rules().isEmpty()
        EngineConfig.DEFAULTS.lint().publishMaxPriority() == 2
        config('{"publishMaxPriority": 9}').lint().publishMaxPriority() == 3
        config('{"publishMaxPriority": 0}').lint().publishMaxPriority() == 1
        config('{"rules": "off"}').lint().rules().isEmpty()
    }

    def 'published diagnostics hold lint findings up to publishMaxPriority; check is not filtered'() {
        when:
        def strict = published(config('{"publishMaxPriority": 1}'))
        def standard = published(config('{}'))
        def everything = published(config('{"publishMaxPriority": 3}'))
        def lint = checked(config('{"publishMaxPriority": 1}'))

        then:
        lint.any { it.code() == 'UnnecessarySemicolon' && it.severity() == Diagnostic.Severity.INFORMATION }
        !standard.any { it.code() == 'UnnecessarySemicolon' }
        standard.any { it.source() == CodeNarcAnalyzer.SOURCE && it.severity() == Diagnostic.Severity.WARNING }
        everything.any { it.code() == 'UnnecessarySemicolon' }
        strict.every { it.source() != CodeNarcAnalyzer.SOURCE || it.severity() == Diagnostic.Severity.ERROR }
        standard.findAll { it.source() != CodeNarcAnalyzer.SOURCE } == strict.findAll { it.source() != CodeNarcAnalyzer.SOURCE }
    }

    def 'a rule switched off disappears and a priority override changes the severity'() {
        when:
        def lint = checked(config('{"rules": {"UnnecessarySemicolon": "off", "NoDef": {"priority": 1}, "NoSuchRule": "off"}}'))
        def plain = checked(config('{}'))

        then:
        plain.any { it.code() == 'UnnecessarySemicolon' }
        plain.find { it.code() == 'NoDef' }.severity() == Diagnostic.Severity.WARNING
        !lint.any { it.code() == 'UnnecessarySemicolon' }
        lint.findAll { it.code() == 'NoDef' }*.severity() as Set == [Diagnostic.Severity.ERROR] as Set
    }

    def 'overrides apply to a fresh copy, so the shared rule set keeps its priorities'() {
        given:
        def analyzer = new CodeNarcAnalyzer()

        when:
        def base = analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_DEFAULT, null, CodeNarcAnalyzer.BUNDLED_DEFAULT)
        def overridden = analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_DEFAULT, null, CodeNarcAnalyzer.BUNDLED_DEFAULT, [NoDef: 3])
        def same = analyzer.ruleSet(CodeNarcAnalyzer.BUNDLED_DEFAULT, null, CodeNarcAnalyzer.BUNDLED_DEFAULT, [:])

        then:
        base.rules.find { it.name == 'NoDef' }.priority == 2
        overridden.rules.find { it.name == 'NoDef' }.priority == 3
        same.is(base)
        overridden.rules.size() == base.rules.size()
    }

    def 'lint priorities follow severities'() {
        expect:
        CodeNarcAnalyzer.priorityOf(finding(Diagnostic.Severity.ERROR)) == 1
        CodeNarcAnalyzer.priorityOf(finding(Diagnostic.Severity.WARNING)) == 2
        CodeNarcAnalyzer.priorityOf(finding(Diagnostic.Severity.INFORMATION)) == 3
        CodeNarcAnalyzer.priorityOf(finding(Diagnostic.Severity.HINT)) == 3
    }

    private static Diagnostic finding(Diagnostic.Severity severity) {
        new Diagnostic('file:///x.groovy', Range.of(0, 0, 0, 1), severity, CodeNarcAnalyzer.SOURCE, 'Rule', 'message')
    }
}
