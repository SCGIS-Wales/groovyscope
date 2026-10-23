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

import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** GS060 to GS065: comments that say why and nothing else. */
class CommentChecksSpec extends Specification {

    static final String URI = 'file:///w/src/main/groovy/app/Job.groovy'

    @Shared
    GroovyCompiler compiler = new GroovyCompiler()

    private List<PracticeFinder.Practice> comments(String text, EngineConfig config = EngineConfig.DEFAULTS,
            String uri = URI) {
        def module = compiler.compile([(uri): text], [], false).modules()[uri]
        PracticeFinder.find(uri, module, text, config).findAll { it.diagnostic().code().startsWith('GS06') }
    }

    private static EngineConfig with(EngineConfig.Comments comments, Map<String, String> severities = [:]) {
        EngineConfig.DEFAULTS.toBuilder().practices(new EngineConfig.Practices(true, severities,
                EngineConfig.Naming.DEFAULTS, [], comments)).build()
    }

    private static String applied(String text, PracticeFinder.Practice practice) {
        PracticeFixes.apply(text, practice.fix()).text()
    }

    private static List<Integer> lines(List<PracticeFinder.Practice> found, String code) {
        found.findAll { it.diagnostic().code() == code }.collect { it.diagnostic().range().start().line() + 1 }
    }

    def "the edit gate fixture's verbose method reports GS060, GS061 and GS065, and the rest of its comments"() {
        given:
        String text = Fixtures.text('edit-gate', 'src/main/groovy/demo/Verbose.groovy')

        when:
        def found = comments(text, EngineConfig.DEFAULTS, Fixtures.uri('edit-gate', 'src/main/groovy/demo/Verbose.groovy'))

        then:
        lines(found, 'GS060') == [23, 25, 33]
        lines(found, 'GS061') == [28]
        found.find { it.diagnostic().code() == 'GS061' }.diagnostic().message().contains('4 comment lines in a row')
        lines(found, 'GS065') == [22]
        found.find { it.diagnostic().code() == 'GS065' }.diagnostic().message()
                .contains('10 comment lines (reasons not counted) to 8 code lines in countWords (1.25, at most 0.25)')
        lines(found, 'GS062') == [14, 16]
        lines(found, 'GS063') == [35]
        lines(found, 'GS064') == [8, 10, 37]
    }

    def 'GS060: a comment that restates the statement below it, in a method or a closure'() {
        when:
        def found = comments('''\
class Job {
    int counter
    int add(int a, int b) {
        // increment the counter
        counter++
        // return the sum
        return a + b
    }
    def each(List<Integer> items) {
        items.each { item ->
            // add the item to the counter
            counter += item
        }
    }
}
''')

        then:
        lines(found, 'GS060') == [4, 6, 11]
        found[0].diagnostic().severity() == Diagnostic.Severity.WARNING
        found[0].diagnostic().message().contains('the comment says what the next line already says (100%')
        !found[0].preserving()
        applied('''\
int f() {
    // return zero
    return 0
}
''', comments('''\
int f() {
    // return zero
    return 0
}
''')[0]) == '''\
int f() {
    return 0
}
'''
    }

    def 'GS060 leaves a why, a list item, a comment outside a body and a comment that is mostly new words alone'() {
        expect:
        comments('''\
// the counter of runs
class Job {
    // the counter of runs
    int counter
    def call() { 1 }
    int run() {
        // the service times out under load, so retry once
        def result = call()
        // counter can be stale because another thread may bump it
        counter++
        // Valid values:
        // - zero
        int zero = 0
        // Remember the outcome for the nightly report
        counter = result
        return counter
    }
}
''').findAll { it.diagnostic().code() == 'GS060' }.isEmpty()
    }

    def 'GS061: more consecutive comment lines in a method body than allowed'() {
        given:
        String text = '''\
class Job {
    // A class level comment
    // of three lines
    // is not in a method.
    int run(String text) {
        // first line of a narration
        // second line of the narration
        // third line of the narration
        int words = text.size()
        // why in two
        // lines is fine
        return words
    }
}
'''

        when:
        def found = comments(text)
        def relaxed = comments(text, with(new EngineConfig.Comments(0.7, 3, 5, true, 0.25, 0.2, true)))

        then:
        lines(found, 'GS061') == [6]
        found.find { it.diagnostic().code() == 'GS061' }.diagnostic().message()
                .contains('3 comment lines in a row in a method body (at most 2); a why in one line is wanted, or none')
        !found.find { it.diagnostic().code() == 'GS061' }.fixable()
        lines(relaxed, 'GS061').isEmpty()
    }

