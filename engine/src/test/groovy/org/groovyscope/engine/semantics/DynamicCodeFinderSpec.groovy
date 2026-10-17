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
package org.groovyscope.engine.semantics

import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

class DynamicCodeFinderSpec extends Specification {

    def 'the dynamic DSL fixture is full of findings and the typed library is clean'() {
        given:
        def dsl = Fixtures.services('dynamic-dsl')
        def typed = Fixtures.services('groovy51-lib')

        when:
        def findings = dsl.dynamicCode([])
        def kinds = findings*.kind() as Set

        then:
        kinds.containsAll(['dynamic-dispatch-hook', 'def-on-public-api', 'metaclass-mutation'])
        !kinds.contains('untyped-class')
        findings.find { it.kind() == 'dynamic-dispatch-hook' }.symbol() == 'dsl.Builder#methodMissing'
        findings.every { it.location() != null && !it.detail().isBlank() }
        typed.dynamicCode([]).empty
    }

    def 'untyped classes, grabs and runtime evaluation are reported'() {
        given:
        def malicious = Fixtures.services('malicious')
        def basic = Fixtures.services('basic-app')

        when:
        def grabs = malicious.dynamicCode([Fixtures.uri('malicious', 'src/main/groovy/evil/UsesGrab.groovy')])
        def untyped = basic.dynamicCode([Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy')])

        then:
        grabs*.kind().containsAll(['grab', 'untyped-class'])
        untyped*.kind() == ['untyped-class']
        untyped[0].symbol() == 'app.Greeter'
        untyped[0].location().range().start().line() == 5
    }

    def 'metaclass writes, Eval and process execution in a script are found'() {
        given:
        def services = Fixtures.services('basic-app')
        String uri = 'file:///virtual/Dyn.groovy'
        services.workspace().open(uri, '''
            String.metaClass.shout = { -> delegate.toUpperCase() }
            Eval.me('1 + 1')
            new GroovyShell().evaluate('2 + 2')
            'ls -la'.execute()
        ''')
        services.markDirty()

        when:
        def findings = services.dynamicCode([uri])

        then:
        findings*.kind().containsAll(['metaclass-mutation', 'dynamic-evaluation', 'process-execution'])
        findings.count { it.kind() == 'dynamic-evaluation' } == 2

        cleanup:
        services.workspace().close(uri)
        services.markDirty()
    }
}
