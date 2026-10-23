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
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** {@code groovyscope/edit}: one declaration edited as an object, answered as a new text and an exact replacement. */
@Timeout(120)
class EditRequestSpec extends Specification {

    static final String PROJECT = 'edit-gate'
    static final String VERBOSE = 'src/main/groovy/demo/Verbose.groovy'
    static final String SUBJECT = 'src/main/groovy/demo/Subject.groovy'
    static final String ZERO = 'int zero() {\n    return 0\n}'
    static final List<String> ZERO_LINES = ['    int zero() {', '        return 0', '    }']
    static final String SHAPES = '''\
package demo

class Shapes {

    Shapes() {
    }

    Shapes(int size) {
        this.size = size
    }

    int size

    int area(int side) {
        return side * side
    }

    int area(int width, int height) {
        return width * height
    }

    static class Inner {
        void go() {}
    }
}
'''

    @Shared GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ int code -> } as IntConsumer,
            Fixtures.project(PROJECT), EngineConfig.DEFAULTS)
    @Shared String root = Fixtures.project(PROJECT).toString()

    @TempDir Path temp

    Map edit(Map params) {
        server.edit([root: root] + params).get(60, TimeUnit.SECONDS) as Map
    }

    /** Writes {@code text} as {@code name} into a temporary workspace and edits it there. */
    Map editIn(String name, String text, Map params) {
        Path file = temp.resolve('src/main/groovy/demo').resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        def local = new GroovyscopeLanguageServer({ int code -> } as IntConsumer, temp, EngineConfig.DEFAULTS)
        Map result = local.edit([root: temp.toString(), uri: file.toUri().toString()] + params).get(60, TimeUnit.SECONDS) as Map
        assert Files.readString(file) == text: 'edit writes nothing'
        exact(text, result)
        return result
    }

    /** The error an edit in a temporary workspace answers with. */
    ResponseErrorException refusedIn(String name, String text, Map params) {
        try {
            editIn(name, text, params)
        } catch (ExecutionException e) {
            return e.cause as ResponseErrorException
        }
        throw new AssertionError('expected the edit to be refused')
    }

    ResponseErrorException refused(Map params) {
        try {
            edit(params)
        } catch (ExecutionException e) {
            return e.cause as ResponseErrorException
        }
        throw new AssertionError('expected the edit to be refused')
    }

    static String uri(String relative) {
        Fixtures.uri(PROJECT, relative)
    }

    static List<String> linesOf(String relative) {
        Fixtures.text(PROJECT, relative).split('\n', -1).toList().dropRight(1)
    }

    static String joined(List<String> lines) {
        lines.join('\n') + '\n'
    }

    /** {@code oldStr} occurs once in the original, and replacing it with {@code newStr} gives the new text. */
    static void exact(String original, Map result) {
        assert !result.oldStr.isEmpty()
        int first = original.indexOf(result.oldStr)
        assert first >= 0
        assert original.indexOf(result.oldStr, first + 1) < 0
        assert original.replace(result.oldStr, result.newStr) == result.text
    }

    Map onFixture(String relative, Map params) {
        Map result = edit([uri: uri(relative)] + params)
        exact(Fixtures.text(PROJECT, relative), result)
        result
    }

    def 'replace takes the Javadoc above current() along and indents the new text as the declaration'() {
        given:
        def lines = linesOf(VERBOSE)

        when:
        def result = onFixture(VERBOSE, [symbol: 'current', operation: 'replace',
                text: '/** The counter. */\nprivate int current() {\n    counter\n}\n'])

        then:
        result.keySet() as List == ['text', 'oldStr', 'newStr', 'startLine', 'endLine', 'symbol']
        result.symbol == 'Verbose.current'
        result.startLine == 14
        result.endLine == 20
        result.text == joined(lines[0..12] + ['    /** The counter. */', '    private int current() {', '        counter', '    }']
                + lines[20..-1])
        result.oldStr.contains('Returns the counter.')
    }

    def 'replace_body replaces the statements of countWords, indented one level below the method'() {
        given:
        def lines = linesOf(VERBOSE)

        when:
        def result = onFixture(VERBOSE, [symbol: 'Verbose.countWords', operation: 'replace_body',
                text: 'if (text == null) {\n    return 0\n}\nreturn text.trim().split(/\\s+/).length'])

        then:
        result.symbol == 'Verbose.countWords'
        result.startLine == 22
        result.endLine == 39
        result.text == joined(lines[0..21] + ['        if (text == null) {', '            return 0', '        }',
                '        return text.trim().split(/\\s+/).length'] + lines[38..-1])
    }

    def 'insert_before and insert_after add the text one blank line away from the declaration, dedented first'() {
        given:
        def lines = linesOf(VERBOSE)

        when:
        def before = onFixture(VERBOSE, [symbol: 'countWords', operation: 'insert_before',
                text: '        int zero() {\n            return 0\n        }'])
        def after = onFixture(VERBOSE, [symbol: 'current', operation: 'insert_after', text: ZERO])

        then:
        before.text == joined(lines[0..20] + ZERO_LINES + [''] + lines[21..-1])
        before.startLine == 22
        before.endLine == 22
        after.text == joined(lines[0..19] + [''] + ZERO_LINES + lines[20..-1])
        after.startLine == 20
        after.endLine == 20
    }

    def 'insert_before goes above the comments and annotations of the declaration'() {
        given:
        def lines = linesOf(VERBOSE)

        when:
        def result = onFixture(VERBOSE, [symbol: 'Verbose', operation: 'insert_before', text: '// first'])

        then:
        result.text == joined(lines[0..3] + ['// first', ''] + lines[4..-1])
        result.startLine == 5
    }

    def 'delete removes the declaration with its Javadoc and one adjacent blank line'() {
        given:
        def lines = linesOf(VERBOSE)

        when:
        def current = onFixture(VERBOSE, [symbol: 'current', operation: 'delete'])
        def countWords = onFixture(VERBOSE, [symbol: 'countWords', operation: 'delete'])

        then: 'the blank line after it goes, or the one before when the class closes next'
        current.text == joined(lines[0..12] + lines[21..-1])
        current.startLine == 14
        current.endLine == 21
        countWords.text == joined(lines[0..19] + lines[39..-1])
        countWords.startLine == 21
        countWords.endLine == 39
    }

    def 'add_member puts the member at the end, the start or after a named member, one blank line apart'() {
        given:
        def lines = linesOf(VERBOSE)

        when:
        def end = onFixture(VERBOSE, [symbol: 'Verbose', operation: 'add_member', text: ZERO])
        def start = onFixture(VERBOSE, [symbol: 'Verbose', operation: 'add_member', text: ZERO, position: 'start'])
        def afterCounter = onFixture(VERBOSE, [symbol: 'Verbose', operation: 'add_member', text: ZERO,
                position: 'after:counter'])

        then:
        end.text == joined(lines[0..38] + [''] + ZERO_LINES + lines[39..-1])
        end.startLine == 40
        start.text == joined(lines[0..5] + [''] + ZERO_LINES + lines[6..-1])
        start.startLine == 6
        afterCounter.text == joined(lines[0..11] + [''] + ZERO_LINES + lines[12..-1])
        afterCounter.startLine == 12
        afterCounter.symbol == 'Verbose'
    }

    def 'add_import keeps the imports sorted, puts static ones after, and refuses a duplicate'() {
        given:
        def lines = linesOf(VERBOSE)

        when:
        def later = onFixture(VERBOSE, [operation: 'add_import', text: 'java.util.List'])
        def earlier = onFixture(VERBOSE, [operation: 'add_import', text: 'import groovy.lang.Lazy;', symbol: ''])
        def statics = onFixture(VERBOSE, [operation: 'add_import', text: 'import static java.lang.Math.max'])

        then:
        later.text == joined(lines[0..2] + ['import java.util.List'] + lines[3..-1])
        later.startLine == 3
        later.symbol == 'import java.util.List'
        earlier.text == joined(lines[0..1] + ['import groovy.lang.Lazy'] + lines[2..-1])
        statics.text == joined(lines[0..2] + ['', 'import static java.lang.Math.max'] + lines[3..-1])

        when:
        def duplicate = refused([uri: uri(VERBOSE), operation: 'add_import', text: 'import  groovy.transform.CompileStatic'])

        then:
        duplicate.responseError.code == ResponseErrorCode.InvalidParams.value
        duplicate.responseError.message.contains('already imported')
    }

    def 'add_import into a file without imports goes after the package line'() {
        given:
        def lines = linesOf(SUBJECT)

        when:
        def result = onFixture(SUBJECT, [operation: 'add_import', text: 'java.util.List'])

        then:
        result.text == joined(lines[0..0] + ['', 'import java.util.List'] + lines[1..-1])
        result.startLine == 1
    }

    def 'add_import sorts among several imports and handles a file without a package'() {
        given:
        String text = '// header\n\nimport b.B\nimport d.D\n\nimport static z.Z.z\n\nclass Imports {\n}\n'
        String bare = '/*\n * header\n */\nclass Bare {\n}\n'

        expect:
        editIn('Imports.groovy', text, [operation: 'add_import', text: 'c.C']).text ==
                '// header\n\nimport b.B\nimport c.C\nimport d.D\n\nimport static z.Z.z\n\nclass Imports {\n}\n'
        editIn('Imports.groovy', text, [operation: 'add_import', text: 'static a.A.a']).text ==
                '// header\n\nimport b.B\nimport d.D\n\nimport static a.A.a\nimport static z.Z.z\n\nclass Imports {\n}\n'
        editIn('Statics.groovy', 'import static z.Z.z\n\nclass Statics {\n}\n', [operation: 'add_import', text: 'b.B']).text ==
                'import b.B\n\nimport static z.Z.z\n\nclass Statics {\n}\n'
        editIn('Bare.groovy', bare, [operation: 'add_import', text: 'java.util.List']).text ==
                '/*\n * header\n */\nimport java.util.List\n\nclass Bare {\n}\n'
        refusedIn('Bare.groovy', bare, [operation: 'add_import', text: 'not an import!']).responseError.message
                .contains('not an import')
    }

    def 'Subject: replace_body by a qualified name, and replace of the whole class'() {
        given:
        def lines = linesOf(SUBJECT)

        when:
        def body = onFixture(SUBJECT, [symbol: 'demo.Subject.square', operation: 'replace_body', text: 'value * value'])
        def type = onFixture(SUBJECT, [symbol: 'Subject', operation: 'replace',
                text: '@groovy.transform.CompileStatic\nclass Subject {\n}'])

        then:
        body.text == joined(lines[0..4] + ['        value * value'] + lines[6..-1])
        body.startLine == 5
        body.endLine == 7
        type.text == joined(lines[0..1] + ['@groovy.transform.CompileStatic', 'class Subject {', '}'])
        type.startLine == 3
        type.endLine == 8
    }

    def 'an overloaded name is ambiguous without line, which picks the declaration that spans it'() {
        when:
        def ambiguous = refusedIn('Shapes.groovy', SHAPES, [symbol: 'area', operation: 'delete'])

        then:
        ambiguous.responseError.code == ResponseErrorCode.InvalidParams.value
        ambiguous.responseError.message.contains('ambiguous')
        ambiguous.responseError.message.contains('Shapes.area (lines 14-16)')
        ambiguous.responseError.message.contains('Shapes.area (lines 18-20)')

        when:
        def second = editIn('Shapes.groovy', SHAPES, [symbol: 'Shapes#area', line: 19, operation: 'replace_body',
                text: 'width * height'])
        def constructor = editIn('Shapes.groovy', SHAPES, [symbol: 'Shapes.<init>', line: 8, operation: 'replace_body',
                text: 'this.size = size * 2'])

        then:
        second.symbol == 'Shapes.area'
        second.text.contains('    int area(int width, int height) {\n        width * height\n    }\n')
        second.text.contains('        return side * side\n')
        constructor.symbol == 'Shapes.<init>'
        constructor.text.contains('    Shapes(int size) {\n        this.size = size * 2\n    }\n')

        when:
        def nowhere = refusedIn('Shapes.groovy', SHAPES, [symbol: 'area', line: 3, operation: 'delete'])

        then:
        nowhere.responseError.message.contains('spans line 3')
    }

    def 'a one-line body is opened up, and an inner class member is found by its dotted name'() {
        when:
        def result = editIn('Shapes.groovy', SHAPES, [symbol: 'Inner.go', operation: 'replace_body', text: "println 'go'"])

        then:
        result.symbol == 'Shapes.Inner.go'
        result.text.contains("        void go() {\n            println 'go'\n        }\n")

        when:
        def emptied = editIn('Shapes.groovy', SHAPES, [symbol: 'Inner.go', operation: 'replace_body', text: ''])

        then:
        emptied.text.contains('        void go() {\n        }\n')
    }

    def 'an unknown symbol is refused naming the declarations there are'() {
        when:
        def error = refused([uri: uri(VERBOSE), symbol: 'nope', operation: 'delete'])

        then:
        error.responseError.code == ResponseErrorCode.InvalidParams.value
        error.responseError.message.contains('nope not found')
        error.responseError.message.contains('Verbose.countWords (lines 22-39)')
        error.responseError.message.contains('Verbose.counter (line 12)')
    }

    def 'at most twenty declarations are named'() {
        given:
        String many = 'class Many {\n' + (1..25).collect { "    int m${it}() { ${it} }\n" }.join('') + '}\n'

        when:
        def error = refusedIn('Many.groovy', many, [symbol: 'nope', operation: 'delete'])

        then:
        error.responseError.message.contains('and 6 more')
        !error.responseError.message.contains('Many.m25')
    }

    def 'a CRLF file stays CRLF and a file without a trailing newline stays without one'() {
        given:
        String crlf = SHAPES.replace('\n', '\r\n')
        String bare = 'class Solo {\n    int a() {\n        1\n    }\n}'

        when:
        def windows = editIn('Shapes.groovy', crlf, [symbol: 'area', line: 15, operation: 'replace_body', text: 'side * side\n'])
        def member = editIn('Solo.groovy', bare, [symbol: 'Solo', operation: 'add_member', text: 'int b() {\n    2\n}'])

        then:
        windows.text.endsWith('\r\n')
        !(windows.text =~ /[^\r]\n/)
        windows.text.contains('    int area(int side) {\r\n        side * side\r\n    }\r\n')
        windows.newStr.contains('\r\n')
        member.text == 'class Solo {\n    int a() {\n        1\n    }\n\n    int b() {\n        2\n    }\n}'
    }

    def 'a script: methods are edited, the last one deleted without a trailing newline, and add_member is refused'() {
        given:
        String script = "println 'hi'\n\ndef helper(int x) {\n    x + 1\n}\n\ndef last() {\n    2\n}"

        when:
        def deleted = editIn('Deploy.groovy', script, [symbol: 'last', operation: 'delete'])
        def replaced = editIn('Deploy.groovy', script, [symbol: 'helper', operation: 'replace', text: 'def helper(int x) {\n    x + 2\n}'])

        then:
        deleted.text == "println 'hi'\n\ndef helper(int x) {\n    x + 1\n}"
        deleted.symbol == 'Deploy.last'
        replaced.text == "println 'hi'\n\ndef helper(int x) {\n    x + 2\n}\n\ndef last() {\n    2\n}"

        when:
        def error = refusedIn('Deploy.groovy', script, [symbol: 'Deploy', operation: 'add_member', text: 'int b'])

        then:
        error.responseError.message.contains('is a script')
    }

    def 'oldStr grows by context lines until it occurs once'() {
        given:
        String twins = 'class A {\n\n    int one() {\n        return 1\n    }\n}\n\nclass B {\n\n    int one() {\n        return 1\n    }\n}\n'

        when:
        def result = editIn('Twins.groovy', twins, [symbol: 'B.one', operation: 'replace_body', text: 'return 2'])

        then:
        result.oldStr.contains('class B')
        result.text.endsWith('class B {\n\n    int one() {\n        return 2\n    }\n}\n')
    }

    def 'add_member opens an empty one-line class body and refuses one that holds members on the same line'() {
        when:
        def opened = editIn('Empty.groovy', 'class Empty {}\n', [symbol: 'Empty', operation: 'add_member', text: 'int x'])
        def empty = editIn('Hollow.groovy', 'class Hollow {\n}\n', [symbol: 'Hollow', operation: 'add_member', text: 'int x', position: 'start'])
        def emptyEnd = editIn('Hollow.groovy', 'class Hollow {\n}\n', [symbol: 'Hollow', operation: 'add_member', text: 'int x'])
        def first = editIn('Tight.groovy', 'class Tight {\n    int a\n}\n', [symbol: 'Tight', operation: 'add_member', text: 'int b', position: 'start'])
        def between = editIn('Tight.groovy', 'class Tight {\n    int a\n    int c\n}\n', [symbol: 'Tight', operation: 'add_member', text: 'int b', position: 'after:Tight.a'])

        then:
        opened.text == 'class Empty {\n    int x\n}\n'
        empty.text == 'class Hollow {\n    int x\n}\n'
        emptyEnd.text == 'class Hollow {\n    int x\n}\n'
        first.text == 'class Tight {\n    int b\n\n    int a\n}\n'
        between.text == 'class Tight {\n    int a\n\n    int b\n\n    int c\n}\n'

        when:
        def crowded = refusedIn('One.groovy', 'class One { int a() { 1 } }\n', [symbol: 'One', operation: 'add_member', text: 'int b'])
        def sharing = refusedIn('One.groovy', 'class One { int a() { 1 } }\n', [symbol: 'a', operation: 'replace', text: 'int a() { 2 }'])

        then:
        crowded.responseError.message.contains('shares one line')
        sharing.responseError.message.contains('shares a line')
    }

    def 'a file indented with tabs gets tabs'() {
        when:
        def result = editIn('Tabs.groovy', 'class Tabs {\n\tint a() {\n\t\t1\n\t}\n}\n', [symbol: 'a', operation: 'replace_body', text: '2'])

        then:
        result.text == 'class Tabs {\n\tint a() {\n\t\t2\n\t}\n}\n'
    }

    def 'requests that cannot be met are invalid params with the reason'() {
        expect:
        def error = refusedIn('Shapes.groovy', SHAPES, params)
        error.responseError.code == ResponseErrorCode.InvalidParams.value
        error.responseError.message.contains(reason)

        where:
        params                                                                   | reason
        [symbol: 'size', operation: 'replace']                                   | 'needs a text'
        [symbol: 'size', operation: 'insert_after', text: '  ']                  | 'not blank'
        [symbol: 'size', operation: 'replace', text: ' ']                        | 'use delete'
        [symbol: 'size', operation: 'rewrite', text: 'x']                        | 'operation must be one of'
        [operation: 'delete']                                                    | 'needs a symbol'
        [symbol: 'size', operation: 'replace_body', text: 'x']                   | 'needs a method or a constructor'
        [symbol: 'Shapes.size', operation: 'add_member', text: 'x']              | 'needs a class'
        [symbol: 'Shapes', operation: 'add_member', text: 'int x', position: 'middle'] | 'position must be'
        [symbol: 'Shapes', operation: 'add_member', text: 'int x', position: 'after:nope'] | 'its members'
        [symbol: 'Shapes', operation: 'add_member', text: 'int x', position: 'after:area'] | 'ambiguous'
    }

    def 'a document that does not parse, is missing or lies outside the workspace is refused'() {
        expect:
        refusedIn('Broken.groovy', 'class Broken {\n    void a() {\n', [symbol: 'a', operation: 'delete'])
                .responseError.message.startsWith('does not parse: ')
        refused([uri: uri('src/main/groovy/demo/Missing.groovy'), symbol: 'a', operation: 'delete'])
                .responseError.message.contains('cannot be read')
        refused([uri: Fixtures.uri('basic-app', 'build.gradle'), symbol: 'a', operation: 'delete'])
                .responseError.message.contains('outside the workspace')
        refused([symbol: 'a', operation: 'delete']).responseError.code == ResponseErrorCode.InvalidParams.value
    }

    def 'a declaration without a body cannot have its body replaced'() {
        when:
        def error = refusedIn('Shape.groovy', 'interface Shape {\n    double area()\n}\n', [symbol: 'area', operation: 'replace_body', text: '1'])

        then:
        error.responseError.message.contains('has no body')
    }
}
