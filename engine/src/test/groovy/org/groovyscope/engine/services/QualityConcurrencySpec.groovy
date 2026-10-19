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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.groovyscope.engine.concurrent.MemoryGuard
import org.groovyscope.engine.concurrent.MemoryPressureException
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.Timeout

/** One type check per snapshot however many requests ask (E-12); memory pressure refuses it (E-18). */
@Timeout(120)
class QualityConcurrencySpec extends Specification {

    def 'three parallel checks run one type check'() {
        given:
        def services = Fixtures.services('typing-sample')
        String uri = Fixtures.uri('typing-sample', 'src/main/groovy/sample/Typed.groovy')
        services.workspace().open(uri, '@groovy.transform.CompileStatic\nclass Typed {\n    int n() { "x".noSuch() }\n}\n')
        services.markDirty()
        services.snapshot()
        def start = new CountDownLatch(1)

        when:
        def checks = (1..3).collect {
            CompletableFuture.supplyAsync {
                start.await(30, TimeUnit.SECONDS)
                services.quality().check([uri], null, [QualityServices.INCLUDE_TYPECHECK] as Set)
            }
        }
        start.countDown()
        def results = checks*.get(60, TimeUnit.SECONDS)

        then:
        services.quality().typeChecksRun() == 1
        results*.diagnostics()*.get(uri).unique().size() == 1

        cleanup:
        services.close()
    }

    def 'under memory pressure a type check is refused, and shedding never waits for a compile'() {
        given:
        def services = Fixtures.services('typing-sample')
        String uri = Fixtures.uri('typing-sample', 'src/main/groovy/sample/Typed.groovy')
        services.workspace().open(uri, '@groovy.transform.CompileStatic\nclass Typed {\n    int n() { "x".noSuch() }\n}\n')
        services.markDirty()
        services.snapshot()
        def pool = Stub(java.lang.management.MemoryPoolMXBean)
        pool.getUsage() >> new java.lang.management.MemoryUsage(0, 95L << 20, 100L << 20, 100L << 20)
        pool.getCollectionUsage() >> new java.lang.management.MemoryUsage(0, 95L << 20, 100L << 20, 100L << 20)
        def guard = MemoryGuard.of(pool, 85, { })
        guard.refresh()

        when:
        MemoryGuard.installForTest(guard)
        services.quality().check([uri], null, [QualityServices.INCLUDE_TYPECHECK] as Set)

        then:
        def refused = thrown(MemoryPressureException)
        refused.message.contains('low on heap')

        when:
        def compiler = new LightRequestsSpec.HeldCompiler()
        def held = new LanguageServices(services.workspace(), compiler)
        held.snapshot()
        MemoryGuard.reset()
        compiler.hold = new CountDownLatch(1)
        held.markDirty()
        def compiling = CompletableFuture.supplyAsync { held.snapshot() }
        compiler.entered.await(30, TimeUnit.SECONDS)
        def shed = CompletableFuture.runAsync { held.shed() }

        then:
        shed.get(10, TimeUnit.SECONDS) == null
        !compiling.isDone()

        cleanup:
        compiler?.hold?.countDown()
        MemoryGuard.reset()
        services.close()
    }
}