    def 'GS062: Javadoc on a private member, a long Javadoc on a method, and tags that repeat the signature'() {
        given:
        String text = '''\
class Job {
    /** The run count. */
    private int runs

    /**
     * Runs the job once.
     * Second line.
     */
    void run() { runs++ }

    /**
     * Counts.
     */
    private int count() { runs }

    /**
     * One.
     * Two.
     * Three.
     * Four.
     * Five.
     * Six.
     */
    void verbose() { }

    /**
     * Retries the call.
     *
     * @param name the name
     * @param count count
     * @param limit how many tries at most, at least one
     * @return the result
     */
    String retry(String name, int count, int limit) { name * count }

    /**
     * The current total.
     * @return the total
     */
    int getTotal() { runs }
}
'''

        when:
        def found = comments(text)
        def gs062 = found.findAll { it.diagnostic().code() == 'GS062' }

        then:
        lines(found, 'GS062') == [2, 11, 16, 29, 30, 32, 38]
        gs062[0].diagnostic().message().contains('Javadoc on private field runs')
        gs062[1].diagnostic().message().contains('Javadoc on private method count')
        gs062[2].diagnostic().message().contains('Javadoc of 6 text lines on verbose (at most 5)')
        gs062[3].diagnostic().message().contains('@param name repeats the signature')
        gs062[4].diagnostic().message().contains('@param count repeats the signature')
        gs062[5].diagnostic().message().contains('@return repeats the signature')
        gs062[3].preserving()
        applied(text, gs062[3]).contains('''\
     *
     * @param count count''')
        comments(text, with(new EngineConfig.Comments(0.7, 2, 6, true, 0.25, 0.2, true)))
                .findAll { it.diagnostic().message().contains('text lines') }.isEmpty()
    }

    def 'GS062: a tag on the delimiter line is reported without a fix, and a constructor is named by its class'() {
        when:
        def found = comments('''\
class Job {
    /** @param name the name */
    Job(String name) { }

    /** Hidden. */
    private Job() { }
}
''')

        then:
        found.size() == 2
        !found[0].fixable()
        found[1].diagnostic().message().contains('Javadoc on private constructor Job')
    }

    def 'GS063: commented out code, but not prose with brackets, list items or an example'() {
        given:
        String text = '''\
class Job {
    int counter
    int run() {
        // int old = counter - 1
        // println(old)
        counter++
        // see the docs (section 3) for the rules
        // - checks spaces only (not tabs)
        // This handles a closure called later, e.g.:
        //      def closure = { println 'ok' }
        //      closure()
        String text = "// not = a (comment)"
        return counter
    }
    // void old() {
    //     println('gone')
    // }
}
'''

        when:
        def found = comments(text)
        def code = found.findAll { it.diagnostic().code() == 'GS063' }

        then:
        lines(found, 'GS063') == [4, 15]
        code[0].diagnostic().message().contains('2 lines of commented out code')
        !code[0].preserving()
        applied(text, code[0]).contains('''\
    int run() {
        counter++''')
    }

    def 'GS063: a commented out block comment is removed whole, part of one is reported without a fix'() {
        when:
        def found = comments('''\
class Job {
    /*
    def old = 1
    */
    /*
     * The old way
     * def old = compute(1)
     */
    int run() { 1 }
}
''').findAll { it.diagnostic().code() == 'GS063' }

        then:
        found.size() == 2
        found[0].fix()[0].range().start().line() == 1 && found[0].fix()[0].range().end().line() == 4
        !found[1].fixable()
        found[1].diagnostic().range().start().line() == 6
    }

    def 'GS064: banners and empty comments are removed, a TODO needs an owner or a ticket'() {
        given:
        String text = '''\
class Job {
    // ----------------------
    /* ***** */
    //
    /* */
    // TODO
    // FIXME: later
    // TODO(alice) owned
    // TODO ABC-123 ticketed
    // TODO @bob owned too
    // first paragraph
    //
    // second paragraph
    // ...
    int run() { 1 } // TODO trailing
}
'''

        when:
        def found = comments(text)
        def gs064 = found.findAll { it.diagnostic().code() == 'GS064' }
        def relaxed = comments(text, with(new EngineConfig.Comments(0.7, 2, 5, false, 0.25, 0.2, true)))

        then:
        lines(found, 'GS064') == [2, 3, 4, 5, 6, 7, 15]
        gs064[0].diagnostic().message().contains('a banner line')
        gs064[2].diagnostic().message().contains('an empty comment')
        gs064[4].diagnostic().message().contains('TODO without an owner or a ticket')
        gs064[5].diagnostic().message().contains('FIXME without an owner')
        gs064[0].preserving() && gs064[0].fixable()
        !gs064[4].fixable()
        applied(text, gs064[0]).startsWith('class Job {\n    /* ***** */')
        lines(relaxed, 'GS064') == [2, 3, 4, 5]
    }

