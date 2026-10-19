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
package org.groovyscope.engine.explain

import org.codehaus.groovy.ast.MethodNode
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** A method's test seams in one answer: collaborators, how tests replace them, the tests and their helpers. */
class TestSeamsSpec extends Specification {

    @Shared
    LanguageServices services = Fixtures.services('test-doubles-sample')

    private TestSeams.Seams seams(String symbol) {
        def snapshot = services.snapshot()
        MethodNode method = services.findDeclarations(snapshot, symbol).first() as MethodNode
        TestSeams.of(method, services.callGraph(), services.&declarationOf, snapshot.result().modules(),
                services.workspace().&text)
    }

    def 'collaborators come with their visibility, how to replace them and an existing stub'() {
        when:
        def found = seams('sample.Store#load')

        then:
        found.collaborators()*.target() == ['sample.Store.fetch', 'sample.Store.format']
        with(found.collaborators()[0]) {
            visibility() == 'private'
            stub().startsWith('private: no Mockito or Spock double can stub it')
            stubbedAt() == 'StoreTest.groovy:12'
            example() == "doReturn('x').when(store).fetch('k')"
        }
        with(found.collaborators()[1]) {
            visibility() == 'public'
            compileStatic()
            stub().contains('doReturn(..).when(spy).format(..)') && stub().contains('never reaches it')
            stubbedAt() == 'StoreTest.groovy:18'
        }
        found.direct() == null
    }

    def 'the tests of the class are named with their helpers, and a private method can be called directly'() {
        when:
        def found = seams('sample.Store#fetch')

        then:
        found.tests()*.name() == ['sample.StoreTest']
        found.tests()[0].helpers() == ['checkLoaded() line 49', 'expect(int size) line 53']
        found.direct().startsWith('private: a dynamic Groovy test calls it directly')
        found.collaborators().empty
    }

    def 'advice for static methods, and tests found through the call graph'() {
        when:
        def cache = seams('sample.Cache#size')

        then:
        cache.collaborators()*.target() == ['sample.Cache.entries']
        cache.collaborators()[0].stub().startsWith('spy the instance') && !cache.collaborators()[0].compileStatic()
        cache.collaborators()[0].stubbedAt() == 'CacheSpec.groovy:11'
        cache.tests()*.name() == ['sample.CacheSpec', 'sample.StoreTest']
        TestSeams.advice(new MethodNode('of', java.lang.reflect.Modifier.STATIC | java.lang.reflect.Modifier.PUBLIC,
                org.codehaus.groovy.ast.ClassHelper.OBJECT_TYPE, [] as org.codehaus.groovy.ast.Parameter[], null, null))
                .startsWith('static: Mockito.mockStatic')
    }
}
