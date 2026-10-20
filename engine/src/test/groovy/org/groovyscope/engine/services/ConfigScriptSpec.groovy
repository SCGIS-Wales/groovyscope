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
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer
import spock.lang.Specification

/** typecheck.configScript: a project that applies @CompileStatic through groovyc's configuration script. */
class ConfigScriptSpec extends Specification {

    static final String PLAIN = Fixtures.uri('configscript-sample', 'src/main/groovy/sample/Plain.groovy')

    private static List<Diagnostic> all(def result) {
        result.diagnostics().values().flatten() as List<Diagnostic>
    }

    def 'a project with a configuration script gets type checked once the workspace is trusted'() {
        given:
        def trusted = Fixtures.services('configscript-sample', EngineConfig.fromJson('{"workspace": {"trust": true}}'))

        when:
        def checked = trusted.quality().check([], null, ['typecheck'] as Set)

        then:
        checked.diagnostics()[PLAIN].any { it.source() == TypeCheckAnalyzer.SOURCE && it.message().contains('lenght') }
        !all(checked).any { it.code() == 'typecheck-coverage' }

        cleanup:
        trusted?.close()
    }

    def 'an untrusted workspace leaves the script unapplied and says how many classes stayed dynamic'() {
        given:
        def untrusted = Fixtures.services('configscript-sample')

        when:
        def checked = untrusted.quality().check([], null, ['typecheck'] as Set)
        def coverage = all(checked).findAll { it.code() == 'typecheck-coverage' }

        then:
        !all(checked).any { it.message().contains('lenght') }
        coverage.size() == 1
        coverage[0].severity() == Diagnostic.Severity.INFORMATION
        coverage[0].message().contains('2 classes are dynamic, not type checked (sample.Other, sample.Plain)')
        coverage[0].message().contains('compile-static.groovy is not applied until the workspace is trusted')

        when: 'a check of single files'
        def single = untrusted.quality().check([PLAIN], null, ['typecheck'] as Set)

        then: 'carries no workspace note'
        !all(single).any { it.code() == 'typecheck-coverage' }

        cleanup:
        untrusted?.close()
    }
}
