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
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@code groovyscope/changeSignature}, {@code groovyscope/extractMethod} and {@code groovyscope/moveMember}: edits and
 * one exact replacement per file, nothing written; and the {@code Extract method} code action.
 */
@Timeout(180)
class RefactorRequestSpec extends Specification {

    static final String PRICING = '''\
package demo

import groovy.transform.CompileStatic

@CompileStatic
class Pricing {

    /** The total of a price over a quantity. */
    static int total(int price, int quantity, String label) {
        return price * quantity
    }

    static int total(int price) {
        return price
    }

    int twice(int price) {
        return total(price, 2, 'twice')
    }
}
'''

    static final String CHECKOUT = '''\
package demo

import groovy.transform.CompileStatic

@CompileStatic
class Checkout {

    int pay(int amount) {
        int first = Pricing.total(amount, 1, 'one')
        return first + Pricing.total(amount, 3, "three")
    }
}
'''

    static final String REPORT = '''\
package demo

import groovy.transform.CompileStatic

@CompileStatic
class Report {

    int base = 10

    String render(List<String> names, int width) {
        String title = 'Report'
        int count = 0
        for (String name : names) {
            count += name.size()
        }
        String line = title + ': ' + count
        String padded = line.padRight(width)
        return padded
    }

    int scaled(int factor) {
        int value = base * factor
        int doubled = value * 2
        return doubled + 1
    }

    int clamp(int value) {
        if (value < 0) {
            return 0
        }
        int result = value * 2
        return result
    }

    int sum(List<Integer> values) {
        int total = 0
        values.each { Integer value ->
            total += value
        }
        return total
    }
}
'''

    static final String TEXTS = '''\
package demo

import groovy.transform.CompileStatic

@CompileStatic
class Texts {

    static final int LIMIT = 3

    String prefix = '>'

    /** Shortens a text to the limit. */
    static String shorten(String text) {
        return text.size() > LIMIT ? text.substring(0, LIMIT) : text
    }

    String title(String text) {
        return shorten(text).toUpperCase() + exclaim(text)
    }

    String exclaim(String text) {
        return text + '!'
    }

    String mark(String text) {
        return prefix + text
    }

    static String trim(String text) {
        return text
    }
}
'''

    static final String STRINGS = '''\
package demo.util

import groovy.transform.CompileStatic

@CompileStatic
class Strings {

    static String trim(String text) {
        return text.strip()
    }
}
'''

    static final String BANNER = '''\
package demo

import groovy.transform.CompileStatic

@CompileStatic
class Banner {

    String show(String text) {
        return Texts.shorten(text) + '!'
    }
}
'''

    @TempDir Path temp

    /** Writes {@code files} (relative path to text) under {@code src/main/groovy} of the temporary workspace. */
    Map<String, String> workspace(Map<String, String> files) {
        Map<String, String> uris = [:]
        files.each { String relative, String text ->
            Path file = temp.resolve('src/main/groovy').resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            uris[relative] = file.toUri().toString()
        }
        return uris
    }

    GroovyscopeLanguageServer server() {
        new GroovyscopeLanguageServer({ int code -> } as IntConsumer, temp, EngineConfig.DEFAULTS)
    }

    /** Sends one request, checks nothing was written and every replacement is exact, and answers the response. */
    Map request(String method, Map params) {
        Map<String, String> before = texts()
        def local = server()
        Map response = local."$method"([root: temp.toString()] + params).get(120, TimeUnit.SECONDS) as Map
        assert texts() == before: 'a refactoring request writes nothing'
        if (!response.refused) {
            exact(before, response)
        }
        return response
    }

    /** Every Groovy source of the temporary workspace by URI. */
    Map<String, String> texts() {
        Map<String, String> texts = [:]
        Files.walk(temp).withCloseable { stream ->
            stream.filter { it.toString().endsWith('.groovy') }.forEach { texts[it.toUri().toString()] = Files.readString(it) }
        }
        return texts
    }

