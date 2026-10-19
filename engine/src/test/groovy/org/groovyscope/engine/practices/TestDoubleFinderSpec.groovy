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

import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** GS013 to GS016 on the test-doubles-sample fixture: doubles that cannot take effect, tests that check nothing. */
class TestDoubleFinderSpec extends Specification {

    @Shared
    LanguageServices services = Fixtures.services('test-doubles-sample')

    private List<List> found(String relative) {
        services.quality().practices(Fixtures.uri('test-doubles-sample', relative))
            .findAll { it.diagnostic().code() in ['GS013', 'GS014', 'GS015', 'GS016'] }
            .collect { [it.diagnostic().code(), it.diagnostic().range().start().line() + 1] }
    }

    private String message(String relative, String code) {
        services.quality().practices(Fixtures.uri('test-doubles-sample', relative))
            .find { it.diagnostic().code() == code }.diagnostic().message()
    }

    def 'JUnit tests: private stubs, bypassed and mixed metaClass overrides, tests that check nothing'() {
        expect:
        found('src/test/groovy/sample/StoreTest.groovy') ==
            [['GS013', 12], ['GS014', 25], ['GS015', 26], ['GS013', 39], ['GS013', 40], ['GS016', 31]]
        message('src/test/groovy/sample/StoreTest.groovy', 'GS013').contains('Store.fetch is private')
        message('src/test/groovy/sample/StoreTest.groovy', 'GS014').contains('Store is @CompileStatic')
        message('src/test/groovy/sample/StoreTest.groovy', 'GS016').contains('runsWithoutChecking')
    }

    def 'Spock: a stub on a private method is found, features are not asked for assertions'() {
        expect:
        found('src/test/groovy/sample/CacheSpec.groovy') == [['GS013', 10]]
        message('src/test/groovy/sample/CacheSpec.groovy', 'GS013').contains('Spock double')
    }

    def 'production code is never checked'() {
        expect:
        found('src/main/groovy/sample/Store.groovy').empty
        found('src/main/groovy/sample/Cache.groovy').empty
    }
}