    def 'GS064: a banner line inside a block comment is reported without a fix'() {
        when:
        def found = comments('''\
class Job {
    /*
     * Section
     * ---------
     * Text
     */
    int run() { 1 }
}
''')

        then:
        lines(found, 'GS064') == [4]
        !found[0].fixable()
    }

    def 'GS065: more comment than code in a method, once per method; a reason is not counted'() {
        given:
        String text = '''\
class Job {
    int dense(int a) {
        // take a
        int b = a
        // double it
        b = b * 2
        // add one
        b = b + 1
        // log it
        println b
        return b
    }

    int explained(int a) {
        // The remote side rejects zero, so a zero becomes one: the
        // service treats one as "no limit", which is what zero means
        // to our callers, and the conversion must happen before the call.
        int b = a ?: 1
        b = b * 2
        b = b + 1
        println b
        return b
    }

    int brief(int a) {
        // one
        // two
        return a
    }
}
'''

        when:
        def found = comments(text)
        def relaxed = comments(text, with(new EngineConfig.Comments(0.7, 2, 5, true, 0.9, 0.2, true)))

        then:
        lines(found, 'GS065') == [2]
        found.find { it.diagnostic().code() == 'GS065' }.diagnostic().message()
                .contains('4 comment lines (reasons not counted) to 7 code lines in dense (0.57, at most 0.25)')
        lines(relaxed, 'GS065').isEmpty()
    }

    def 'GS065 names a constructor by its class, at the declaration'() {
        when:
        def found = comments('''\
class Job {
    int total
    Job(int a) {
        // keep a
        total = a
        // twice
        total = total * 2
        // plus one
        total++
        // done
    }
}
''')

        then:
        lines(found, 'GS065') == [3]
        found.find { it.diagnostic().code() == 'GS065' }.diagnostic().message().contains('code lines in Job (')
    }

    def 'nothing is reported for a well commented class, a generated constructor or a string that looks like a comment'() {
        expect:
        comments('''\
import groovy.transform.TupleConstructor

/** A job the scheduler runs once a night. */
@TupleConstructor
class Job {
    String name = "// not a comment"
    String other = \'\'\'/* nor this */\'\'\'
    String pattern = /\\/\\/ nor this/
    String dollar = $/ // nor this /$
    String gstring = "${name} // still a string ${ [1].collect { it } }"

    /**
     * Runs the job; the scheduler retries a failure once.
     */
    int run(int attempts) {
        // the scheduler counts from one, not zero
        int first = attempts + 1
        int second = first * 2
        int third = second - first
        int fourth = third * third
        println fourth
        return fourth / 2 as int
    }
}
''').isEmpty()
    }

    def 'severity overrides switch a check off'() {
        expect:
        comments('''\
class Job {
    int run(int counter) {
        // increment the counter
        counter++
        return counter
    }
}
''', with(EngineConfig.Comments.DEFAULTS, [GS060: 'off'])).isEmpty()
    }

    def 'helpers: words, stems and tag repetition'() {
        expect:
        CommentChecks.stem(word) == expected

        where:
        word        | expected
        'counts'    | 'count'
        'counting'  | 'count'
        'counted'   | 'count'
        'entries'   | 'entry'
        'classes'   | 'class'
        'value'     | 'valu'
        'values'    | 'valu'
        'is'        | 'is'
    }

    def 'helpers: a tag description repeats the signature when it is empty, generic or the name again'() {
        expect:
        CommentChecks.repeats(description, ['user', 'name'] as Set) == repeats

        where:
        description                       | repeats
        ''                                | true
        'the user name'                   | true
        '{@code userName}'                | true
        'the <b>name</b> of the user'     | true
        'the login, never blank'          | false
    }

    def 'helpers: code-like comment lines and syntax-only parsing'() {
        expect:
        CommentChecks.codeLike(line) == code

        where:
        line                                  | code
        'int old = counter - 1'               | true
        'println(old)'                        | true
        '}'                                   | true
        'else {'                              | true
        'see the docs (section 3)'            | false
        '- checks spaces only (not tabs)'     | false
        'TODO call(x)'                        | false
        '@Override'                           | false
        'plain words only'                    | false
        ''                                    | false
    }

    def 'helpers: parsing never throws'() {
        expect:
        CommentChecks.parses('int x = 1\nprintln(x)')
        !CommentChecks.parses('if (x) {')
        !CommentChecks.parses('class {')
    }
}