    /**
     * Asserts that each {@code oldStr} occurs once in its file and that replacing it gives the text the LSP edits
     * give, and answers the edited texts of every file.
     */
    static Map<String, String> exact(Map<String, String> before, Map response) {
        Map<String, String> after = new LinkedHashMap<>(before)
        assert (response.files as List).size() == (response.edits as Map).size()
        for (Map file : response.files as List<Map>) {
            String original = before[file.uri]
            assert original.indexOf(file.oldStr) >= 0
            assert original.indexOf(file.oldStr) == original.lastIndexOf(file.oldStr): "oldStr occurs once in ${file.uri}"
            String replaced = original.replace(file.oldStr as String, file.newStr as String)
            assert replaced == applyEdits(original, response.edits[file.uri] as List)
            after[file.uri] = replaced
        }
        return after
    }

    /** {@code text} with LSP text edits applied, the last first. */
    static String applyEdits(String text, List edits) {
        List<Integer> starts = text.readLines().inject([0]) { List<Integer> acc, String line -> acc + [acc.last() + line.length() + 1] }
        def offset = { org.eclipse.lsp4j.Position position -> starts[position.line] + position.character }
        String result = text
        edits.collect().sort { a, b -> offset(b.range.start) <=> offset(a.range.start) }.each { edit ->
            result = result.substring(0, offset(edit.range.start)) + edit.newText + result.substring(offset(edit.range.end))
        }
        return result
    }

    /** The errors the engine's compiler and its static type checker report for {@code texts}. */
    static List<String> errors(Map<String, String> texts) {
        List<String> errors = []
        def result = new GroovyCompiler().compile(texts, [], false)
        try {
            result.diagnostics().values().flatten().findAll { it.severity() == Diagnostic.Severity.ERROR }
                    .each { errors << it.message() }
        } finally {
            result.close()
        }
        new TypeCheckAnalyzer().analyze(texts, [], false, TypeCheckAnalyzer.CORE_ONLY).values().flatten()
                .findAll { it.severity() == Diagnostic.Severity.ERROR }.each { errors << it.message() }
        return errors
    }

    /** The edited texts of a response applied to the current workspace. */
    Map<String, String> edited(Map response) {
        exact(texts(), response)
    }

    def 'the compile check catches a broken text, so a clean result means something'() {
        expect:
        errors(['file:///w/A.groovy': '@groovy.transform.CompileStatic\nclass A { int a() { return "x".nope() } }'])
        errors(['file:///w/A.groovy': '@groovy.transform.CompileStatic\nclass A { int a() { return 1 } }']).isEmpty()
    }

    def 'change signature reorders, drops and adds a defaulted parameter at the declaration and all three call sites'() {
        given:
        Map<String, String> uris = workspace(['demo/Pricing.groovy': PRICING, 'demo/Checkout.groovy': CHECKOUT])

        when:
        Map response = request('changeSignature', [uri: uris['demo/Pricing.groovy'], symbol: 'Pricing.total',
                line: 9, parameters: [[name: 'quantity', type: 'int'], [name: 'price', type: 'int'],
                                      [name: 'rounding', type: 'boolean', default: 'false']]])
        Map<String, String> after = edited(response)
        String pricing = after[uris['demo/Pricing.groovy']]
        String checkout = after[uris['demo/Checkout.groovy']]

        then:
        response.callSites == 3
        response.declaration == 'static int total(int quantity, int price, boolean rounding)'
        pricing.contains('    /** The total of a price over a quantity. */\n    static int total(int quantity, int price, boolean rounding) {')
        pricing.contains("return total(2, price, false)")
        pricing.contains('static int total(int price) {')
        checkout.contains("int first = Pricing.total(1, amount, false)")
        checkout.contains('return first + Pricing.total(3, amount, false)')
        errors(after).isEmpty()
    }

