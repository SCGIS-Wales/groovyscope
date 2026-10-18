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
package org.groovyscope.engine.semantics

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.model.Position
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

/** A def local takes its type from a method's declared return type or from a factory closure's construction. */
class DefLocalInferenceSpec extends Specification {

    @TempDir Path root

    static final String MAIN = '''\
        |class Main {
        |    protected Runner makeRunner() { new Runner() }
        |    protected Closure createRunner = {
        |        def runner = new Runner()
        |        if (System.getenv('X')) {
        |            println 'x'
        |        }
        |        return runner
        |    }
        |    protected Closure mixed = { flag -> flag ? new Runner() : 'text' }
        |
        |    void run() {
        |        def fromMethod = makeRunner()
        |        fromMethod.execute()
        |        def fromClosure = createRunner()
        |        fromClosure.execute()
        |        def unknown = mixed(true)
        |        unknown.execute()
        |    }
        |}
        |'''.stripMargin()

    private LanguageServices services() {
        Files.writeString(root.resolve('Runner.groovy'), 'class Runner {\n    String execute() { "done" }\n}\n')
        Files.writeString(root.resolve('Main.groovy'), MAIN)
        new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
    }

    private static Position at(String needle) {
        List<String> lines = MAIN.readLines()
        int line = lines.findIndexOf { it.contains(needle) }
        new Position(line, lines[line].indexOf(needle) + needle.indexOf('.') + 1)
    }

    def 'calls through a def local typed by a method or a factory closure are inferred, not dynamic'() {
        given:
        def services = services()
        String uri = WorkspaceModel.toUri(root.resolve('Main.groovy'))

        expect:
        services.definition(uri, at('fromMethod.execute()')).get().with {
            symbol().container() == 'Runner' && confidence() == Confidence.INFERRED
        }
        services.definition(uri, at('fromClosure.execute()')).get().with {
            symbol().container() == 'Runner' && confidence() == Confidence.INFERRED
        }
    }

    def 'a closure whose returns disagree leaves the local dynamic'() {
        given:
        def services = services()
        String uri = WorkspaceModel.toUri(root.resolve('Main.groovy'))

        expect:
        services.definition(uri, at('unknown.execute()')).get().confidence() == Confidence.DYNAMIC_UNKNOWN
    }
}
