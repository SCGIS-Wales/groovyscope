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
package org.groovyscope.engine.practices

import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** GS040 to GS043 on the spock-quality fixture: what Spock itself would reject or silently ignore. */
class SpockFinderSpec extends Specification {

    static final List<String> CODES = ['GS040', 'GS041', 'GS042', 'GS043']

    @Shared
    LanguageServices services = Fixtures.services('spock-quality')

    private List<PracticeFinder.Practice> practices(String name) {
        services.quality().practices(Fixtures.uri('spock-quality', 'src/test/groovy/demo/' + name))
            .findAll { it.diagnostic().code() in CODES }
    }

    private List<List> found(String name) {
        practices(name).collect { [it.diagnostic().code(), it.diagnostic().range().start().line() + 1] }
    }

    private Diagnostic first(String name, String code) {
        practices(name).find { it.diagnostic().code() == code }.diagnostic()
    }

    def 'interactions, data tables, @Unroll and exception conditions that Spock gets wrong are found'() {
        expect:
        found('MessySpec.groovy') == [['GS040', 16], ['GS040', 17], ['GS042', 20], ['GS041', 26], ['GS041', 35],
                                      ['GS043', 42], ['GS043', 51]]
        practices('MessySpec.groovy').findAll { it.diagnostic().code() == 'GS040' }*.diagnostic()*.message()
            .with { it[0].contains('cardinality') && it[1].contains('Mock()') }
        first('MessySpec.groovy', 'GS041').message().contains("'d'")
        practices('MessySpec.groovy').findAll { it.diagnostic().code() == 'GS041' }[1].diagnostic().message()
            .contains("'a' twice")
        first('MessySpec.groovy', 'GS042').severity() == Diagnostic.Severity.INFORMATION
        first('MessySpec.groovy', 'GS043').message().contains('expect:')
        first('MessySpec.groovy', 'GS043').severity() == Diagnostic.Severity.ERROR
    }

    def 'cardinalities, named unrolling, derived columns and examined exceptions are left alone'() {
        expect:
        found('CleanSpec.groovy').empty
    }

    def 'production code is never read as a specification'() {
        expect:
        services.quality().practices(Fixtures.uri('spock-quality', 'src/main/groovy/demo/Store.groovy'))
            .every { !(it.diagnostic().code() in CODES) }
    }

    def 'the catalogue explains each code'() {
        expect:
        CODES.every { Explanations.explain(it).isPresent() }
    }
}