    def 'change signature keeps a parameter as written, changes the return type and rewrites nested calls'() {
        given:
        Map<String, String> uris = workspace(['demo/Nest.groovy': '''\
package demo

class Nest {

    static def add(final int a, int b) {
        a + b
    }

    static int run() {
        add(add(1, 2), 3)
    }
}
'''])

        when:
        Map response = request('changeSignature', [uri: uris['demo/Nest.groovy'], symbol: 'add',
                parameters: [[name: 'b'], [name: 'a', type: 'int']], returnType: 'int'])
        String after = edited(response)[uris['demo/Nest.groovy']]

        then:
        response.declaration == 'static int add(int b, final int a)'
        after.contains('add(3, add(2, 1))')
        errors([(uris['demo/Nest.groovy']): after]).isEmpty()
    }

    def 'change signature refuses when a call site is matched by name only, and lists it'() {
        given:
        Map<String, String> uris = workspace(['demo/Pricing.groovy': PRICING, 'demo/Checkout.groovy': CHECKOUT,
                'demo/Loose.groovy': 'package demo\n\nclass Loose {\n    def run(helper) {\n        helper.total(1, 2, \'x\')\n    }\n}\n'])

        when:
        Map response = request('changeSignature', [uri: uris['demo/Pricing.groovy'], symbol: 'Pricing.total',
                line: 9, parameters: [[name: 'price', type: 'int'], [name: 'quantity', type: 'int']]])

        then:
        response.keySet() == ['refused'] as Set
        response.refused == '1 call site(s) are matched by name only (dynamic dispatch): Loose.groovy:5; change ' +
                'signature refused rather than guessed'
    }

    def 'change signature refuses what call sites cannot keep'() {
        given:
        Map<String, String> uris = workspace(['demo/Pricing.groovy': PRICING, 'demo/Checkout.groovy': checkout])

        when:
        Map response = request('changeSignature', [uri: uris['demo/Pricing.groovy'], symbol: 'Pricing.total',
                line: 9, parameters: parameters])

        then:
        response.refused == refused

        where:
        checkout                                                | parameters                                                    || refused
        CHECKOUT                                                | [[name: 'price'], [name: 'quantity'], [name: 'label'], [name: 'extra', type: 'int']] || 'the added parameter extra needs a default: 3 call site(s) must pass it'
        CHECKOUT.replace("'one'", 'String.valueOf(amount)')    | [[name: 'price'], [name: 'quantity']]                         || 'the call at Checkout.groovy:9 passes `String.valueOf(amount)` to the removed parameter label, and dropping it may drop a side effect (only a literal, a variable or a property read is dropped)'
        CHECKOUT.replace('total(amount, 1, \'one\')', 'total(*[amount, 1, \'one\'])') | [[name: 'price'], [name: 'quantity'], [name: 'label']] || '1 call site(s) are matched by name only (dynamic dispatch): Checkout.groovy:9; change signature refused rather than guessed'
        CHECKOUT.replace('        int first', '        Pricing.total amount, 2, \'two\'\n        int first') | [[name: 'price'], [name: 'quantity'], [name: 'label']] || 'the call at Checkout.groovy:9 has no parentheses; add them first'
    }

