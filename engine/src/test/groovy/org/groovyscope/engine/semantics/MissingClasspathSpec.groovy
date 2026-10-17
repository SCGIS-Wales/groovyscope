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
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.groovyscope.engine.model.ImpactReport
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

/** A classpath jar whose classes mention a class missing from the classpath (JUnit 4 without Hamcrest). */
class MissingClasspathSpec extends Specification {

    @TempDir
    Path dir

    private Path jarWithoutDependency() {
        Path src = Files.createDirectories(dir.resolve('src/lib'))
        Files.writeString(src.resolve('Dep.java'), 'package lib; public class Dep { }')
        Files.writeString(src.resolve('Api.java'), 'package lib; public class Api { public static Dep make() { return new Dep(); } public static String hello() { return "hi"; } }')
        Path classes = Files.createDirectories(dir.resolve('classes'))
        int status = ToolProvider.systemJavaCompiler.run(null, null, null, '-d', classes.toString(),
                src.resolve('Dep.java').toString(), src.resolve('Api.java').toString())
        assert status == 0
        Path jar = dir.resolve('api-only.jar')
        new JarOutputStream(Files.newOutputStream(jar)).withCloseable { out ->
            out.putNextEntry(new JarEntry('lib/Api.class'))
            out.write(Files.readAllBytes(classes.resolve('lib/Api.class')))
            out.closeEntry()
        }
        jar
    }

    def 'members that mention missing classes leave calls unresolved instead of failing the request'() {
        given:
        Path workspace = Files.createDirectories(dir.resolve('ws/src/main/groovy'))
        Files.writeString(workspace.resolve('User.groovy'), 'class User {\n    String go() {\n        lib.Api.hello()\n    }\n    String twice() { go() + go() }\n}\n')
        def config = new EngineConfig(['src/main/groovy'], [jarWithoutDependency().toString()], [], false)
        def services = new LanguageServices(new WorkspaceModel(dir.resolve('ws'), config), new GroovyCompiler())

        when:
        def graph = services.callGraph()
        def impact = services.impactOf('User.go', ImpactReport.Change.SIGNATURE)
        def api = services.snapshot().index().entries(workspace.resolve('User.groovy').toUri().toString())
                .find { it.node() instanceof org.codehaus.groovy.ast.ClassNode && it.node().name == 'lib.Api' }

        then:
        noExceptionThrown()
        graph != null
        impact.size() == 1
        impact[0].directCallers().size() == 2
        api == null || Members.methodsNamed(api.node(), 'hello') != null
        Members.readable({ throw new NoClassDefFoundError('missing') }).empty
    }
}
