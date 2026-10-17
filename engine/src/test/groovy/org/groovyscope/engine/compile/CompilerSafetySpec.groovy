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
import javax.tools.ToolProvider
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Compiling untrusted Groovy must not run any of it: no Grape downloads, no @ASTTest closures, and no
 * AST transformations from the workspace classpath unless the workspace is trusted.
 */
class CompilerSafetySpec extends Specification {

    static final String EVIL_TRANSFORM = '''
        package evil;

        import org.codehaus.groovy.ast.ASTNode;
        import org.codehaus.groovy.control.CompilePhase;
        import org.codehaus.groovy.control.SourceUnit;
        import org.codehaus.groovy.transform.ASTTransformation;
        import org.codehaus.groovy.transform.GroovyASTTransformation;

        @GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
        public class EvilTransform implements ASTTransformation {
            static { touch("static-init"); }
            public void visit(ASTNode[] nodes, SourceUnit source) { touch("visit"); }
            static void touch(String what) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("groovyscope.marker")), what);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    '''

    @TempDir
    Path temp

    Path marker
    GroovyCompiler compiler = new GroovyCompiler()

    def setup() {
        marker = temp.resolve('marker.txt')
        System.setProperty('groovyscope.marker', marker.toString())
    }

    def cleanup() {
        System.clearProperty('groovyscope.marker')
    }

    def 'the JVM-wide Grape and ASTTest switches are forced off'() {
        expect:
        System.getProperty('groovy.grape.enable') == 'false'
        System.getProperty('groovy.asttest.enable') == 'false'
    }

    def '@Grab and @ASTTest in workspace code never execute'() {
        given:
        def model = new WorkspaceModel(Fixtures.project('malicious'), EngineConfig.DEFAULTS)

        when:
        def result = compiler.compile(model.sources(), [], false)

        then: 'the ASTTest closure did not run'
        !Files.exists(marker)

        and: 'the harmless file still compiled; the grab-dependent import is simply unresolved'
        result.modules().containsKey(Fixtures.uri('malicious', 'src/main/groovy/evil/Plain.groovy'))
        result.diagnostics()[Fixtures.uri('malicious', 'src/main/groovy/evil/UsesGrab.groovy')]
            .any { it.message().contains('StringUtils') || it.message().contains('Grab') }
    }

    def 'a global AST transformation on the workspace classpath is loaded only when trusted'() {
        given: 'a classpath directory with a registered transformation that leaves a marker when it runs'
        Path classes = compileEvilTransform()
        def model = new WorkspaceModel(Fixtures.project('basic-app'), EngineConfig.DEFAULTS)

        when: 'compiling without trust'
        def untrusted = compiler.compile(model.sources(), [classes], false)

        then:
        !Files.exists(marker)
        untrusted.modules().size() == 6

        when: 'compiling with trust'
        compiler.compile(model.sources(), [classes], true)

        then:
        Files.exists(marker)
        Files.readString(marker) in ['static-init', 'visit']
    }

    private Path compileEvilTransform() {
        Path src = temp.resolve('src/evil/EvilTransform.java')
        Files.createDirectories(src.parent)
        Files.writeString(src, EVIL_TRANSFORM)
        Path out = temp.resolve('classes')
        Files.createDirectories(out.resolve('META-INF/services'))
        Files.writeString(out.resolve('META-INF/services/org.codehaus.groovy.transform.ASTTransformation'),
            'evil.EvilTransform\n')
        def javac = ToolProvider.systemJavaCompiler
        def files = javac.getStandardFileManager(null, null, null)
        def units = files.getJavaFileObjects(src.toFile())
        def options = ['-d', out.toString(), '-classpath', System.getProperty('java.class.path'), '--release', '17']
        boolean ok = javac.getTask(null, files, null, options, null, units).call()
        assert ok : 'javac failed to compile the fixture transformation'
        out
    }
}
