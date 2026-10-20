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
