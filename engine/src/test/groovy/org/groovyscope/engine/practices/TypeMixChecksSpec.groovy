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

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** GS070 to GS076: one kind of value per variable, key, comparison, argument and return. */
class TypeMixChecksSpec extends Specification {

    static final String URI = 'file:///w/src/main/groovy/app/Job.groovy'

    @Shared
    GroovyCompiler compiler = new GroovyCompiler()

    private List<PracticeFinder.Practice> types(String text, String uri = URI, EngineConfig config = EngineConfig.DEFAULTS) {
        def module = compiler.compile([(uri): text], [], false).modules()[uri]
        PracticeFinder.find(uri, module, text, config).findAll { it.diagnostic().code().startsWith('GS07') }
    }

    private static List<String> codes(List<PracticeFinder.Practice> found) {
        found.sort(false) { it.diagnostic().range().start() }
                .collect { "${it.diagnostic().code()}:${it.diagnostic().range().start().line() + 1}".toString() }
    }

    private static PracticeFinder.Practice first(List<PracticeFinder.Practice> found, String code) {
        found.find { it.diagnostic().code() == code }
    }

    private static String message(List<PracticeFinder.Practice> found, String code) {
        first(found, code).diagnostic().message()
    }

    def "the edit gate fixture's type cases report GS070 to GS075"() {
        given:
        String text = Fixtures.text('edit-gate', 'src/main/groovy/demo/Types.groovy')

        when:
        def found = types(text, Fixtures.uri('edit-gate', 'src/main/groovy/demo/Types.groovy'))

        then:
        codes(found) == ['GS070:6', 'GS071:12', 'GS074:18', 'GS072:23', 'GS072:26', 'GS073:29', 'GS075:39']
        message(found, 'GS070').contains('age > 18 compares text (age is String) with a number')
        first(found, 'GS070').diagnostic().confidence() == Confidence.STATIC
        message(found, 'GS071').contains('sum holds Integer (line 10) and String (line 12)')
        message(found, 'GS073').contains('status returns Boolean (line 31) and String (line 33)')
        message(found, 'GS075').contains('map.retries holds Integer (line 38) and String (line 39)')
        message(found, 'GS072').contains("by Groovy truth any text but '' is true, 'false' included")
        first(found, 'GS074').fix()*.newText() == ['size()']
        PracticeFixes.apply(text, first(found, 'GS074').fix()).text().contains('return items.size()')
    }

    def 'GS070: a literal of one kind assigned, compared, passed or returned where another kind is declared'() {
        when:
        def found = types('''\
class Job {
    boolean ready = 'yes'
    int age
    void send(String text) {}
    int seven() {
        return '7'
    }
    void run(String name, Job other, int count) {
        int n = '5'
        String s = 5
        other.age = '40'
        if (name > 18) {}
        if (count == true) {}
        if (count in ['a', 'b']) {}
        send(5)
    }
}
''')

        then:
        codes(found) == ['GS070:2', 'GS070:6', 'GS070:9', 'GS070:10', 'GS070:11', 'GS070:12', 'GS070:13', 'GS070:14',
                         'GS070:15']
        found.every { it.diagnostic().confidence() == Confidence.STATIC }
        found[2].diagnostic().message().contains("'5' is text assigned to n, declared int: Groovy stores the character code")
        found.find { it.diagnostic().message().contains('send(5)') || it.diagnostic().message().contains('5 is a number passed') }
                .diagnostic().message().contains('MissingMethodException')
        message(found, 'GS070').startsWith('Literal of another kind: ')
    }

    def 'GS070: a def local whose every value is of one kind is compared as that kind, with INFERRED confidence'() {
        when:
        def found = types('''\
class Job {
    void run(List<Integer> numbers) {
        def total = 0
        total = total + 1
        if (total == 'none') {}
        if ('x' in numbers) {}
    }
}
''')

        then:
        codes(found) == ['GS070:5', 'GS070:6']
        found[0].diagnostic().confidence() == Confidence.INFERRED
        found[0].diagnostic().message().contains('total is Integer (inferred from line 3)')
        found[1].diagnostic().message().contains("looks for text in numbers")
    }

