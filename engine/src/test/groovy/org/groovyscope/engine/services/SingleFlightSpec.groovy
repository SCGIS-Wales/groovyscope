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

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.groovyscope.engine.compile.CompilationResult
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.concurrent.Cancellation
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** One compile per change however many requests wait, and cancellation that stops only what nobody wants. */
@Timeout(120)
class SingleFlightSpec extends Specification {

    @TempDir Path root

    /** A compiler that pauses inside its compile until released, and counts its calls. */
    static class GatedCompiler extends GroovyCompiler {
        final AtomicInteger calls = new AtomicInteger()
        final CountDownLatch entered = new CountDownLatch(1)
        final CountDownLatch release = new CountDownLatch(1)

        @Override
        CompilationResult compile(Map<String, String> sources, List<Path> classpath, boolean trust) {
            calls.incrementAndGet()
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            Cancellation.checkpoint() // what the real compiler does between rounds
            super.compile(sources, classpath, trust)
        }
    }

    private LanguageServices newServices(GroovyCompiler compiler) {
        Files.createDirectories(root.resolve('src/main/groovy'))
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A { void run() {} }\n')
        new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), compiler)
    }

    def 'concurrent requests share one compile'() {
        given:
        def compiler = new GatedCompiler()
        def services = newServices(compiler)
        def results = new AtomicReference<List<Snapshot>>(Collections.synchronizedList([]))
        def threads = (1..4).collect { new Thread({ results.get() << services.snapshot() }) }

        when:
        threads*.start()
        compiler.entered.await(30, TimeUnit.SECONDS)
        compiler.release.countDown()
        threads*.join(30_000)

        then:
        compiler.calls.get() == 1
        results.get().size() == 4
        results.get()*.version().toSet().size() == 1
        !services.isDirty()
    }

    def 'a cancelled waiter leaves while the compile goes on for the others'() {
        given:
        def compiler = new GatedCompiler()
        def services = newServices(compiler)
        def waiterCancelled = new AtomicBoolean()
        def waiterOutcome = new AtomicReference<Object>()
        def owner = new Thread({ services.snapshot() })
        def waiter = new Thread({
            try {
                waiterOutcome.set(Cancellation.run({ waiterCancelled.get() }, { services.snapshot() }))
            } catch (CancellationException e) {
                waiterOutcome.set(e)
            }
        })

        when: 'the owner is inside the compile, the waiter joins, then the waiter is cancelled'
        owner.start()
        compiler.entered.await(30, TimeUnit.SECONDS)
        waiter.start()
        Thread.sleep(300)
        waiterCancelled.set(true)
        waiter.join(30_000)

        then: 'the waiter is gone with a cancellation while the compile is still running'
        waiterOutcome.get() instanceof CancellationException
        owner.alive

        when:
        compiler.release.countDown()
        owner.join(30_000)

        then: 'the compile finished for its owner'
        compiler.calls.get() == 1
        !services.isDirty()
        services.fileCount() == 1
    }

    def 'a compile nobody waits for any more is abandoned at the next checkpoint and leaves the workspace dirty'() {
        given:
        def compiler = new GatedCompiler()
        def services = newServices(compiler)
        def ownerCancelled = new AtomicBoolean()
        def outcome = new AtomicReference<Object>()
        def owner = new Thread({
            try {
                outcome.set(Cancellation.run({ ownerCancelled.get() }, { services.snapshot() }))
            } catch (CancellationException e) {
                outcome.set(e)
            }
        })

        when:
        owner.start()
        compiler.entered.await(30, TimeUnit.SECONDS)
        ownerCancelled.set(true)
        compiler.release.countDown()
        owner.join(30_000)

        then:
        outcome.get() instanceof CancellationException
        services.isDirty()
        compiler.calls.get() == 1

        when: 'the next request compiles afresh'
        def again = new GatedCompiler()
        def fresh = newServices(again)
        again.release.countDown()
        def snapshot = fresh.snapshot()

        then:
        snapshot.version() == 1
        !fresh.isDirty()
    }

    def 'closing a workspace releases its loader and lets it be evicted'() {
        given:
        def compiler = new GatedCompiler()
        compiler.release.countDown()
        def services = newServices(compiler)
        def snapshot = services.snapshot()

        when:
        def before = services.lastUsedNanos()
        services.close()

        then:
        snapshot.result().loader().closed
        before > 0
    }
}
