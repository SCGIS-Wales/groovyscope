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
package org.groovyscope.engine.search

import org.groovyscope.engine.services.Snapshot
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** Call search uses the resolver's receiver types, keeps unknown receivers, and reads GString literals (Q-14). */
class SearchReceiverSpec extends Specification {

    @Shared
    Snapshot snapshot = Fixtures.services('search-receivers').snapshot()

    private List<CodeSearch.Match> find(String kind, String name, String type, String pattern) {
        CodeSearch.find(snapshot.result().modules(), snapshot.index()::lines, [],
                new CodeSearch.Query(kind, name, type, pattern, [], null, null, null, null), snapshot.resolver())
    }

    def 'a call on a def local initialised from a static factory is found by its receiver type'() {
        when:
        def found = find('call', 'rows', 'Sql', null)

        then:
        found*.detail == ['call other.rows', 'call sql.rows']
        found*.facts*.receiverKnown == [false, true]
    }

    def 'a call whose receiver is known to be another type is not found'() {
        expect:
        !find('call', 'rows', 'Sql', null)*.snippet.any { it.contains('Ledger') }
        find('call', 'rows', 'Ledger', null)*.facts*.receiverKnown == [true, false]
    }

    def 'without a type filter matches carry no receiver fact'() {
        expect:
        find('call', 'rows', null, null).every { !it.facts.containsKey('receiverKnown') }
    }

    def 'the literal parts of a GString are searched'() {
        when:
        def found = find('string-literal', null, null, 'SELECT')

        then:
        found.size() == 1
        found[0].detail.contains('SELECT * FROM')
        find('string-literal', null, null, 'table').isEmpty()
    }
}
