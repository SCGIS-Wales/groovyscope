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
package org.groovyscope.engine.config

import spock.lang.Specification

class EngineConfigSpec extends Specification {

    def 'blank input yields the defaults'() {
        expect:
        EngineConfig.fromJson(json) == EngineConfig.DEFAULTS

        where:
        json << [null, '', '   ', '{}', '{"daemon": {"heap": "2g"}}']
    }

    def 'workspace keys override the defaults and unknown keys are ignored'() {
        when:
        def config = EngineConfig.fromJson('''
            {"workspace": {"sourceRoots": ["src", "vars"], "classpath": ["lib/a.jar"], "trust": true,
                           "exclude": ["**/out/**"], "mystery": 1},
             "root": "/tmp/x", "lint": {"ruleset": null}}
        ''')

        then:
        config.sourceRoots() == ['src', 'vars']
        config.classpath() == ['lib/a.jar']
        config.exclude() == ['**/out/**']
        config.trust()
    }

    def 'the comment thresholds are read from the comments section, each missing key keeping its default'() {
        when:
        def tuned = EngineConfig.fromJson('''
            {"comments": {"restateShare": 0.8, "maxBlockLines": 4, "maxJavadocLines": 7, "todoNeedsOwner": false,
                          "maxDensity": 0.5, "maxAddedShare": 0.3, "exemptPublicJavadoc": false}}
        ''').practices().comments()
        def partial = EngineConfig.fromJson('{"comments": {"maxDensity": 1, "maxBlockLines": "x"}}').practices().comments()

        then:
        tuned == new EngineConfig.Comments(0.8, 4, 7, false, 0.5, 0.3, false)
        partial == new EngineConfig.Comments(0.7, 2, 5, true, 1.0, 0.2, true)
        EngineConfig.DEFAULTS.practices().comments() == EngineConfig.Comments.DEFAULTS
        new EngineConfig.Practices(true, [:], null, null, null).comments() == EngineConfig.Comments.DEFAULTS
    }

    def 'the test bloat settings are read from the tests section, each missing or invalid key keeping its default'() {
        when:
        def tuned = EngineConfig.fromJson('''
            {"tests": {"maxTestToCodeRatio": 2.5, "maxMethodsPerSubject": 12, "namePattern": "^should.*",
                       "vagueNames": ["test", "check"]}}
        ''').practices().tests()
        def partial = EngineConfig.fromJson('{"tests": {"maxTestToCodeRatio": 4, "namePattern": ""}}').practices().tests()
        def defaults = EngineConfig.Tests.DEFAULTS

        then:
        tuned == new EngineConfig.Tests(2.5, 12, '^should.*', ['test', 'check'])
        partial == new EngineConfig.Tests(4.0, 40, defaults.namePattern(), defaults.vagueNames())
        EngineConfig.DEFAULTS.practices().tests() == defaults
        new EngineConfig.Practices(true, [:], null, null, null, null, null, null).tests() == defaults
        'should work' ==~ defaults.namePattern()
        'returnsEmptyListForBlankQuery' ==~ defaults.namePattern()
        'test_blank_query_gives_nothing' ==~ defaults.namePattern()
        !('shouldWork' ==~ defaults.namePattern())
        'chargesTax' ==~ defaults.namePattern()
        !('testTotal' ==~ defaults.namePattern())
        !('test1' ==~ defaults.namePattern())
    }

    def 'the value type settings are read from the types section, each missing key keeping its default'() {
        expect:
        EngineConfig.fromJson('{"types": {"preview": false, "previewMaxPerFile": 3, "declareOnEdit": false}}')
                .practices().types() == new EngineConfig.Types(false, 3, false)
        EngineConfig.fromJson('{"types": {"previewMaxPerFile": "x"}}').practices().types() == EngineConfig.Types.DEFAULTS
        EngineConfig.DEFAULTS.practices().types() == new EngineConfig.Types(true, 10, true)
        new EngineConfig.Practices(true, [:], null, null, null, null, null, null).types() == EngineConfig.Types.DEFAULTS
    }

    def 'each lint profile takes its own rule set path'() {
        when:
        def lint = EngineConfig.fromJson('''
            {"lint": {"ruleset": "a.groovy", "jenkinsRuleset": "j.groovy", "gradleRuleset": "g.groovy"}}
        ''').lint()

        then:
        [lint.ruleset(), lint.jenkinsRuleset(), lint.gradleRuleset()] == ['a.groovy', 'j.groovy', 'g.groovy']
        EngineConfig.DEFAULTS.lint().gradleRuleset() == null
    }

    def 'typecheck.configScript defaults to the conventional path'() {
        expect:
        EngineConfig.DEFAULTS.typecheck().configScript() == 'config/groovyc/compile-static.groovy'
        EngineConfig.fromJson('{"typecheck": {"configScript": "build/cs.groovy"}}').typecheck().configScript() ==
                'build/cs.groovy'
    }

    def 'diagnostics delivery settings are parsed, normalised and defaulted'() {
        expect:
        EngineConfig.DEFAULTS.diagnostics() == new EngineConfig.Diagnostics('open', 300)
        EngineConfig.fromJson('{"diagnostics": {"push": "Workspace", "debounceMillis": 1000}}').diagnostics() ==
                new EngineConfig.Diagnostics('workspace', 1000)
        EngineConfig.fromJson('{"diagnostics": {"push": "none"}}').diagnostics().push() == 'none'
        EngineConfig.fromJson('{"diagnostics": {"push": "everything", "debounceMillis": -5}}').diagnostics() ==
                new EngineConfig.Diagnostics('open', 0)
        EngineConfig.fromJson('{"diagnostics": {"push": "none"}}').withConfigHash('h').diagnostics().push() == 'none'
    }

    def 'non-boolean trust and non-array lists fall back to defaults'() {
        when:
        def config = EngineConfig.fromJson('{"workspace": {"trust": "yes", "sourceRoots": "src"}}')

        then:
        !config.trust()
        config.sourceRoots() == EngineConfig.DEFAULTS.sourceRoots()
    }

    def 'malformed input is rejected with a clear message'() {
        when:
        EngineConfig.fromJson(json)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(expected)

        where:
        json        | expected
        '{nope'     | 'invalid engine configuration JSON'
        '[1, 2]'    | 'must be a JSON object'
    }

    def 'groovy file names are recognised'() {
        expect:
        EngineConfig.isGroovyFileName(name) == groovy

        where:
        name                  | groovy
        'Foo.groovy'          | true
        'build.gradle'        | true
        'x.gvy'               | true
        'Jenkinsfile'         | true
        'Jenkinsfile.prod'    | true
        'Foo.java'            | false
        'README.md'           | false
        'groovy'              | false
    }

    def 'the NullChecker mode is off, lenient or strict, nothing else (Q-17)'() {
        when:
        EngineConfig.fromJson('{"typecheck":{"nullChecker":"Strict "}}')

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('typecheck.nullChecker')

        expect:
        ['off', 'lenient', 'strict'].every { EngineConfig.fromJson("{\"typecheck\":{\"nullChecker\":\"${it}\"}}").typecheck().nullChecker() == it }
    }
}
