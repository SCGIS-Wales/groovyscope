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
package org.groovyscope.engine.concurrent

import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.lsp.GroovyscopeLanguageServer
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

/** Memory pressure sheds caches and refuses whole-workspace requests; it clears when a collection frees memory. */
class MemoryGuardSpec extends Specification {

    @TempDir Path root

    def cleanup() {
        MemoryGuard.reset()
    }

    private MemoryPoolMXBean pool(long usedAfterGc, long max) {
        def pool = Stub(MemoryPoolMXBean)
        pool.getType() >> MemoryType.HEAP
        pool.isCollectionUsageThresholdSupported() >> true
        pool.getUsage() >> new MemoryUsage(0, usedAfterGc, usedAfterGc, max)
        pool.getCollectionUsage() >> new MemoryUsage(0, usedAfterGc, usedAfterGc, max)
        pool
    }

    def 'pressure is declared once, sheds, and clears when a collection leaves the pool under the threshold'() {
        given:
        def shed = new AtomicInteger()
        def guard = MemoryGuard.of(pool(90L * 1024 * 1024, 100L * 1024 * 1024), 85, { shed.incrementAndGet() })

        expect:
        !guard.underPressure()

        when: 'the JVM reports the threshold crossed, twice'
        guard.tripForTest()
        guard.tripForTest()

        then:
        guard.underPressure()
        shed.get() == 1
        guard.advice().contains('90 MB of 100 MB')
        guard.advice().contains('daemon.heap')

        when: 'a later collection leaves less in use'
        def relaxed = MemoryGuard.of(pool(10L * 1024 * 1024, 100L * 1024 * 1024), 85, { shed.incrementAndGet() })
        relaxed.tripForTest()
        relaxed.refresh()

        then:
        !relaxed.underPressure()

        when: 'the notification was missed but the pool is over the line'
        def missed = MemoryGuard.of(pool(95L * 1024 * 1024, 100L * 1024 * 1024), 85, { shed.incrementAndGet() })
        missed.refresh()

        then:
        missed.underPressure()
    }

    def 'under pressure a whole-workspace request is refused and a single-document one is served'() {
        given:
        Files.createDirectories(root.resolve('src/main/groovy'))
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A { void run() {} }\n')
        def server = new GroovyscopeLanguageServer({ }, root, EngineConfig.DEFAULTS)
        def guard = MemoryGuard.of(pool(90L * 1024 * 1024, 100L * 1024 * 1024), 85, { })
        guard.tripForTest()
        MemoryGuard.installForTest(guard)
        String uri = WorkspaceModel.toUri(root.resolve('src/main/groovy/A.groovy'))

        when:
        server.duplicates([root: root.toString()]).get()

        then:
        def failure = thrown(java.util.concurrent.ExecutionException)
        failure.cause instanceof ResponseErrorException
        failure.cause.message.contains('low on heap')

        when:
        def whole = server.check([root: root.toString(), uris: [], include: ['compile']])
        def one = server.check([root: root.toString(), uris: [uri], include: ['compile']]).get()

        then:
        whole.isCompletedExceptionally()
        one.diagnostics != null
        server.status().get().memory.pressure == true

        when: 'pressure clears'
        MemoryGuard.installForTest(MemoryGuard.of(pool(10L * 1024 * 1024, 100L * 1024 * 1024), 85, { }))

        then:
        server.duplicates([root: root.toString()]).get() != null
    }

    def 'shedding drops the caches a workspace can rebuild'() {
        given:
        Files.createDirectories(root.resolve('src/main/groovy'))
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A { void run() { helper() }; void helper() {} }\n')
        def services = new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
        def graph = services.callGraph()

        when:
        services.shed()

        then: 'the graph is rebuilt on demand, from the same snapshot'
        !services.callGraph().is(graph)
        services.snapshot().version() == 1
    }

    def 'the tenured pool of this JVM is found and the guard installs once'() {
        expect:
        MemoryGuard.tenuredPool().present
        MemoryGuard.install(85, { }).present
        MemoryGuard.install(50, { }).get().percent() == 85
        !MemoryGuard.pressure()
    }
}
