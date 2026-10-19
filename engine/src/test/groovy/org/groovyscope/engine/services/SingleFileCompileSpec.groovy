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

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

/** A check after a write compiles the written file alone against the last full compile, and lints alongside. */
class SingleFileCompileSpec extends Specification {

    static final String MAIN = 'src/main/groovy/app/Main.groovy'
    static final String GREETER = 'src/main/groovy/app/Greeter.groovy'

    private static String uri(String relative) {
        Fixtures.uri('basic-app', relative)
    }

    def 'the written file is compiled alone, resolving the rest from the last compile, with the same findings'() {
        given:
        def services = Fixtures.services('basic-app')
        services.snapshot()
        String edited = Fixtures.text('basic-app', MAIN) + '\nclass Extra {\n    Greeter greeter = new Greeter()\n    MissingType missing\n}\n'
        services.workspace().open(uri(MAIN), edited)
        services.markDirty()

        when:
        def alone = services.compileOnly([uri(MAIN)])

        then:
        alone.present
        alone.get().sources().keySet() == [uri(MAIN)] as Set
        alone.get().diagnostics()[uri(MAIN)]*.message().any { it.contains('unable to resolve class MissingType') }
        !alone.get().diagnostics()[uri(MAIN)]*.message().any { it.contains('Greeter') }
        services.isDirty()

        when: 'the check takes the same path, and the full compile afterwards agrees'
        alone.get().close()
        def quick = services.quality().check([uri(MAIN)], null, ['compile', 'practices', 'lint'] as Set)
        services.snapshot()
        def full = services.quality().check([uri(MAIN)], null, ['compile', 'practices', 'lint'] as Set)

        then:
        quick.diagnostics()[uri(MAIN)].collect { [it.code(), it.range(), it.message()] } ==
            full.diagnostics()[uri(MAIN)].collect { [it.code(), it.range(), it.message()] }
        quick.diagnostics()[uri(MAIN)]*.source().contains('codenarc')

        cleanup:
        services.close()
    }

    def 'no single-file compile before the first compile, when nothing changed, or when other files changed'() {
        given:
        def services = Fixtures.services('basic-app')

        expect: 'nothing compiled yet'
        !services.compileOnly([uri(MAIN)]).present

        when:
        services.snapshot()

        then: 'the snapshot is current'
        !services.compileOnly([uri(MAIN)]).present

        when:
        services.workspace().open(uri(GREETER), Fixtures.text('basic-app', GREETER) + '\n// edited\n')
        services.markDirty()

        then: 'a file outside the targets changed, or a target did not'
        !services.compileOnly([uri(MAIN)]).present
        !services.compileOnly([uri(MAIN), uri(GREETER)]).present
        services.compileOnly([uri(GREETER)]).present

        when:
        services.workspace().open(uri('src/main/groovy/app/Added.groovy'), 'class Added { }')
        services.markDirty()

        then: 'a document was added'
        !services.compileOnly([uri(GREETER)]).present
        services.compileOnly([]).empty

        cleanup:
        services.close()
    }

    def "the lint thread's failure is rethrown as it was"() {
        when:
        QualityServices.joined(CompletableFuture.failedFuture(new IllegalStateException('broken rule')))

        then:
        thrown(IllegalStateException)

        when:
        QualityServices.joined(CompletableFuture.failedFuture(new AssertionError('bug')))

        then:
        thrown(AssertionError)

        when:
        QualityServices.joined(CompletableFuture.failedFuture(new IOException('io')))

        then:
        thrown(CompletionException)

        expect:
        QualityServices.joined(CompletableFuture.completedFuture([:])) == [:]
    }
}
