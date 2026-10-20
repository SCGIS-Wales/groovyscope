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
package org.groovyscope.engine.callgraph

import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.model.ImpactReport
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** Tests that reach a symbol through a dynamic receiver are possible tests, not "no test" (Q-12). */
class PossibleTestSpec extends Specification {

    @Shared
    LanguageServices services = Fixtures.services('dynamic-tests')

    @Shared
    ImpactReport report = services.impactOf('demo.Calculator.add', ImpactReport.Change.SIGNATURE)[0]

    def 'a Spock spec with a def receiver counts as a possible test, directly or transitively'() {
        expect:
        report.tests()*.caller()*.container().toSet() == ['demo.CalculatorSpec'] as Set
        report.tests().size() == 2
        report.tests().every { it.confidence() == Confidence.DYNAMIC_UNKNOWN }
    }

    def 'transitive callers include the sites matched by name only, flagged as dynamic'() {
        expect:
        report.transitiveCallers().any { it.confidence() == Confidence.DYNAMIC_UNKNOWN && it.callee().name() == 'sum' }
        report.transitiveCallers().any { it.confidence() != Confidence.DYNAMIC_UNKNOWN && it.caller().name() == 'render' }
    }

    def 'possible tests suppress the "no test" advice and are named as possible'() {
        expect:
        !report.suggestedChecks().any { it.contains('no test reaches') }
        report.suggestedChecks().any { it.contains('test file(s)') && it.contains('possibly') }
    }
}
