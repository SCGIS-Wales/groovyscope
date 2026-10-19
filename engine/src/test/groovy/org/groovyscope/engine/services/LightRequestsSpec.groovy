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

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.groovyscope.engine.compile.CompilationResult
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.concurrent.Freshness
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Position
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.Timeout

/** Light requests answer from the current snapshot while a compile runs, unless their own document changed (E-11). */
@Timeout(120)
class LightRequestsSpec extends Specification {

    /** A compiler whose next compile waits for a latch, so a test can hold a compile "running". */
    static class HeldCompiler extends GroovyCompiler {
        volatile CountDownLatch hold
        final CountDownLatch entered = new CountDownLatch(1)

        @Override
        CompilationResult compile(Map<String, String> sources, List<Path> classpath, boolean trust) {
            if (hold != null) {
                entered.countDown()
                hold.await(60, TimeUnit.SECONDS)
            }
            super.compile(sources, classpath, trust)
        }
    }

    def 'an edit elsewhere: two hovers answer while the compile is still running, then the compile publishes'() {
        given:
        def compiler = new HeldCompiler()
        def services = new LanguageServices(new WorkspaceModel(Fixtures.project('basic-app'), EngineConfig.DEFAULTS),
                compiler)
        String greeter = Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy')
        String edited = Fixtures.uri('basic-app', 'src/main/groovy/app/Later.groovy')
        services.snapshot()
        long before = services.snapshot().version()

        when: 'another document changes and a heavy request starts the compile, which is held'
        compiler.hold = new CountDownLatch(1)
        services.workspace().open(edited, 'class Later { }')
        services.markDirty()
        def heavy = CompletableFuture.supplyAsync { services.snapshot() }
        compiler.entered.await(30, TimeUnit.SECONDS)
        def hovers = (1..2).collect {
            CompletableFuture.supplyAsync {
                Freshness.staleOkFor(greeter) { services.hover(greeter, new Position(11, 12)) }
            }
        }

        then: 'both hovers answer from the snapshot already published, without waiting for the compile'
        hovers.every { it.get(30, TimeUnit.SECONDS).isPresent() }
        !heavy.isDone()

        when:
        compiler.hold.countDown()

        then:
        heavy.get(30, TimeUnit.SECONDS).version() > before
        !services.isDirty()

        cleanup:
        compiler.hold?.countDown()
        services.workspace().close(edited)
        services.close()
    }

    def 'a light request on the document that changed waits for the fresh compile'() {
        given:
        def compiler = new HeldCompiler()
        def services = new LanguageServices(new WorkspaceModel(Fixtures.project('basic-app'), EngineConfig.DEFAULTS),
                compiler)
        String edited = Fixtures.uri('basic-app', 'src/main/groovy/app/Later.groovy')
        services.workspace().open(edited, 'class Later { }')
        services.snapshot()

        when:
        services.workspace().change(edited, 'class Later { String added() { "x" } }')
        services.markDirty()
        def symbols = Freshness.staleOkFor(edited) { services.documentSymbols(edited) }

        then:
        symbols[0].children()*.name().contains('added')

        cleanup:
        services.workspace().close(edited)
        services.close()
    }

    def 'with no published snapshot yet, a light request compiles; a background compile replaces the stale one'() {
        given:
        def services = Fixtures.services('basic-app')
        String greeter = Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy')
        String other = Fixtures.uri('basic-app', 'src/main/groovy/app/Later.groovy')

        when:
        def first = Freshness.staleOkFor(greeter) { services.snapshot() }

        then:
        first.version() > 0

        when: 'an edit elsewhere: the light request answers stale and queues the revalidation'
        services.workspace().open(other, 'class Later { }')
        services.markDirty()
        def stale = Freshness.staleOkFor(greeter) { services.snapshot() }
        services.backgroundCompile().get(60, TimeUnit.SECONDS)

        then:
        stale.version() == first.version()
        services.currentSnapshot().version() > first.version()
        !services.isDirty()

        cleanup:
        services.workspace().close(other)
        services.close()
    }
}
