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

import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer
import spock.lang.Shared
import spock.lang.Specification

/** The @CompileStatic verdict is per class, and only a document that was type-checked gets an annotation (1.5, 1.12). */
class TypingVerdictSpec extends Specification {

    @Shared
    LanguageServices services = Fixtures.services('typing-sample')

    private static String uri(String name) {
        Fixtures.uri('typing-sample', "src/main/groovy/sample/${name}")
    }

    def 'each class of a document gets its own verdict'() {
        when:
        def result = services.quality().fix([uri('Mixed.groovy')], ['CompileStatic'] as Set)
        String fixed = PracticeFixes.apply(Fixtures.text('typing-sample', 'src/main/groovy/sample/Mixed.groovy'),
                result.edits()[uri('Mixed.groovy')]).text()

        then:
        fixed.contains('@CompileStatic\nclass Good')
        fixed.contains('@CompileDynamic // TODO justify why this class stays dynamic (it does not type-check)\nclass Bad')
        result.left()[uri('Mixed.groovy')] == [CompileStatic: 1]
    }

    def 'a class right after the package line is judged on the annotated text, imports first'() {
        when:
        def result = services.quality().fix([uri('Tight.groovy')], ['CompileStatic'] as Set)
        String fixed = PracticeFixes.apply(Fixtures.text('typing-sample', 'src/main/groovy/sample/Tight.groovy'),
                result.edits()[uri('Tight.groovy')]).text()

        then:
        fixed.startsWith('package sample\n\nimport groovy.transform.CompileStatic\n@CompileStatic\nclass Tight')
        result.left()[uri('Tight.groovy')] == null
    }

    def 'an error inside an anonymous class counts against the class that holds it'() {
        when:
        def result = services.quality().fix([uri('Nested.groovy')], ['CompileStatic'] as Set)
        String fixed = PracticeFixes.apply(Fixtures.text('typing-sample', 'src/main/groovy/sample/Nested.groovy'),
                result.edits()[uri('Nested.groovy')]).text()

        then:
        !fixed.contains('@CompileStatic')
        fixed.contains('@CompileDynamic // TODO justify why this class stays dynamic (it does not type-check)\nclass Outer')
    }

    def 'a document the type checker could not check gets no annotation'() {
        when:
        def result = services.quality().fix([uri('Unresolved.groovy')], ['CompileStatic'] as Set)

        then:
        result.edits()[uri('Unresolved.groovy')] == null || !result.edits()[uri('Unresolved.groovy')]*.newText()
                .any { it.contains('@Compile') }
        result.left()[uri('Unresolved.groovy')] == [CompileStatic: 1]
    }

    def 'a checker that cannot load is a failed check, never a clean one'() {
        given:
        String doc = 'file:///w/Typed.groovy'
        String text = '@groovy.transform.CompileStatic\nclass Typed {\n    int n() {\n        "x".noSuchMethod()\n    }\n}\n'

        when:
        def broken = new TypeCheckAnalyzer().check([(doc): text], [], false, ['no.such.Checker'], [] as Set)
        def plain = new TypeCheckAnalyzer().check([(doc): text], [], false, [], [] as Set)

        then:
        broken.status()[doc] == TypeCheckAnalyzer.Status.CRASHED
        broken.diagnostics()[doc]*.code() == ['typecheck-failed']
        broken.diagnostics()[doc][0].message().contains('no.such.Checker')
        plain.status()[doc] == TypeCheckAnalyzer.Status.CHECKED
        plain.diagnostics()[doc]*.code() == ['typecheck']
    }

    def 'only a message about a checker extension counts as a checker that did not load'() {
        given:
        def collector = { String text ->
            def errors = new org.codehaus.groovy.control.ErrorCollector(new org.codehaus.groovy.control.CompilerConfiguration())
            errors.addErrorAndContinue(new org.codehaus.groovy.control.messages.SimpleMessage(text, null))
            errors
        }
        List<String> extensions = ['groovy.typecheckers.NullChecker(strict: true)']

        expect:
        TypeCheckAnalyzer.unloadedExtension(collector('Static type checking extension \'x.Y\' was not found on the classpath.'),
                extensions) != null
        TypeCheckAnalyzer.unloadedExtension(collector('could not load groovy.typecheckers.NullChecker'), extensions) != null
        TypeCheckAnalyzer.unloadedExtension(collector('Invalid duplicate class definition of class Foo'), extensions) == null
    }
}