    def 'GS070 stays silent on matching kinds, characters, nulls, unknown values and mixed locals'() {
        expect:
        types('''\
class Job {
    void run(String name, Map settings, int count) {
        char c = 'a'
        long l = 5
        BigDecimal d = 5
        String s = null
        Object o = 5
        def v = settings.get('x')
        if (v == 5) {}
        if (name == 'x' || count == 0 || count == null) {}
        def mixed = 0
        mixed = compute()
        if (mixed == 'a') {}
        if (c == 'a') {}
    }
    def compute() { 1 }
}
''').isEmpty()
    }

    def 'GS070 passes only where the call target is certain'() {
        when:
        def found = types('''\
class Box {
    Box(String label) {}
    static int parse(int value) { value }
}
class Job {
    void send(String text) {}
    void log(String... parts) {}
    void put(String key) {}
    void put(int key) {}
    void run() {
        new Box(5)
        Box.parse(true)
        send('x')
        log('a')
        put(5)
        [1].each { send(5) }
        send(text: 'x')
    }
}
''')

        then:
        codes(found) == ['GS070:11', 'GS070:12']
    }

    def 'GS070 leaves classes with methodMissing alone at their implicit calls'() {
        expect:
        types('''\
class Job {
    void send(String text) {}
    def methodMissing(String name, args) { null }
    void run() {
        send(5)
    }
}
''').isEmpty()
    }

    def 'GS071: a variable, field or parameter without a type assigned two kinds, nulls and appends aside'() {
        when:
        def found = types('''\
class Job {
    def status = 0
    void finish(attempt) {
        status = 'done'
        attempt = 1
        attempt = 'again'
        def ok = 1
        ok = 2
        def later = null
        later = 'a'
        def text = 'a'
        text += 1
        def count = 0
        count = count + 1
        int typed = 1
        typed = 2
    }
}
''')

        then:
        codes(found) == ['GS071:4', 'GS071:6']
        found.find { it.diagnostic().message().contains('status holds Integer (line 2) and String (line 4)') }
        found.find { it.diagnostic().message().contains('attempt holds Integer (line 5) and String (line 6)') }
    }

    def 'GS072: text compared with a boolean word, tested for truth or stored as a boolean'() {
        when:
        def found = types('''\
class Job {
    boolean run(String flag, String name) {
        if (flag) {}
        if (!flag) {}
        boolean on = flag
        if (flag.equals('false')) {}
        if (name) {}
        String shown = flag ?: 'none'
        return flag == 'true'
    }
    boolean ready(String groupId) {
        return groupId
    }
}
''')

        then:
        codes(found) == ['GS072:3', 'GS072:4', 'GS072:5', 'GS072:6', 'GS072:9']
    }

    def 'GS073: a def method returning two kinds on its paths, the last expression included'() {
        when:
        def found = types('''\
class Job {
    def status(int code) {
        if (code == 0) {
            return true
        }
        'failed'
    }
    def one(int code) {
        if (code) {
            return 1
        }
        return null
    }
    def text(int code) {
        if (code) {
            return 'a'
        }
        return "b${code}"
    }
    def closures() {
        [1].collect { return 'a' }
        return 5
    }
    String typed() {
        return 'a'
    }
}
''')

        then:
        codes(found) == ['GS073:2']
        found[0].diagnostic().message().contains('status returns Boolean (line 4) and String (line 6)')
        found[0].diagnostic().range().start().column() == 8
    }

    def 'GS074: length on a collection, a list passed for an array, an array against a list, a literal never found'() {
        given:
        String text = '''\
class Job {
    void join(String[] parts) {}
    void any(Object[] parts) {}
    void run(List<String> items, String[] names, int[] counts) {
        int a = items.length
        int b = items.length()
        join(['a', 'b'])
        any(['a'])
        if (names == ['x']) {}
        if (5 in names) {}
        int c = names.length
        int d = items.size()
    }
}
'''

        when:
        def found = types(text)

        then:
        codes(found) == ['GS074:5', 'GS074:6', 'GS074:7', 'GS074:9', 'GS074:10']
        PracticeFixes.apply(text, found[0].fix()).text().contains('int a = items.size()')
        PracticeFixes.apply(text, found[1].fix()).text().contains('int b = items.size()')
        PracticeFixes.apply(text, found[2].fix()).text().contains("join(['a', 'b'] as String[])")
        found[3].diagnostic().message().contains('Groovy matches them element by element')
    }