    def 'change signature refuses named arguments and an override, and errors on a symbol it cannot find'() {
        given:
        Map<String, String> uris = workspace(['demo/Named.groovy': '''\
package demo

class Named {
    static int go(Map options, int n) {
        n
    }

    static int call() {
        go(a: 1, 2)
    }

    static int apply(int n, Closure<Integer> twice) {
        twice(n)
    }

    static int applied() {
        apply(1) { int it -> it * 2 }
    }

    static int count(int n, String[] names) {
        n + names.length
    }

    static int counted() {
        count(1, 'a', 'b')
    }
}
''', 'demo/Base.groovy': '''\
package demo

class Base {
    String name(int n) {
        'base'
    }
}

class Derived extends Base {
    @Override
    String name(int n) {
        'derived'
    }
}
'''])

        when:
        Map named = request('changeSignature', [uri: uris['demo/Named.groovy'], symbol: 'go',
                parameters: [[name: 'n'], [name: 'options']]])
        Map overridden = request('changeSignature', [uri: uris['demo/Base.groovy'], symbol: 'Base.name',
                parameters: [[name: 'n'], [name: 'm', type: 'int', default: '0']]])
        Map overriding = request('changeSignature', [uri: uris['demo/Base.groovy'], symbol: 'Derived.name',
                parameters: []])
        Map trailing = request('changeSignature', [uri: uris['demo/Named.groovy'], symbol: 'apply',
                parameters: [[name: 'twice'], [name: 'n']]])
        Map array = request('changeSignature', [uri: uris['demo/Named.groovy'], symbol: 'count',
                parameters: [[name: 'names'], [name: 'n']]])
        ResponseErrorException missing = refusal('changeSignature', [uri: uris['demo/Named.groovy'], symbol: 'nope',
                parameters: []])
        ResponseErrorException twice = refusal('changeSignature', [uri: uris['demo/Named.groovy'], symbol: 'go',
                parameters: [[name: 'n'], [name: 'n']]])
        ResponseErrorException constructor = refusal('changeSignature', [uri: uris['demo/Named.groovy'],
                symbol: 'Named', parameters: []])
        ResponseErrorException noList = refusal('changeSignature', [uri: uris['demo/Named.groovy'], symbol: 'go'])

        then:
        named.refused == 'the call at Named.groovy:9 uses named arguments; change signature refused'
        overridden.refused == 'Derived.name overrides it (line 10 of Base.groovy); change the two together'
        overriding.refused == 'name overrides Base.name; change the two together'
        missing.responseError.code == ResponseErrorCode.InvalidParams.value
        missing.message.startsWith('changeSignature: symbol nope not found')
        trailing.refused == 'the call at Named.groovy:17 passes a closure after its parentheses; move it inside first'
        array.refused == 'the last parameter names is an array, which Groovy also fills from a variable argument ' +
                'list; keep it last'
        twice.message == 'changeSignature: parameter n is named twice'
        constructor.message == 'changeSignature: change signature needs a method; Named is a class'
        noList.message == 'changeSignature needs parameters, the whole new list ({name, type?, default?})'
    }

    ResponseErrorException refusal(String method, Map params) {
        try {
            request(method, params)
        } catch (ExecutionException e) {
            return e.cause as ResponseErrorException
        }
        throw new AssertionError('the request was not refused')
    }

    def 'extract method derives parameters and the returned local, and the result compiles as a static method'() {
        given:
        Map<String, String> uris = workspace(['demo/Report.groovy': REPORT])

        when:
        Map response = request('extractMethod', [uri: uris['demo/Report.groovy'], startLine: 12, endLine: 16,
                name: 'summary'])
        String after = edited(response)[uris['demo/Report.groovy']]

        then:
        response.method == 'private static String summary(List<String> names, String title)'
        response.parameters == [[name: 'names', type: 'List<String>'], [name: 'title', type: 'String']]
        response.returns == 'line'
        response.static == true
        after.contains('''\
        String title = 'Report'
        String line = summary(names, title)
        String padded = line.padRight(width)
        return padded
    }

    private static String summary(List<String> names, String title) {
        int count = 0
        for (String name : names) {
            count += name.size()
        }
        String line = title + ': ' + count
        return line
    }

    int scaled(int factor) {''')
        errors([(uris['demo/Report.groovy']): after]).isEmpty()
    }

    def 'extract method keeps instance state in an instance method and returns from the end of the method'() {
        given:
        Map<String, String> uris = workspace(['demo/Report.groovy': REPORT])

        when:
        Map instance = request('extractMethod', [uri: uris['demo/Report.groovy'], startLine: 22, endLine: 22,
                name: 'valueOf'])
        Map ending = request('extractMethod', [uri: uris['demo/Report.groovy'], startLine: 17, endLine: 18,
                name: 'finish'])
        String afterInstance = edited(instance)[uris['demo/Report.groovy']]
        String afterEnding = edited(ending)[uris['demo/Report.groovy']]

        then:
        instance.method == 'private int valueOf(int factor)'
        instance.static == false
        instance.returns == 'value'
        afterInstance.contains('        int value = valueOf(factor)\n')
        afterInstance.contains('    private int valueOf(int factor) {\n        int value = base * factor\n        return value\n    }\n')
        errors([(uris['demo/Report.groovy']): afterInstance]).isEmpty()
        ending.method == 'private static String finish(String line, int width)'
        ending.returns == null
        afterEnding.contains('        return finish(line, width)\n    }\n\n    private static String finish(String line, int width) {\n')
        errors([(uris['demo/Report.groovy']): afterEnding]).isEmpty()
    }

