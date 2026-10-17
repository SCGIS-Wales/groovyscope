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
package org.groovyscope.engine.compile

import org.codehaus.groovy.control.Phases
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification

class GroovyCompilerSpec extends Specification {

    GroovyCompiler compiler = new GroovyCompiler()

    private CompilationResult compile(String project, EngineConfig config = EngineConfig.DEFAULTS) {
        def model = new WorkspaceModel(Fixtures.project(project), config)
        compiler.compile(model.sources(), model.classpath(), config.trust())
    }

    def 'compiles a whole project to canonicalization and keeps every module'() {
        when:
        def result = compile('basic-app')

        then:
        result.modules().size() == 6
        result.phaseReached() >= Phases.CANONICALIZATION
        result.diagnostics().keySet().size() == 6
        result.diagnostics()[Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy')].empty
    }

    def 'unresolved classes become error diagnostics in the right file'() {
        when:
        def result = compile('basic-app')
        def spec = result.diagnostics()[Fixtures.uri('basic-app', 'src/test/groovy/app/GreeterSpec.groovy')]

        then:
        result.hasErrors()
        spec.any { it.severity() == Diagnostic.Severity.ERROR && it.message().contains('spock.lang.Specification') }
        spec.every { it.source() == GroovyCompiler.SOURCE }

        and: 'the file is parsed on its own while the others are fully resolved'
        result.unresolvedUris() == [Fixtures.uri('basic-app', 'src/test/groovy/app/GreeterSpec.groovy')] as Set
        result.modules().size() == 6
    }

    def 'a file that does not parse is dropped and the rest still compiles'() {
        when:
        def result = compile('broken')
        String broken = Fixtures.uri('broken', 'src/main/groovy/Broken.groovy')
        String fine = Fixtures.uri('broken', 'src/main/groovy/Fine.groovy')

        then:
        result.modules().keySet() == [fine] as Set
        result.diagnostics()[broken].any { it.code() == 'syntax' && it.range().start().line() == 1 }
        result.diagnostics()[fine].empty
    }

    def 'an empty workspace compiles to nothing'() {
        expect:
        compiler.compile([:], [], false).modules().isEmpty()
    }

    def 'syntax ranges never collapse'() {
        expect:
        GroovyCompiler.rangeOf(new org.codehaus.groovy.syntax.SyntaxException('x', 3, 5, 3, 2)).end().column() == 5
        GroovyCompiler.rangeOf(new org.codehaus.groovy.syntax.SyntaxException('x', -1, -1)).start().line() == 0
    }
}
