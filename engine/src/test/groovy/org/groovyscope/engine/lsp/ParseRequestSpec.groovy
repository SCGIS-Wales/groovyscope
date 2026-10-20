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
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.index.Declarations
import spock.lang.Specification
import spock.lang.TempDir

/** {@code groovyscope/parse}: candidate texts parsed on their own, with their syntax errors and declarations. */
class ParseRequestSpec extends Specification {

    static final String SPEC_TEXT = '''\
package demo

import spock.lang.Specification

@Deprecated
class ResourceExecutionPluginTest extends Specification {
    @Deprecated
    String name
    private int count = 1

    ResourceExecutionPluginTest(int start) {
        count = start
    }

    def "ca exemption fails safe"() {
        expect:
        count == 1
    }

    void helper() {
        Runnable task = new Runnable() {
            void run() {}
        }
    }

    static class Inner {
        void go() {}
    }

    enum Colour { RED }
    interface Shape { double area() }
    trait Named { String label() { 'x' } }
    record Point(int x) {}
}
'''

    @TempDir
    Path root

    Map<String, Object> parse(Map<String, Object> params, EngineConfig config = EngineConfig.DEFAULTS) {
        GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ int code -> } as IntConsumer, root, config)
        Map<String, Object> request = [root: root.toString()] + params
        return server.parse(request).get(60, TimeUnit.SECONDS)
    }

    def 'a text that parses lists its classes, members, Spock features, inner classes and fields in source order'() {
        when:
        def response = parse(texts: [SPEC_TEXT], declarations: true, name: 'ResourceExecutionPluginTest.groovy')
        def result = response.results[0]
        def byName = result.declarations.collectEntries { [(it.name): it] }

        then:
        response.results.size() == 1
        result.ok
        result.errors == []
        result.declarations*.name == [
            'ResourceExecutionPluginTest',
            'ResourceExecutionPluginTest.name',
            'ResourceExecutionPluginTest.count',
            'ResourceExecutionPluginTest.<init>',
            'ResourceExecutionPluginTest.ca exemption fails safe',
            'ResourceExecutionPluginTest.helper',
            'ResourceExecutionPluginTest.Inner',
            'ResourceExecutionPluginTest.Inner.go',
            'ResourceExecutionPluginTest.Colour',
            'ResourceExecutionPluginTest.Colour.RED',
            'ResourceExecutionPluginTest.Shape',
            'ResourceExecutionPluginTest.Shape.area',
            'ResourceExecutionPluginTest.Named',
            'ResourceExecutionPluginTest.Named.label',
            'ResourceExecutionPluginTest.Point',
            'ResourceExecutionPluginTest.Point.x',
        ]
        byName['ResourceExecutionPluginTest'] == [name: 'ResourceExecutionPluginTest', kind: 'class', line: 5, endLine: 34]
        byName['ResourceExecutionPluginTest.name'] == [name: 'ResourceExecutionPluginTest.name', kind: 'field', line: 7, endLine: 8]
        byName['ResourceExecutionPluginTest.count'].kind == 'field'
        byName['ResourceExecutionPluginTest.<init>'] == [name: 'ResourceExecutionPluginTest.<init>', kind: 'constructor', line: 11, endLine: 13]
        byName['ResourceExecutionPluginTest.ca exemption fails safe'] == [name: 'ResourceExecutionPluginTest.ca exemption fails safe', kind: 'method', line: 15, endLine: 18]
        byName['ResourceExecutionPluginTest.Inner'] == [name: 'ResourceExecutionPluginTest.Inner', kind: 'class', line: 26, endLine: 28]
        byName['ResourceExecutionPluginTest.Point.x'].kind == 'field'
        !result.declarations*.name.any { it.contains('run') || it.contains('$') }
    }

    def 'a script keeps the methods it declares under the class named after the last segment of the name'() {
        when:
        def result = parse(texts: ["println 'hi'\ndef helper(int x) {\n    x + 1\n}\n"], declarations: true,
            name: 'scripts/nested/Deploy.groovy').results[0]

        then: 'the script class covers the whole script, not only its statements'
        result.ok
        result.declarations == [
            [name: 'Deploy', kind: 'class', line: 1, endLine: 4],
            [name: 'Deploy.helper', kind: 'method', line: 2, endLine: 4],
        ]

        when:
        def methodFirst = parse(texts: ["def helper() {\n}\n\nprintln helper()\n"], declarations: true).results[0]

        then:
        methodFirst.declarations == [
            [name: 'Candidate', kind: 'class', line: 1, endLine: 4],
            [name: 'Candidate.helper', kind: 'method', line: 1, endLine: 2],
        ]

        when:
        def onlyMethods = parse(texts: ["def helper() {\n}\n"], declarations: true).results[0]

        then:
        onlyMethods.declarations*.name.contains('Candidate.helper')
        !onlyMethods.declarations*.name.any { it.endsWith('.run') || it.endsWith('.main') }
    }

    def 'an unbalanced brace does not parse and reports where'() {
        when:
        def result = parse(texts: ["class A {\n    void a() {\n        println 'x'\n}\n"], declarations: true).results[0]

        then:
        !result.ok
        result.errors.size() >= 1
        result.errors.every { it.line >= 1 && it.column >= 1 && it.message }
        !result.errors[0].message.contains('Candidate.groovy')
        result.declarations == []
    }

    def 'git conflict markers do not parse'() {
        given:
        def text = '''\
class A {
<<<<<<< HEAD
    void a() {}
=======
    void b() {}
>>>>>>> feat/x
}
'''

        when:
        def result = parse(texts: [text]).results[0]

        then:
        !result.ok
        result.errors[0].line == 2
        result.errors[0].column >= 1
        result.errors[0].message.contains('<')
    }

    def 'results keep the order of the texts and declarations appear only when requested'() {
        when:
        def response = parse(texts: ['class Good {}', 'class Bad {', 'class AlsoGood { int x }'])

        then:
        response.results*.ok == [true, false, true]
        response.results.every { !it.containsKey('declarations') }
        response.results.every { it.keySet() as List == ['ok', 'errors'] }

        when:
        def requested = parse(texts: ['class Good {}', 'class AlsoGood { int x }'], declarations: true)

        then:
        requested.results*.declarations*.name == [['Good'], ['AlsoGood', 'AlsoGood.x']]
    }

    def 'no texts answers no results'() {
        expect:
        parse(texts: []).results == []
    }

    def 'more than sixteen texts are refused, naming the limit'() {
        when:
        parse(texts: ['class A {}'] * 17)

        then:
        def failure = thrown(ExecutionException)
        def error = (failure.cause as ResponseErrorException).responseError
        error.code == ResponseErrorCode.InvalidRequest.value
        error.message.contains('16')
    }

    def 'texts must be a list of strings'() {
        when:
        parse(texts: value)

        then:
        def failure = thrown(ExecutionException)
        (failure.cause as ResponseErrorException).responseError.code == ResponseErrorCode.InvalidParams.value

        where:
        value << [null, 'class A {}', ['class A {}', 3]]
    }

    def 'a request without params is refused'() {
        given:
        def server = new GroovyscopeLanguageServer({ int code -> } as IntConsumer, root, EngineConfig.DEFAULTS)

        when:
        server.parse(null).get(60, TimeUnit.SECONDS)

        then:
        def failure = thrown(ExecutionException)
        (failure.cause as ResponseErrorException).responseError.code == ResponseErrorCode.InvalidParams.value
    }

    def 'a text over workspace.maxFileBytes in UTF-8 bytes is answered as too large, not parsed'() {
        given:
        def config = EngineConfig.DEFAULTS.withMaxFileBytes(64)
        def fits = '// ' + ('\u00e9' * 30) // 63 bytes
        def over = '// ' + ('\u00e9' * 31) // 34 characters, 65 bytes

        when:
        def results = parse([texts: [fits, over], declarations: true], config).results

        then:
        results[0] == [ok: true, errors: [], declarations: []]
        results[1] == [ok: false, errors: [[line: 1, column: 1, message: 'too large to parse']], declarations: []]
    }

    def 'at most twenty errors are reported per text'() {
        given:
        GroovyCompiler.Parsed many = new GroovyCompiler.Parsed(Optional.empty(), false,
            (1..25).collect { new org.groovyscope.engine.model.Diagnostic('x', org.groovyscope.engine.model.Range.of(it, 0, it, 1),
                org.groovyscope.engine.model.Diagnostic.Severity.ERROR, 'groovyc', 'syntax', "error $it") })

        when:
        def result = QualityRequests.parsed(many, false)

        then:
        !result.ok
        result.errors.size() == QualityRequests.MAX_PARSE_ERRORS
        result.errors[0] == [line: 2, column: 1, message: 'error 1']
    }

    def 'the virtual name is the last path segment, or the default'() {
        expect:
        QualityRequests.virtualName(name) == expected

        where:
        name                         | expected
        null                         | 'Candidate.groovy'
        '  '                         | 'Candidate.groovy'
        'dir/'                       | 'Candidate.groovy'
        'Plain.groovy'               | 'Plain.groovy'
        'a/b/Spec.groovy'            | 'Spec.groovy'
        'C:\\work\\Build.groovy'     | 'Build.groovy'
    }

    def 'UTF-8 length counts one to four bytes per code point'() {
        expect:
        QualityRequests.utf8Length(text) == expected

        where:
        text                      | expected
        ''                        | 0
        'abc'                     | 3
        '\u00e9'                  | 2
        '\u20ac'                  | 3
        new String(Character.toChars(0x1F600)) | 4
        '\ud800'                  | 3
    }

    def 'the compiler parse keeps the tree of a text that parses and none for one that does not'() {
        given:
        def compiler = new GroovyCompiler()

        when:
        def good = compiler.parse('Good.groovy', 'class Good { void a() {} }')
        def bad = compiler.parse('Bad.groovy', 'class Bad {')

        then:
        good.ok() && good.module().isPresent() && good.errors().isEmpty()
        !bad.ok() && !bad.errors().isEmpty()
        compiler.parses('Good.groovy', 'class Good {}')
        !compiler.parses('Bad.groovy', 'class Bad {')
        compiler.openLoaderCount() == 0
        Declarations.of(null) == []
    }

    def 'nothing is written into the workspace root'() {
        when:
        parse(texts: [SPEC_TEXT], declarations: true)

        then:
        Files.list(root).withCloseable { it.toList() }.isEmpty()
    }
}