    def 'extract method refuses what a call cannot reproduce'() {
        given:
        Map<String, String> uris = workspace(['demo/Report.groovy': REPORT])

        when:
        Map response = request('extractMethod', [uri: uris['demo/Report.groovy'], startLine: start, endLine: end,
                name: 'part'])

        then:
        response.refused == refused

        where:
        start | end || refused
        13    | 14  || 'lines 13 to 14 cut the statement at lines 13 to 15; select whole statements of one block'
        11    | 12  || 'the selection writes title, count, which are read after it; extract method returns one value'
        28    | 31  || 'line 29 returns from inside the selection; a return is extracted only as the last statement of a selection that ends the method'
        38    | 38  || 'lines 38 to 38 are inside a closure (line 37); extract method takes whole statements of a method body'
        7     | 8   || 'lines 7 to 8 are not inside a method body; extract method takes statements of a method'
        19    | 21  || 'lines 19 to 21 are not inside a method body; extract method takes statements of a method'
    }

    def 'extract method rejects lines outside the document and a name that is not an identifier'() {
        given:
        Map<String, String> uris = workspace(['demo/Report.groovy': REPORT])

        expect:
        refusal('extractMethod', [uri: uris['demo/Report.groovy'], startLine: 40, endLine: 400, name: 'x'])
                .message.startsWith('extractMethod: startLine and endLine must be 1-based lines')
        refusal('extractMethod', [uri: uris['demo/Report.groovy'], startLine: 12, endLine: 12, name: 'not a name'])
                .message == 'extractMethod: method name not a name is not an identifier'
        refusal('extractMethod', [uri: uris['demo/Report.groovy']]).message ==
                'extractMethod needs startLine and endLine (1-based, inclusive)'
    }

    static final String ACC = '''\
package demo

import groovy.transform.CompileStatic

@CompileStatic
class Acc {

    static int run(int start) {
        int total = start
        total = total * 2
        total += 1
        return total
    }

    static void show(String name) {
        String text = name.trim()
        println(text)
        return
    }

    static String build() {
        def builder = new StringBuilder()
        builder.append('a')
        return builder.toString()
    }
}
'''

    def 'extract method passes and returns a local declared before, ends a void method and infers a def type'() {
        given:
        Map<String, String> uris = workspace(['demo/Acc.groovy': ACC])
        String uri = uris['demo/Acc.groovy']

        when:
        Map updated = request('extractMethod', [uri: uri, startLine: 10, endLine: 11, name: 'grow'])
        Map ending = request('extractMethod', [uri: uri, startLine: 17, endLine: 18, name: 'print'])
        Map inferred = request('extractMethod', [uri: uri, startLine: 23, endLine: 23])
        Map clash = request('extractMethod', [uri: uri, startLine: 23, endLine: 23, name: 'show'])

        then:
        updated.method == 'private static int grow(int total)'
        updated.returns == 'total'
        edited(updated)[uri].contains('        total = grow(total)\n        return total\n')
        errors(edited(updated)).isEmpty()
        ending.method == 'private static void print(String text)'
        edited(ending)[uri].contains('        String text = name.trim()\n        print(text)\n    }\n')
        errors(edited(ending)).isEmpty()
        inferred.method == 'private static void extracted(StringBuilder builder)'
        errors(edited(inferred)).isEmpty()
        clash.refused == 'Acc already has a method show with 1 parameter(s) (line 15); choose another name'
    }

