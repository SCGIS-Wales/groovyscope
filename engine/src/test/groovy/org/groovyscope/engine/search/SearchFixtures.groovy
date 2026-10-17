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
package org.groovyscope.engine.search

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.services.Snapshot
import org.groovyscope.engine.workspace.WorkspaceModel

/** Compiles Groovy sources written into a temporary workspace. */
class SearchFixtures {

    static Snapshot compile(Path root, Map<String, String> files) {
        files.each { String relative, String text ->
            Path file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler()).snapshot()
    }

    static String uri(Path root, String relative) {
        root.resolve(relative).toUri().toString()
    }
}
