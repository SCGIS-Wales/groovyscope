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
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** Implicit-this calls inside closures: the owner, or the @DelegatesTo type, never a silent exact answer (Q-09). */
class ClosureReceiverSpec extends Specification {

    static final String FILE = 'src/main/groovy/demo/Pipeline.groovy'

    @Shared
    LanguageServices services = Fixtures.services('closure-delegates')

    private static String uri() {
        Fixtures.uri('closure-delegates', FILE)
    }

    private static pos(String needle, int occurrence = 1, int offset = 0) {
        Fixtures.pos('closure-delegates', FILE, needle, occurrence, offset)
    }

    def 'a call that only the @DelegatesTo type declares resolves to it, inferred'() {
        when:
        def definition = services.definition(uri(), pos("step 'compile'")).get()

        then:
        definition.symbol().container() == 'demo.Builder'
        definition.confidence() == Confidence.INFERRED
    }

    def 'an owner method shadowing a delegate method is ambiguous, and its rename is refused'() {
        when:
        def definition = services.definition(uri(), pos('shared()', 3))
        def rename = services.rename(uri(), pos('void shared()', 2, 5), 'common')

        then:
        definition.get().confidence() == Confidence.DYNAMIC_UNKNOWN
        rename.isRefused()
    }

    def 'an owner method called from a plain closure is the owner method, inferred rather than exact'() {
        expect:
        services.definition(uri(), pos('helper(it)')).get().with {
            symbol().container() == 'demo.Pipeline' && confidence() == Confidence.INFERRED
        }
        services.definition(uri(), pos('helper(3)')).get().confidence() == Confidence.STATIC
        !services.rename(uri(), pos('void helper', 1, 5), 'assist').isRefused()
    }
}
