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
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** Each compile is one generation: the previous one is released when the next snapshot is published. */
@Timeout(120)
class GenerationsSpec extends Specification {

    @TempDir Path root

    private LanguageServices services() {
        Files.createDirectories(root.resolve('src/main/groovy'))
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A { void run() {} }\n')
        Files.writeString(root.resolve('src/main/groovy/B.groovy'), 'class B { A a = new A() }\n')
        new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
    }

    def "the previous generation's class loader is closed when the next snapshot is published"() {
        given:
        def services = services()

        when:
        def first = services.refresh()
        services.markDirty()
        def second = services.refresh()

        then:
        first.result().loader() != null
        first.result().loader().closed
        !second.result().loader().closed
        second.version() > first.version()
    }

    def 'a file that stops parsing keeps the symbols of its last good text, parsed into the current generation'() {
        given:
        def services = services()
        String uri = WorkspaceModel.toUri(root.resolve('src/main/groovy/A.groovy'))
        def first = services.refresh()
        def before = first.index().classNamed('A').get()

        when: 'the file is broken on disk'
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A { void run( {\n')
        services.syncWithDisk()
        def broken = services.refresh()
        def after = broken.index().classNamed('A').get()

        then: 'the compile itself has no tree for it, but the index holds a fresh parse of the last good text'
        broken.result().modules()[uri] == null
        !broken.result().diagnostics()[uri].empty
        !after.is(before)
        after.methods*.name.contains('run')
        services.documentSymbols(uri)[0].children()*.name().contains('run')
        services.workspaceSymbols('A').any { it.name() == 'A' }
        first.result().loader().closed

        when: 'the file is fixed again'
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A { void run() {}; void more() {} }\n')
        services.syncWithDisk()
        def fixed = services.refresh()

        then:
        fixed.result().diagnostics()[uri].empty
        fixed.result().modules()[uri].classes[0].methods*.name.containsAll(['run', 'more'])
    }
}