    def 'move member moves a static helper to a class in another file and package, with call sites and imports'() {
        given:
        Map<String, String> uris = workspace(['demo/Texts.groovy': TEXTS, 'demo/util/Strings.groovy': STRINGS,
                'demo/Banner.groovy': BANNER])

        when:
        Map response = request('moveMember', [uri: uris['demo/Texts.groovy'], symbol: 'Texts.shorten',
                toClass: 'Strings'])
        Map<String, String> after = edited(response)
        String texts = after[uris['demo/Texts.groovy']]
        String strings = after[uris['demo/util/Strings.groovy']]
        String banner = after[uris['demo/Banner.groovy']]

        then:
        response.from == 'demo.Texts'
        response.to == 'demo.util.Strings'
        response.callSites == 2
        response.static == false
        response.method == 'static String shorten(String text)'
        !texts.contains('static String shorten(')
        texts.contains('return Strings.shorten(text).toUpperCase() + exclaim(text)')
        texts.contains('import demo.util.Strings\n')
        strings == '''\
package demo.util

import demo.Texts
import groovy.transform.CompileStatic

@CompileStatic
class Strings {

    static String trim(String text) {
        return text.strip()
    }

    /** Shortens a text to the limit. */
    static String shorten(String text) {
        return text.size() > Texts.LIMIT ? text.substring(0, Texts.LIMIT) : text
    }
}
'''
        banner.contains('return Strings.shorten(text) + \'!\'')
        banner.contains('import demo.util.Strings\n')
        errors(after).isEmpty()
    }

    def 'move member makes an instance method that uses no state static, and calls it through the new class'() {
        given:
        Map<String, String> uris = workspace(['demo/Texts.groovy': TEXTS, 'demo/util/Strings.groovy': STRINGS])

        when:
        Map response = request('moveMember', [uri: uris['demo/Texts.groovy'], symbol: 'exclaim',
                toClass: 'demo.util.Strings'])
        Map<String, String> after = edited(response)

        then:
        response.static == true
        response.callSites == 1
        response.method == 'static String exclaim(String text)'
        after[uris['demo/Texts.groovy']].contains('shorten(text).toUpperCase() + Strings.exclaim(text)')
        after[uris['demo/util/Strings.groovy']].contains('    static String exclaim(String text) {\n        return text + \'!\'\n    }\n}\n')
        errors(after).isEmpty()
    }

    def 'move member refuses instance state, a taken signature, a dynamic call and a call through an instance'() {
        given:
        Map<String, String> uris = workspace(['demo/Texts.groovy': TEXTS, 'demo/util/Strings.groovy': STRINGS,
                'demo/Banner.groovy': banner])

        when:
        Map response = request('moveMember', [uri: uris['demo/Texts.groovy'], symbol: symbol, toClass: target])

        then:
        response.refused == refused

        where:
        symbol    | target    | banner                                                             || refused
        'mark'    | 'Strings' | BANNER                                                             || 'mark uses instance state (property prefix (line 26)); move member moves a static method or one that uses none'
        'trim'    | 'Strings' | BANNER                                                             || 'Strings already has trim(String) at line 8; move member refused'
        'shorten' | 'Strings' | BANNER.replace('Texts.shorten(text)', 'helper(text)') + 'class Other { def run(h) { h.shorten("x") } }\n' || '1 call site(s) are matched by name only (dynamic dispatch): Banner.groovy:12; move member refused rather than guessed'
        'exclaim' | 'Strings' | BANNER.replace('Texts.shorten(text)', 'new Texts().exclaim(text)') || 'exclaim is called through an instance at Banner.groovy:9 (`new Texts().exclaim(text)`); call it through its class first'
        'shorten' | 'Nowhere' | BANNER                                                             || 'no workspace class is named Nowhere'
        'shorten' | 'Texts'   | BANNER                                                             || 'shorten is already in Texts'
    }

