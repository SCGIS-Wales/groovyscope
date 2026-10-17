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

import java.nio.file.Files
import java.nio.file.Path
import org.codenarc.ruleset.RuleSet
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.lint.CodeNarcAnalyzer
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** Linkage errors and stack overflows in the compiler or CodeNarc become diagnostics, not failed requests. */
@Timeout(120)
class CompilerResilienceSpec extends Specification {

    @TempDir Path root

    /** A compiler that fails the way a broken classpath does, a set number of times. */
    static class FailingCompiler extends GroovyCompiler {
        int calls
        int failures
        Throwable failure

        @Override
        CompilationResult compile(Map<String, String> sources, List<Path> classpath, boolean trust) {
            calls++
            if (failures-- > 0) {
                throw failure
            }
            super.compile(sources, classpath, trust)
        }
    }

    private LanguageServices services(GroovyCompiler compiler) {
        Files.createDirectories(root.resolve('src/main/groovy'))
        Files.writeString(root.resolve('src/main/groovy/A.groovy'), 'class A { void run() {} }\n')
        new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), compiler)
    }

    def 'a refresh that fails is recorded on a snapshot instead of failing every later request'() {
        given:
        def compiler = new FailingCompiler(failures: 1, failure: new NoClassDefFoundError('com/example/Missing'))
        def services = services(compiler)
        String uri = WorkspaceModel.toUri(root.resolve('src/main/groovy/A.groovy'))

        when:
        def diagnostics = services.diagnostics()
        services.syncWithDisk()
        def again = services.diagnostics()

        then:
        diagnostics[uri]*.code() == ['engine-crash']
        diagnostics[uri][0].severity() == Diagnostic.Severity.ERROR
        diagnostics[uri][0].message().contains('com/example/Missing')
        again == diagnostics
        compiler.calls == 1
        !services.isDirty()
        services.workspaceSymbols('A').empty

        when: 'the next change recompiles, and this time it works'
        services.markDirty()

        then:
        services.diagnostics()[uri] == []
        compiler.calls == 2
        services.workspaceSymbols('A')*.name().contains('A')
    }

    def 'a stack overflow while compiling becomes a diagnostic'() {
        given:
        def compiler = new FailingCompiler(failures: 1, failure: new StackOverflowError())
        def services = services(compiler)

        expect:
        services.diagnostics().values().flatten()*.code() == ['engine-crash']
    }

    def 'deeply nested code that overflows the parser stack is reported, not thrown'() {
        given:
        String nested = 'def x = ' + ('(' * 20_000) + '1' + (')' * 20_000) + '\n'
        CompilationResult result = null
        Throwable escaped = null
        // A small stack makes the overflow certain without depending on the JVM's default thread stack size.
        Thread thread = new Thread(null, {
            try {
                result = new GroovyCompiler().compile(['file:///Deep.groovy': nested], [], false)
            } catch (Throwable t) {
                escaped = t
            }
        }, 'deep-compile', 256 * 1024)

        when:
        thread.start()
        thread.join(60_000)

        then:
        escaped == null
        result != null
        result.diagnostics()['file:///Deep.groovy']*.code() == ['compiler-crash']
        result.diagnostics()['file:///Deep.groovy'][0].message().contains('StackOverflowError')
    }

    def 'CodeNarc linkage errors and stack overflows become a codenarc-failed warning'() {
        given:
        def analyzer = new CodeNarcAnalyzer()
        RuleSet broken = { throw failure } as RuleSet

        when:
        def found = analyzer.analyze('file:///x.groovy', 'class X {}\n', broken)

        then:
        found*.code() == ['codenarc-failed']
        found[0].severity() == Diagnostic.Severity.WARNING

        where:
        failure << [new NoClassDefFoundError('org/example/Gone'), new StackOverflowError(), new IllegalStateException('bug')]
    }
}
