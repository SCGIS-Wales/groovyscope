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

import java.nio.file.Paths
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

    def 'an unresolved class in one file is reported while another file has an unresolved import'() {
        when: 'the import fails in B and the body of A names a class that exists nowhere'
        def result = compiler.compile([
                'file:///w/A.groovy': 'class A {\n    void run() {\n        NoSuchTypeAnywhere x = null\n    }\n}\n',
                'file:///w/B.groovy': 'import org.apache.foo.Bar\nclass B {\n    Bar bar\n}\n',
        ], [], false)

        then: 'both files keep their own error'
        result.diagnostics()['file:///w/A.groovy'].any { it.message().contains('NoSuchTypeAnywhere') }
        result.diagnostics()['file:///w/B.groovy'].any { it.message().contains('org.apache.foo.Bar') }
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
    def 'when dropping broken files cascades, the first attempt keeps every file resolved'() {
        given: 'Missing imports a class nobody has; Base uses Missing; a chain of subclasses extends Base'
        Map<String, String> sources = [
            'file:///w/Missing.groovy': 'import does.not.Exist\nclass Missing { Exist e }',
            'file:///w/Base.groovy'   : 'class Base { Missing m; String name() { "base" } }',
            'file:///w/Sub1.groovy'   : 'class Sub1 extends Base { }',
            'file:///w/Sub2.groovy'   : 'class Sub2 extends Sub1 { }',
            'file:///w/Sub3.groovy'   : 'class Sub3 extends Sub2 { String twice() { name() + name() } }',
            'file:///w/User.groovy'   : 'class User { String run(Sub3 s) { def local = s.twice(); local } }',
            'file:///w/Util.groovy'   : 'class Util { static String shout(String s) { s.toUpperCase() } }',
            'file:///w/Caller.groovy' : 'class Caller { String go(String s) { def x = Util.shout(s); x } }',
        ]

        when:
        def result = compiler.compile(sources, [], false)
        def sub3 = result.modules()['file:///w/Sub3.groovy'].classes[0]
        def user = result.modules()['file:///w/User.groovy'].classes[0]

        then: 'only the genuinely broken file is reported, and only it is partial'
        result.unresolvedUris() == ['file:///w/Missing.groovy'] as Set
        result.diagnostics().findAll { k, v -> !v.empty }.keySet() == ['file:///w/Missing.groovy'] as Set
        result.diagnostics()['file:///w/Missing.groovy']*.message().every { it.contains('does.not.Exist') }

        and: 'the subclass chain resolves to the source classes, not to unresolved placeholders'
        sub3.superClass.name == 'Sub2'
        sub3.superClass.superClass.superClass.name == 'Base'
        user.methods.find { it.name == 'run' }.parameters[0].type.name == 'Sub3'

        and: 'method bodies are resolved too: a class-qualified call has a class receiver, not a variable'
        def go = result.modules()['file:///w/Caller.groovy'].classes[0].methods.find { it.name == 'go' }
        def call = go.code.statements[0].expression.rightExpression
        call.objectExpression instanceof org.codehaus.groovy.ast.expr.ClassExpression
        call.objectExpression.type.name == 'Util'
    }

    def 'a syntax error in a base class costs a bounded number of rounds and the subclasses stay resolved'() {
        given: 'Base does not parse; a chain of subclasses extends it; other files are unrelated'
        Map<String, String> sources = [
            'file:///w/Base.groovy'  : 'class Base { String name() { "base" ',
            'file:///w/Sub1.groovy'  : 'class Sub1 extends Base { }',
            'file:///w/Sub2.groovy'  : 'class Sub2 extends Sub1 { }',
            'file:///w/Sub3.groovy'  : 'class Sub3 extends Sub2 { String twice() { name() + name() } }',
            'file:///w/User.groovy'  : 'class User { String run(Sub3 s) { def local = s.twice(); local } }',
            'file:///w/Util.groovy'  : 'class Util { static String shout(String s) { s.toUpperCase() } }',
            'file:///w/Caller.groovy': 'class Caller { String go(String s) { def x = Util.shout(s); x } }',
        ]

        when:
        def result = compiler.compile(sources, [], false)

        then: 'one round fails on syntax, the next resolves everything else, one more shows the cascade'
        result.attempts() <= 3
        result.unresolvedUris().contains('file:///w/Base.groovy')
        !result.diagnostics()['file:///w/Base.groovy'].empty
        result.diagnostics()['file:///w/Util.groovy'].empty
        result.diagnostics()['file:///w/Caller.groovy'].empty

        and: 'the subclass chain and its users keep resolved trees'
        result.modules().keySet().containsAll(['file:///w/Sub2.groovy', 'file:///w/Sub3.groovy', 'file:///w/User.groovy'])
        result.modules()['file:///w/Sub3.groovy'].classes[0].superClass.name == 'Sub2'
        result.modules()['file:///w/User.groovy'].classes[0].methods.find { it.name == 'run' }.parameters[0].type.name == 'Sub3'

        cleanup:
        result?.close()
    }

    def 'the retry budget is a few first rounds, never below the floor'() {
        expect:
        RetryBudget.of(0) == RetryBudget.MINIMUM_NANOS
        RetryBudget.of(-5) == RetryBudget.MINIMUM_NANOS
        RetryBudget.of(10_000_000_000L) == RetryBudget.FACTOR * 10_000_000_000L
    }

    def 'trees stay usable after compiling: classpath types are decompiled lazily'() {
        given:
        def jar = Paths.get(spock.lang.Specification.protectionDomain.codeSource.location.toURI())
        Map<String, String> sources = ['file:///w/T.groovy': 'class T { spock.lang.Specification spec }']

        when:
        def result = compiler.compile(sources, [jar], false)
        def specType = result.modules()['file:///w/T.groovy'].classes[0].getField('spec').type
        def methods = specType.methods

        then:
        noExceptionThrown()
        specType.name == 'spock.lang.Specification'
        !methods.empty
    }
}
