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
package org.groovyscope.engine.perf

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Position
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Generates a synthetic workspace and reports timings for a warm references query and a single-file
 * re-check. The budgets below are generous on purpose (shared CI runners); the numbers are printed so they
 * can be tracked. Set -Dgroovyscope.perf.files=2000 for the full-size run.
 */
class PerformanceReportSpec extends Specification {

    static final int FILES = Integer.getInteger('groovyscope.perf.files', 300)

    @TempDir
    Path temp

    def 'warm references and a single-file re-check stay within budget'() {
        given: 'FILES classes, each calling the previous one'
        Path root = temp.resolve('src/main/groovy/gen')
        Files.createDirectories(root)
        FILES.times { i ->
            String previous = i == 0 ? '' : "        new Class${i - 1}().work${i - 1}(n)\n"
            Files.writeString(root.resolve("Class${i}.groovy"), """\
package gen

class Class${i} {
    int value${i} = ${i}

    int work${i}(int n) {
${previous}        value${i} + n
    }
}
""")
        }
        def services = new LanguageServices(new WorkspaceModel(temp, EngineConfig.DEFAULTS), new GroovyCompiler())
        String target = root.resolve('Class0.groovy').toUri().toString()

        when: 'the first compile happens'
        long coldStart = System.nanoTime()
        services.refresh()
        long coldMillis = (System.nanoTime() - coldStart) / 1_000_000

        and: 'a warm references query runs'
        long warmStart = System.nanoTime()
        def references = services.references(target, new Position(5, 8), false)
        long warmMillis = (System.nanoTime() - warmStart) / 1_000_000

        and: 'one file changes and the snapshot is rebuilt'
        services.workspace().open(target, Files.readString(root.resolve('Class0.groovy')) + '\n// edited\n')
        services.markDirty()
        long recheckStart = System.nanoTime()
        services.snapshot()
        long recheckMillis = (System.nanoTime() - recheckStart) / 1_000_000

        and: 'after another edit, the diagnostics of the one open document are pushed (what an agent session pays)'
        services.workspace().change(target, Files.readString(root.resolve('Class0.groovy')) + '\n// edited again\n')
        services.markDirty()
        long openStart = System.nanoTime()
        def openOnly = services.quality().publishedDiagnostics([target])
        long openMillis = (System.nanoTime() - openStart) / 1_000_000

        and: 'while another file is edited and its compile runs, two hovers on an unchanged file (the light lane)'
        String other = root.resolve('Class1.groovy').toUri().toString()
        services.workspace().open(other, Files.readString(root.resolve('Class1.groovy')) + '\n// edited\n')
        services.markDirty()
        def compiling = java.util.concurrent.CompletableFuture.supplyAsync { services.snapshot() }
        long hoverStart = System.nanoTime()
        2.times {
            org.groovyscope.engine.concurrent.Freshness.staleOkFor(target) { services.hover(target, new Position(5, 8)) }
        }
        long hoverMillis = (System.nanoTime() - hoverStart) / 1_000_000
        compiling.get(120, java.util.concurrent.TimeUnit.SECONDS)

        and: 'the same for every document (the pre-0.13 push)'
        long allStart = System.nanoTime()
        def everything = services.quality().publishedDiagnostics()
        long allMillis = (System.nanoTime() - allStart) / 1_000_000

        then:
        references.size() == 1
        openOnly.keySet() == [target] as Set
        everything.size() == FILES
        println "perf: files=${FILES} cold-compile=${coldMillis}ms warm-references=${warmMillis}ms " +
                "single-file-recheck=${recheckMillis}ms publish-open-document=${openMillis}ms publish-all=${allMillis}ms " +
                "two-hovers-during-compile=${hoverMillis}ms"
        warmMillis < 5_000
        recheckMillis < 60_000
        openMillis < 60_000
        hoverMillis < HOVER_DURING_COMPILE_MILLIS
    }

    /** The plan's target for two hovers while a compile runs (E-11). */
    static final long HOVER_DURING_COMPILE_MILLIS = 200
}
