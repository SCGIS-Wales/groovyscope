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
import java.nio.file.attribute.FileTime
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

/** Requests without an editor (CLI, MCP) must see files changed on disk since the last compile. */
class DiskSyncSpec extends Specification {

    @TempDir Path root

    def 'an edit on disk makes the next synced request recompile'() {
        given:
        Path file = root.resolve('A.groovy')
        Files.writeString(file, 'class A { void run() { } }\n')
        def services = new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
        def uri = WorkspaceModel.toUri(file)

        expect:
        services.diagnostics()[uri].empty

        when: 'nothing changed'
        services.syncWithDisk()

        then:
        !services.dirty

        when: 'the file is rewritten by another tool'
        Files.writeString(file, 'class A { void run() { undefinedCall( } }\n')
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000))
        services.syncWithDisk()

        then:
        services.dirty
        !services.diagnostics()[uri].empty

        when: 'a file is added'
        Files.writeString(root.resolve('B.groovy'), 'class B { }\n')
        services.syncWithDisk()

        then:
        services.dirty
        services.diagnostics().containsKey(WorkspaceModel.toUri(root.resolve('B.groovy')))
    }

    def 'syncing before the first compile changes nothing'() {
        given:
        Files.writeString(root.resolve('A.groovy'), 'class A { }\n')
        def services = new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
        boolean before = services.dirty

        when:
        services.syncWithDisk()

        then:
        services.dirty == before
    }
}
