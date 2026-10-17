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
package org.groovyscope.engine.profiles

import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.nio.file.Paths
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.ExceptionMessage
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

/** Profile data from users can be wrong; the engine reports instead of failing or dropping findings. */
class ProfileRobustnessSpec extends Specification {

    static final String DEMO = 'plugins/demo/src/main/groovy/demo/DemoPlugin.groovy'

    Profile profile = EngineConfig.fromJson('{"profiles": [' + ProfilesSpec.profileJson() + ']}').profiles()[0]
    Map<String, String> sources = ['file:///w/A.groovy': 'class A {}', 'file:///w/B.groovy': 'class B {}']

    def 'secret names are plain fragments: regex syntax neither fails nor matches as a pattern'() {
        given:
        def services = ProfilesSpec.services(Fixtures.project('profile-sample'), ProfilesSpec.profileJson(
                secrets: [names: ['[', '(a+)+$', 'tok(en', 'secret', 'password', 'token'], maps: ['outputs', 'params']]))
        String uri = Fixtures.uri('profile-sample', DEMO)

        when:
        def lines = services.quality().practices(uri).findAll { it.diagnostic().code() == 'RP022' }
                .collect { it.diagnostic().range().start().line() + 1 }

        then:
        lines.contains(24)
        lines.contains(25)
    }

    def 'relative emulation jars resolve against the workspace root'() {
        given:
        Profile relative = EngineConfig.fromJson('{"profiles": [' + ProfilesSpec.profileJson(
                target: [groovy: '5.1', emulationJars: ['lib/groovy.jar', '/abs/groovy.jar', '']]) + ']}').profiles()[0]

        expect:
        RemoteCompiler.emulationJars(relative, Paths.get('/work/root')) ==
                [Paths.get('/work/root/lib/groovy.jar'), Paths.get('/abs/groovy.jar')]
        RemoteCompiler.emulationJars(relative, null) == [Paths.get('lib/groovy.jar'), Paths.get('/abs/groovy.jar')]
    }

    def 'a crash of the emulated compiler is reported; compile failures are left to the error collector'() {
        given:
        Map<String, List<Diagnostic>> out = new TreeMap<>()

        when:
        RemoteCompiler.compileFailed(new InvocationTargetException(new IllegalStateException('internal')), sources, profile, out)
        RemoteCompiler.compileFailed(new InvocationTargetException(new MultipleCompilationErrorsException(null)),
                sources, profile, out)

        then:
        out.keySet() == ['file:///w/A.groovy'] as Set
        out['file:///w/A.groovy']*.message() == ['the emulated compiler crashed on demo-runtime: java.lang.IllegalStateException: internal']
        out['file:///w/A.groovy'][0].severity() == Diagnostic.Severity.WARNING
        RemoteCompiler.isCompilationFailure(new CompilationFailedException(0, null))
        !RemoteCompiler.isCompilationFailure(new RuntimeException())
    }

    def 'messages without a matching source, and non-syntax messages, become RP050 warnings'() {
        given:
        Map<String, List<Diagnostic>> out = new TreeMap<>()
        def located = new SyntaxException('unexpected token', 2, 3)
        located.sourceLocator = 'file:///w/B.groovy'
        def unlocated = new SyntaxException('unable to resolve class Gone', 1, 1)
        unlocated.sourceLocator = 'file:///elsewhere/C.groovy'

        def owner = SourceUnit.create('file:///w/B.groovy', 'class B {}')

        def messages = [new SyntaxErrorMessage(located, owner), new SyntaxErrorMessage(unlocated, owner),
                        new SimpleMessage('general error during class generation', null),
                        new ExceptionMessage(new IllegalArgumentException('bad'), false, null)]
        // The message constructor stamps its owner's name on the exception; the runtime may name another file.
        unlocated.sourceLocator = 'file:///elsewhere/C.groovy'

        when:
        messages.each {
            RemoteCompiler.report(it, sources, Diagnostic.Severity.ERROR, profile, out)
        }

        then:
        out['file:///w/B.groovy']*.severity() == [Diagnostic.Severity.ERROR]
        out['file:///w/B.groovy'][0].range().start().line() == 1
        out['file:///w/A.groovy']*.severity().every { it == Diagnostic.Severity.WARNING }
        out['file:///w/A.groovy']*.message() == [
                'does not compile on demo-runtime (Groovy 5.1): unable to resolve class Gone (in file:///elsewhere/C.groovy)',
                'does not compile on demo-runtime (Groovy 5.1): general error during class generation',
                'does not compile on demo-runtime (Groovy 5.1): java.lang.IllegalArgumentException: bad']
        RemoteCompiler.describe(new Object() { String toString() { 'plain' } }) == 'plain'
    }
}
