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
package org.groovyscope.engine.support

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Position
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel

/** Locates fixture projects under src/test/resources/projects and turns text into positions. */
class Fixtures {

    static Path project(String name) {
        URL url = Fixtures.classLoader.getResource("projects/${name}")
        assert url != null : "fixture project ${name} is not on the test classpath"
        Paths.get(url.toURI())
    }

    static LanguageServices services(String name, EngineConfig config = EngineConfig.DEFAULTS) {
        new LanguageServices(new WorkspaceModel(project(name), config), new GroovyCompiler())
    }

    static String uri(String name, String relative) {
        project(name).resolve(relative).toUri().toString()
    }

    static String text(String name, String relative) {
        Files.readString(project(name).resolve(relative), StandardCharsets.UTF_8)
    }

    /** The zero-based position of {@code needle}'s nth occurrence, shifted by {@code offset} characters. */
    static Position pos(String name, String relative, String needle, int occurrence = 1, int offset = 0) {
        String content = text(name, relative)
        int index = -1
        occurrence.times {
            index = content.indexOf(needle, index + 1)
            assert index >= 0 : "'${needle}' (occurrence ${occurrence}) not found in ${relative}"
        }
        int target = index + offset
        int line = 0
        int column = 0
        for (int i = 0; i < target; i++) {
            if (content.charAt(i) == '\n' as char) {
                line++
                column = 0
            } else {
                column++
            }
        }
        new Position(line, column)
    }
}