    def 'GS075: one literal key of a map written with two kinds in a method'() {
        when:
        def found = types('''\
class Job {
    void run() {
        Map options = [retries: 3]
        options.retries = 'three'
        def other = [:]
        other['k'] = 1
        other.put('k', 'x')
        Map same = [:]
        same.a = 1
        same.a = 2
        same.b = null
        same.b = 'x'
    }
}
''')

        then:
        codes(found) == ['GS075:4', 'GS075:7']
        found[1].diagnostic().confidence() == Confidence.INFERRED
    }

    def 'GS076: an argument of a known kind Groovy does not convert for the parameter'() {
        when:
        def found = types('''\
class Job {
    void send(String text) {}
    void take(int count) {}
    void letter(char c) {}
    void join(String[] parts) {}
    void run(String text, List<String> names) {
        int count = 5
        send(count)
        take(text)
        def name = 'x'
        letter("${name}")
        join(names)
        def inferred = 'y'
        take(inferred)
        send("${count}")
        send(text)
    }
}
''')

        then:
        codes(found) == ['GS076:8', 'GS076:9', 'GS070:11', 'GS076:12', 'GS076:14']
        found.find { it.diagnostic().code() == 'GS076' && it.diagnostic().range().start().line() == 13 }
                .diagnostic().confidence() == Confidence.INFERRED
        message(found, 'GS076').contains('count is int passed where send declares String text')
    }

    def 'well typed and dynamic code report nothing'() {
        expect:
        types('''\
class Job {
    def handle(request) {
        def body = request.body
        def total = body.items.sum { it.price }
        if (total > 100) {
            body.discount = true
        }
        request.respond(body)
    }
    int add(int a, int b) {
        int sum = a + b
        return sum
    }
    String label(String name) {
        name ? "Dear ${name}" : 'Hello'
    }
}
''').isEmpty()
    }

    def 'a Spock specification and an annotation type are left alone'() {
        expect:
        types('''\
class JobSpec extends spock.lang.Specification {
    def 'x'() {
        def a = 1
        a = 'b'
        expect:
        a
    }
}
@interface Marker {}
''').isEmpty()
    }

    def 'a code switched off reports nothing'() {
        given:
        EngineConfig config = EngineConfig.DEFAULTS.toBuilder().practices(new EngineConfig.Practices(true,
                [GS071: 'off'])).build()

        expect:
        types('class Job {\n    void run() {\n        def a = 1\n        a = "b"\n    }\n}\n', URI, config).isEmpty()
    }

    def 'hover sees every value a local is assigned, with its type and line'() {
        given:
        String text = '''\
class Job {
    void run() {
        def total = 0
        total = 'n/a'
        total = null
        total = compute()
        def once = 1
    }
    def compute() { 1 }
}
'''
        def module = compiler.compile([(URI): text], [], false).modules()[URI]
        ClassNode job = module.classes[0]
        MethodNode run = job.getMethods('run')[0]
        Map<String, VariableExpression> locals = [:]
        run.code.visit(new CodeVisitorSupport() {
            @Override
            void visitDeclarationExpression(DeclarationExpression expression) {
                locals[expression.variableExpression.name] = expression.variableExpression
            }
        })

        expect:
        PracticeFinder.assignedTypes(job, run, locals.total)*.toString() == [
                'InferredType[type=Integer, line=3]', 'InferredType[type=String, line=4]', 'InferredType[type=null, line=6]']
        PracticeFinder.assignedTypes(job, run, locals.once) == []
        PracticeFinder.assignedTypes(null, run, locals.once) == []
    }
}
