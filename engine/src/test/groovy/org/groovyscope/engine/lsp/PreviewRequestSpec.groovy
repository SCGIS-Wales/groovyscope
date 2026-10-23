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
package org.groovyscope.engine.lsp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** {@code groovyscope/preview}: findings of candidate texts as if saved, nothing written, the snapshot untouched. */
@Timeout(180)
class PreviewRequestSpec extends Specification {

    static final String PROJECT = 'edit-gate'
    static final String SUBJECT = 'src/main/groovy/demo/Subject.groovy'
    static final String TYPES = 'src/main/groovy/demo/Types.groovy'
    static final String BROKEN_SUBJECT = '''\
package demo

class Subject {

    int square(int value) {
        Missing helper = new Missing()
        return value * value
    }
}
'''

    @Shared GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ int code -> } as IntConsumer,
            Fixtures.project(PROJECT), EngineConfig.DEFAULTS)
    @Shared String root = Fixtures.project(PROJECT).toString()

    @TempDir Path temp

    Map request(String method, Map params, GroovyscopeLanguageServer on = server, String at = root) {
        on."$method"([root: at] + params).get(120, TimeUnit.SECONDS) as Map
    }

    static String uri(String relative) {
        Fixtures.uri(PROJECT, relative)
    }

    static ResponseErrorException failure(Closure work) {
        try {
            work()
        } catch (ExecutionException e) {
            return e.cause as ResponseErrorException
        }
        throw new AssertionError('expected the request to fail')
    }

    static List<Map> errors(List diagnostics) {
        diagnostics.findAll { it.severity == DiagnosticSeverity.Error }.collect { [message: it.message.getLeft(), line: it.range.start.line] }
    }

    def 'a candidate with a compile error reports it, while the file on disk and the snapshot stay clean'() {
        given:
        String before = Fixtures.text(PROJECT, SUBJECT)

        when:
        def response = request('preview', [texts: [[uri: uri(SUBJECT), text: BROKEN_SUBJECT]]])
        def result = response.results[uri(SUBJECT)]

        then:
        response.keySet() as List == ['results', 'elapsedMs']
        response.elapsedMs instanceof Number
        result.keySet() as List == ['syntax', 'diagnostics']
        result.syntax == []
        errors(result.diagnostics).any { it.message.contains('Missing') && it.line == 5 }

        when: 'the document is checked afterwards'
        def check = request('check', [uris: [uri(SUBJECT)], include: ['compile']])

        then: 'it is still clean and unchanged on disk'
        check.diagnostics[uri(SUBJECT)] == []
        Fixtures.text(PROJECT, SUBJECT) == before
    }

    def 'a candidate the same as the file on disk reports what check reports, fixable flags included'() {
        given:
        String text = Fixtures.text(PROJECT, TYPES)
        Closure shape = { List diagnostics ->
            diagnostics.collect { [it.code?.getLeft(), it.range.start.line, it.range.start.character, it.message.getLeft(), it.data?.toString()] }
        }

        when:
        def preview = request('preview', [texts: [[uri: uri(TYPES), text: text]]]).results[uri(TYPES)]
        def check = request('check', [uris: [uri(TYPES)], include: ['compile', 'lint', 'practices']]).diagnostics[uri(TYPES)]

        then:
        !check.isEmpty()
        shape(preview.diagnostics) == shape(check)
    }

    def 'the type preview runs with practices or types, and types adds the errors of a static class the compile misses'() {
        given:
        String text = Fixtures.text(PROJECT, TYPES)
        String typed = 'package demo\n\n@groovy.transform.CompileStatic\nclass Subject {\n\n    int square(int value) {\n        return \'x\'\n    }\n}\n'

        when:
        def types = request('preview', [texts: [[uri: uri(TYPES), text: text]], include: ['types']]).results[uri(TYPES)].diagnostics
        def plain = request('preview', [texts: [[uri: uri(TYPES), text: text]]]).results[uri(TYPES)].diagnostics
        def lint = request('preview', [texts: [[uri: uri(TYPES), text: text]], include: ['lint', 'compile']]).results[uri(TYPES)].diagnostics
        def compiled = request('preview', [texts: [[uri: uri(SUBJECT), text: typed]], include: ['compile']]).results[uri(SUBJECT)].diagnostics
        def checked = request('preview', [texts: [[uri: uri(SUBJECT), text: typed]], include: ['compile', 'types']]).results[uri(SUBJECT)].diagnostics
        def check = request('check', [uris: [uri(TYPES)], include: ['types']]).diagnostics[uri(TYPES)]

        then:
        types*.code*.getLeft() == ['GS077', 'GS077']
        types*.range*.start*.line == [5, 17]
        types[0].message.getLeft() == 'would not type check: Cannot call java.lang.String#compareTo(java.lang.String) with arguments [int]'
        types.every { it.severity == DiagnosticSeverity.Warning && it.data.get('confidence').asString == 'INFERRED' }
        plain.count { it.code.getLeft() == 'GS077' } == 2
        !lint.any { it.code.getLeft() == 'GS077' }
        plain.find { it.code.getLeft() == 'GS070' }.data.get('confidence').asString == 'STATIC'
        errors(compiled) == []
        errors(checked).any { it.message.contains('Cannot return value of type java.lang.String') && it.line == 6 }
        check*.code*.getLeft() == ['GS077', 'GS077']
    }

    def 'a candidate for a new file inside the workspace is compiled against the rest and nothing is created'() {
        given:
        String fresh = 'src/main/groovy/demo/Fresh.groovy'
        Path file = Fixtures.project(PROJECT).resolve(fresh)

        when:
        def response = request('preview', [texts: [
            [uri: uri(fresh), text: 'package demo\n\nclass Fresh {\n\n    int twice(int value) {\n        return new Subject().square(value) * 2\n    }\n}\n'],
            [uri: Fixtures.project(PROJECT).resolve('src/main/groovy/demo/Other.groovy').toUri().toString(),
             text: 'package demo\n\nclass Other {\n\n    Unknown make() {\n        return null\n    }\n}\n'],
        ]])

        then:
        errors(response.results[uri(fresh)].diagnostics) == []
        errors(response.results.values()[1].diagnostics).any { it.message.contains('Unknown') }
        !Files.exists(file)
    }

    def 'lint and practice findings of the candidate are reported, and include narrows them'() {
        given:
        String text = 'package demo\n\nclass Subject {\n\n    int square(int value) {\n        println value\n        return value * value\n    }\n\n    void twice(Closure body) {\n        body()\n        body()\n    }\n}\n'

        when:
        def all = request('preview', [texts: [[uri: uri(SUBJECT), text: text]]]).results[uri(SUBJECT)].diagnostics
        def lintOnly = request('preview', [texts: [[uri: uri(SUBJECT), text: text]], include: ['lint']]).results[uri(SUBJECT)].diagnostics
        def compileOnly = request('preview', [texts: [[uri: uri(SUBJECT), text: text]], include: ['compile']]).results[uri(SUBJECT)].diagnostics

        then:
        all.any { it.code.getLeft() == 'Println' && it.range.start.line == 5 }
        all.any { it.code.getLeft() == 'GS017' && it.source != 'codenarc' }
        all*.range*.start*.line == all*.range*.start*.line.sort(false)
        lintOnly.any { it.code.getLeft() == 'Println' }
        lintOnly.every { it.source == 'codenarc' }
        compileOnly.every { it.source == 'groovyc' }
        !compileOnly.any { it.code.getLeft() == 'Println' }
    }

    def 'a syntax error is reported alone: nothing is compiled, linted or checked for that text'() {
        given:
        String broken = 'package demo\n\nclass Subject {\n    int square(int value) {\n        println value\n'

        when:
        def response = request('preview', [texts: [[uri: uri(SUBJECT), text: broken],
                                                [uri: uri(TYPES), text: Fixtures.text(PROJECT, TYPES)]]])

        then:
        response.results[uri(SUBJECT)].syntax.size() >= 1
        response.results[uri(SUBJECT)].syntax.every { it.line >= 1 && it.column >= 1 && it.message && !it.message.contains('Subject.groovy') }
        response.results[uri(SUBJECT)].diagnostics == []
        !response.results[uri(TYPES)].diagnostics.isEmpty()
    }

    def 'a text over workspace.maxFileBytes gets one syntax error and nothing else'() {
        given:
        def limited = new GroovyscopeLanguageServer({ int code -> } as IntConsumer, Fixtures.project(PROJECT),
                EngineConfig.DEFAULTS.withMaxFileBytes(2048))
        String big = 'package demo\n\nclass Subject {\n' + ('    // éééééééééé\n' * 100) + '}\n'

        when:
        def result = request('preview', [texts: [[uri: uri(SUBJECT), text: big]]], limited).results[uri(SUBJECT)]

        then:
        result == [syntax: [[line: 1, column: 1, message: 'too large to preview']], diagnostics: []]
    }

    def 'a workspace never compiled is compiled first, then the candidate over it'() {
        given:
        Path source = temp.resolve('src/main/groovy/demo')
        Files.createDirectories(source)
        Files.writeString(source.resolve('Base.groovy'), 'package demo\n\nclass Base {\n\n    int one() {\n        return 1\n    }\n}\n')
        def fresh = new GroovyscopeLanguageServer({ int code -> } as IntConsumer, temp, EngineConfig.DEFAULTS)
        String user = source.resolve('User.groovy').toUri().toString()

        when:
        def result = request('preview', [texts: [[uri: user, text: 'package demo\n\nclass User {\n\n    int two() {\n        return new Base().one() + 1\n    }\n}\n']]],
                fresh, temp.toString()).results[user]

        then:
        fresh.services().hasCompiled()
        errors(result.diagnostics) == []
        !Files.exists(source.resolve('User.groovy'))
    }

    def 'a Gradle build script and a Java file are previewed without the compile'() {
        given:
        String gradle = uri('build.gradle')
        String java = uri('src/main/java/demo/Plain.java')

        when:
        def response = request('preview', [texts: [[uri: gradle, text: Fixtures.text(PROJECT, 'build.gradle')],
                                                [uri: java, text: 'package demo;\n\npublic class Plain {\n}\n']]])

        then:
        response.results[gradle].syntax == []
        response.results[gradle].diagnostics.every { it.source != 'groovyc' }
        response.results[java] == [syntax: [], diagnostics: []]
    }

    def 'more than sixteen texts are refused, naming the limit'() {
        when:
        def error = failure { request('preview', [texts: [[uri: uri(SUBJECT), text: 'class A {}']] * 17]) }

        then:
        error.responseError.code == ResponseErrorCode.InvalidRequest.value
        error.responseError.message.contains('16')
    }

    def 'a uri outside the workspace, malformed texts and an unknown include are invalid'() {
        expect:
        failure { request('preview', params) }.responseError.code == ResponseErrorCode.InvalidParams.value

        where:
        params << [
            [texts: [[uri: Fixtures.uri('basic-app', 'src/main/groovy/Elsewhere.groovy'), text: 'class A {}']]],
            [texts: 'class A {}'],
            [texts: [[uri: uri(SUBJECT)]]],
            [texts: [[uri: uri(SUBJECT), text: 'class A {}']], include: ['typecheck']],
        ]
    }

    def 'a request without params is invalid'() {
        when:
        server.preview(null).get(60, TimeUnit.SECONDS)

        then:
        def failure = thrown(ExecutionException)
        (failure.cause as ResponseErrorException).responseError.code == ResponseErrorCode.InvalidParams.value
    }
}
