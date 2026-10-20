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

/** Overloads of one arity are told apart by argument type, and overrides by erased parameter types (Q-11). */
class OverloadSpec extends Specification {

    static final String FILE = 'src/main/groovy/demo/Repository.groovy'

    @Shared
    LanguageServices services = Fixtures.services('overloads')

    private static String uri() {
        Fixtures.uri('overloads', FILE)
    }

    private static pos(String needle, int occurrence = 1, int offset = 0) {
        Fixtures.pos('overloads', FILE, needle, occurrence, offset)
    }

    private ImpactReport report(String detail) {
        services.impactOf('demo.Repository.save', ImpactReport.Change.SIGNATURE).find { it.symbol().detail() == detail }
    }

    def 'save(String) and save(Order) are not each other\'s callers'() {
        when:
        def byKey = report('void save(String key)')
        def byOrder = report('void save(Order order)')

        then:
        byKey.directCallers()*.caller()*.name() == ['storeKey']
        byOrder.directCallers()*.caller()*.name() == ['storeOrder']
    }

    def 'a call whose argument type is unknown reaches either overload, so it is dynamic'() {
        when:
        def definition = services.definition(uri(), pos('save(value)')).get()

        then:
        definition.confidence() == Confidence.DYNAMIC_UNKNOWN
        report('void save(String key)').dynamicUnknowns()*.caller()*.name() == ['storeAny']
        report('void save(Order order)').dynamicUnknowns()*.caller()*.name() == ['storeAny']
    }

    def 'a call narrowed by its argument type is inferred'() {
        expect:
        services.definition(uri(), pos("save('k1')")).get().with {
            symbol().detail() == 'void save(String key)' && confidence() == Confidence.INFERRED
        }
    }

    def 'overrides match erased parameter types, not arity alone'() {
        expect:
        report('void save(String key)').overrides().isEmpty()
        report('void save(Order order)').overrides()*.container() == ['demo.AuditedRepository']
        services.implementations(uri(), pos('void save(String', 1, 5)).isEmpty()
        services.implementations(uri(), pos('void save(Order', 1, 5)).size() == 1
    }

    def 'a generic parameter matches the type argument a subclass overrides it with'() {
        when:
        def report = services.impactOf('demo.Store.put', ImpactReport.Change.SIGNATURE)[0]

        then:
        report.overrides()*.container() == ['demo.OrderStore']
    }
}