    def 'move member keeps a recursive call, drops private when others call it, and refuses what it cannot reach'() {
        given:
        Map<String, String> uris = workspace(['demo/Maths.groovy': '''\
package demo

class Maths {

    private static final int BASE = 10

    private static int depth(int n) {
        n <= 0 ? 0 : 1 + depth(n - 1)
    }

    static int digits(int n) {
        n < BASE ? 1 : 1 + digits(n.intdiv(BASE) as int)
    }

    static int run() {
        depth(3) + Maths.digits(42)
    }
}
''', 'demo/Target.groovy': 'package demo\n\nclass Target {\n}\n', 'demo/Port.groovy': 'package demo\n\ninterface Port {\n}\n'])
        String maths = uris['demo/Maths.groovy']

        when:
        Map moved = request('moveMember', [uri: maths, symbol: 'depth', toClass: 'Target'])
        Map<String, String> after = edited(moved)
        Map field = request('moveMember', [uri: maths, symbol: 'digits', toClass: 'Target'])
        Map port = request('moveMember', [uri: maths, symbol: 'depth', toClass: 'Port'])
        ResponseErrorException noTarget = refusal('moveMember', [uri: maths, symbol: 'depth'])

        then:
        moved.madePublic == true
        moved.static == false
        moved.method == 'static int depth(int n)'
        after[uris['demo/Target.groovy']] == 'package demo\n\nclass Target {\n    static int depth(int n) {\n' +
                '        n <= 0 ? 0 : 1 + depth(n - 1)\n    }\n}\n'
        after[maths].contains('        Target.depth(3) + Maths.digits(42)')
        errors(after).isEmpty()
        field.refused == 'digits uses the private field BASE of Maths, which the new class cannot reach; make it ' +
                'reachable or move it too'
        port.refused == 'Port is an interface; move member moves into a class'
        noTarget.message == 'moveMember needs toClass'
    }

    def 'move member refuses a call through a static import'() {
        given:
        Map<String, String> uris = workspace(['demo/Maths.groovy': 'package demo\n\nclass Maths {\n    static int one() {\n' +
                '        1\n    }\n}\n', 'demo/Target.groovy': 'package demo\n\nclass Target {\n}\n',
                'demo/Imported.groovy': 'package demo\n\nimport static demo.Maths.one\n\nclass Imported {\n' +
                        '    int go() {\n        one()\n    }\n}\n'])

        when:
        Map response = request('moveMember', [uri: uris['demo/Maths.groovy'], symbol: 'one', toClass: 'Target'])

        then:
        response.refused == 'Imported.groovy:7 calls one through a static import of Maths; import it from the new ' +
                'class first'
    }

    def 'the Extract method code action appears for a selection of whole statements and not for part of one'() {
        given:
        Map<String, String> uris = workspace(['demo/Report.groovy': REPORT])
        def local = server()
        local.initialize(new InitializeParams(rootUri: temp.toUri().toString())).get(60, TimeUnit.SECONDS)
        def identifier = new TextDocumentIdentifier(uris['demo/Report.groovy'])

        when:
        def whole = local.textDocumentService.codeAction(new CodeActionParams(identifier,
                new Range(new Position(11, 0), new Position(16, 0)), new CodeActionContext([])))
                .get(120, TimeUnit.SECONDS)*.getRight()
        def refactors = local.textDocumentService.codeAction(new CodeActionParams(identifier,
                new Range(new Position(11, 4), new Position(15, 30)),
                new CodeActionContext([], [CodeActionKind.Refactor]))).get(120, TimeUnit.SECONDS)*.getRight()
        def part = local.textDocumentService.codeAction(new CodeActionParams(identifier,
                new Range(new Position(12, 0), new Position(13, 10)), new CodeActionContext([])))
                .get(120, TimeUnit.SECONDS)*.getRight()
        def quickOnly = local.textDocumentService.codeAction(new CodeActionParams(identifier,
                new Range(new Position(11, 0), new Position(16, 0)), new CodeActionContext([], [CodeActionKind.QuickFix])))
                .get(120, TimeUnit.SECONDS)*.getRight()
        def extract = whole.find { it.title == 'Extract method' }
        String after = applyEdits(REPORT, extract.edit.changes[uris['demo/Report.groovy']])

        then:
        extract.kind == CodeActionKind.RefactorExtract
        after.contains('String line = extracted(names, title)')
        after.contains('private static String extracted(List<String> names, String title) {')
        refactors*.title == ['Extract method']
        part.every { it.title != 'Extract method' }
        quickOnly.every { it.kind == CodeActionKind.QuickFix }
        errors([(uris['demo/Report.groovy']): after]).isEmpty()
    }
}
