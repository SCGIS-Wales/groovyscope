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

import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.model.ImpactReport
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** Calls on a classpath receiver that only a workspace extension or category method answers (Q-10). */
class ExtensionMethodSpec extends Specification {

    static final String FILE = 'src/main/groovy/demo/Greeter.groovy'

    @Shared
    LanguageServices services = Fixtures.services('extension-methods')

    private static String uri() {
        Fixtures.uri('extension-methods', FILE)
    }

    private static pos(String needle, int occurrence = 1, int offset = 0) {
        Fixtures.pos('extension-methods', FILE, needle, occurrence, offset)
    }

    def 'an extension method on a classpath type resolves to the workspace static method, inferred'() {
        when:
        def definition = services.definition(uri(), pos('shout()', 1, 0)).get()

        then:
        definition.symbol().container() == 'demo.StringExtensions'
        definition.confidence() == Confidence.INFERRED
    }

    def 'a category method used on its category type resolves to the category class'() {
        when:
        def definition = services.definition(uri(), pos('twice()', 2)).get()

        then:
        definition.symbol().container() == 'demo.Doubling'
        definition.confidence() == Confidence.INFERRED
    }

    def 'the extension method has its caller in impact'() {
        when:
        def report = services.impactOf('demo.StringExtensions.shout', ImpactReport.Change.SIGNATURE)[0]

        then:
        report.directCallers()*.caller()*.name().contains('greet')
    }

    def 'a classpath call nothing in the workspace answers still resolves to nothing'() {
        expect:
        services.definition(uri(), pos('whisper()')).isEmpty()
    }
}
