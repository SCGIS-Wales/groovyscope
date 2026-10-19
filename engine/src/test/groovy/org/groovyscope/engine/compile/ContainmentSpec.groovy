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
package org.groovyscope.engine.compile

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.codehaus.groovy.GroovyBugError
import org.groovyscope.engine.concurrent.Cancellation
import org.groovyscope.engine.concurrent.Containment
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer
import spock.lang.Specification

/** Loaders are released on every exit (E-16); a crash is contained to the file that caused it (E-17). */
class ContainmentSpec extends Specification {

    static final String GOOD = 'file:///w/Good.groovy'
    static final String DEEP = 'file:///w/Deep.groovy'
    static final String BAD = 'file:///w/Bad.groovy'

    def 'a cancel during the parse-only pass leaves no loader open; a kept loader closes with its result'() {
        given: 'a file with a syntax error is dropped, so the compile ends with a parse-only pass'
        def compiler = new GroovyCompiler()
        def sources = [(GOOD): 'class Good { }', (BAD): 'class Bad {']
        def calls = new AtomicInteger()

        when: 'the request is cancelled once the rounds are done, during the parse-only pass'
        Cancellation.run({ calls.incrementAndGet() > 2 }) { compiler.compile(sources, [], false) }

        then:
        thrown(CancellationException)
        compiler.openLoaderCount() == 0

        when:
        def result = compiler.compile(sources, [], false)

        then:
        compiler.openLoaderCount() == 1

        when:
        result.loader().close()

        then:
        compiler.openLoaderCount() == 0
    }

    def 'a file that crashes the type checker is isolated; the others are still checked'() {
        given:
        String deep = '@groovy.transform.CompileStatic\nclass Deep { int v() { ' + ('1 + ' * 1500) + '1 } }'
        def sources = [(GOOD): '@groovy.transform.CompileStatic\nclass Good { int n() { "x".noSuch() } }', (DEEP): deep]

        when:
        def result = new TypeCheckAnalyzer().check(sources, [], false, TypeCheckAnalyzer.CORE_ONLY,
                EngineConfig.DEFAULTS.groovy(), [] as Set)

        then:
        result.status()[GOOD] == TypeCheckAnalyzer.Status.CHECKED
        result.diagnostics()[GOOD]*.code() == ['typecheck']
        result.status()[DEEP] == TypeCheckAnalyzer.Status.CRASHED
        result.diagnostics()[DEEP]*.code() == ['typecheck-failed']
    }

    def 'what counts as recoverable'() {
        expect:
        Containment.recoverable(new IllegalStateException())
        Containment.recoverable(new NoClassDefFoundError())
        Containment.recoverable(new GroovyBugError('x'))
        Containment.recoverable(new StackOverflowError())
        !Containment.recoverable(new OutOfMemoryError())
        !Containment.recoverable(new InternalError())
    }
}
