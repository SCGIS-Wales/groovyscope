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
package org.groovyscope.engine.practices

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

/** The edit gate's fixture: every file parses, so each later check reports on code, never on a syntax error. */
class EditGateFixtureSpec extends Specification {

    def 'every source of the edit gate fixture parses'() {
        given:
        Path root = Fixtures.project('edit-gate')
        def compiler = new GroovyCompiler()
        List<Path> sources = Files.walk(root).withCloseable { stream ->
            stream.filter { it.toString().endsWith('.groovy') }.toList()
        }

        expect:
        sources.size() == 7
        sources.every { compiler.parse(it.fileName.toString(), Files.readString(it)).ok() }
    }
}
