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
package org.groovyscope.engine.search

import java.nio.file.Path
import org.codehaus.groovy.ast.builder.AstStringCompiler
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.groovyscope.engine.services.Snapshot
import spock.lang.Specification
import spock.lang.TempDir

/** Clone detection: normalisation, maximal runs, containment and speed. */
class CloneFinderSpec extends Specification {

    @TempDir Path root

    static Statement statement(String source) {
        def nodes = new AstStringCompiler().compile(source, org.codehaus.groovy.control.CompilePhase.CONVERSION, true)
        (nodes[0] as BlockStatement).statements[0]
    }

    List<CloneFinder.Group> clones(Snapshot snapshot, int minLines = 6, int minStatements = 3) {
        CloneFinder.find(snapshot.result().modules(), snapshot.index()::lines, [], minLines, minStatements)
    }

    def 'names and literals normalise away; calls, operators and types stay'() {
        expect:
        CloneFinder.tokens(statement('int total = price * 2')) == ['expr', 'decl', 'int', 'ID', 'bin', '*', 'ID', 'LIT']
        CloneFinder.hash(statement('a = b + 1')) == CloneFinder.hash(statement('x = y + 99'))
        CloneFinder.hash(statement('a = b + 1')) != CloneFinder.hash(statement('a = b - 1'))
        CloneFinder.hash(statement('log.info("x")')) != CloneFinder.hash(statement('log.warn("x")'))
        CloneFinder.hash(statement('String s = v')) != CloneFinder.hash(statement('Integer s = v'))
    }

    def 'every statement and expression shape has tokens'() {
        given:
        String source = '''
            for (String s : list) { if (!s) { continue } else { break } }
            while (x) { x-- }
            do { ++x } while (x < 3)
            try { assert x } catch (IOException e) { throw e } finally { }
            switch (x) { case 1: y = -x; break; default: y = +x }
            synchronized (lock) { z = ~x }
            def (p, q) = [a: 1, *: m]
            r = x ?: y
            r = x ? y : z
            r = a?.b*.c.@d
            r = this.&foo
            r = new int[2]
            r = (1..2) + (1..<2) + [*xs]
            r = { int i -> "$i" }
            r = String
            r = Math.max(1, 2)
            r = (String) x
            r = !(a instanceof String)
            obj?.call()
            list*.call()
            return null
        '''
        def nodes = new AstStringCompiler().compile(source, org.codehaus.groovy.control.CompilePhase.CONVERSION, true)

        when:
        List<String> tokens = (nodes[0] as BlockStatement).statements.collectMany { CloneFinder.tokens(it) }

        then:
        tokens.containsAll(['for', 'String', 'if', '!', 'continue', 'break', 'while', 'post--', 'do',
                            'pre++', 'try', 'assert', 'catch', 'IOException', 'throw', 'switch', 'case', '-',
                            '+', 'synchronized', '~', '[:', ':', '*', '?:', '?', '?.', '*.', '.@', '.&', 'array', '..',
                            '..<', 'closure', 'text', 'class', 'cast', 'bool', '?call', '*call', 'return', 'LIT'])
    }

    def 'maximal runs are reported once, contained runs are dropped and similarity is graded'() {
        given:
        String body = '''
                int total = 0
                for (int i = 0; i < 10; i++) {
                    total += i
                }
                if (total > 5) {
                    total = total * 2
                }
                println total
                return total'''
        String renamed = body.replace('total', 'sum')
        def snapshot = SearchFixtures.compile(root, ['src/main/groovy/A.groovy': """\
            class A {
                int one() {${body}
                }
                int two() {${body}
                }
                int three() {${renamed}
                }
            }
            """.stripIndent()])

        when:
        def groups = clones(snapshot)

        then:
        groups.size() == 1
        groups[0].statements == 5
        groups[0].lines == 9
        !groups[0].exact
        groups[0].occurrences*.enclosing == ['A.one', 'A.two', 'A.three']
        clones(snapshot, 6, 3).size() == 1
        clones(snapshot, 20, 3).isEmpty()
    }

    def 'strings are one text token, so a block pasted with reworded messages is still a copy'() {
        given:
        String template = '''
                String body = mapper.write(values)
                String url = base + '/applications/' + id
                int attempts = maxAttempts
                for (int attempt = 1; attempt <= attempts; attempt++) {
                    int status = patch(url, body, headers)
                    if (status == 204) {
                        log(OK)
                        return
                    } else if ((status == 429 || status == 503) && attempt < attempts) {
                        log(RETRY)
                        Thread.sleep(delay)
                        continue
                    } else {
                        log(FAILED)
                        return
                    }
                }'''
        String stamp = template.replace('OK', '"stamped ${id}"').replace('RETRY', '"HTTP ${status} on PATCH, waiting"')
                .replace('FAILED', '"WARNING: PATCH failed: HTTP ${status}"')
        String unique = template.replace('OK', "'set ' + name").replace('RETRY', '"uniqueName: HTTP ${status} (attempt ${attempt})"')
                .replace('FAILED', '"WARNING: uniqueName PATCH failed for ${id}: HTTP ${status} " + "after ${attempt} attempt(s). " + \'rerun\'')
        def snapshot = SearchFixtures.compile(root, ['src/main/groovy/Plugin.groovy': """\
            class Plugin {
                def mapper; def headers; String base; int maxAttempts; long delay
                int patch(String url, String body, Map headers) { 204 }
                void log(String message) { }
                void stamp(Map values, String id) {${stamp}
                }
                void unique(Map values, String id, String name) {${unique}
                }
            }
            """.stripIndent()])

        when:
        def groups = clones(snapshot)

        then:
        CloneFinder.isText(new org.codehaus.groovy.ast.expr.ConstantExpression('x'))
        !CloneFinder.isText(new org.codehaus.groovy.ast.expr.ConstantExpression(1))
        groups.size() == 1
        groups[0].statements == 4
        !groups[0].exact
        groups[0].occurrences*.enclosing == ['Plugin.stamp', 'Plugin.unique']
    }

    def 'copies inside one block never overlap'() {
        given:
        String repeated = (1..12).collect { '    println x' }.join('\n')
        def snapshot = SearchFixtures.compile(root, ['run.groovy': "def x = 1\n${repeated}\n"])

        when:
        def groups = clones(snapshot, 3, 3)

        then:
        !groups.isEmpty()
        groups.every { group ->
            def occurrences = group.occurrences.toSorted { it.line }
            (0..<occurrences.size() - 1).every { occurrences[it].endLine < occurrences[it + 1].line }
        }
        groups.every { it.exact }
        groups[0].occurrences.every { it.enclosing == 'run' }
    }

    def 'a ten-thousand-line document is analysed in well under a few seconds'() {
        given:
        StringBuilder source = new StringBuilder('class Big {\n')
        500.times { int n ->
            source << "    int m${n}(int a, int b) {\n"
            source << "        int c = a + ${n}\n"
            (n % 7).times { source << "        c = c * b - ${it}\n" }
            source << "        if (c > ${n}) {\n            c = c - b\n        }\n"
            (n % 5).times { source << "        println c + ${it}\n" }
            source << "        return c\n    }\n"
        }
        source << '}\n'
        while (source.readLines().size() < 10_000) {
            source << '// padding\n'
        }
        def snapshot = SearchFixtures.compile(root, ['src/main/groovy/Big.groovy': source.toString()])

        when:
        long started = System.nanoTime()
        def groups = clones(snapshot)
        long millis = (System.nanoTime() - started).intdiv(1_000_000)
        println "duplicates over ${source.readLines().size()} lines: ${groups.size()} groups in ${millis} ms"

        then:
        !groups.isEmpty()
        millis < 3_000
    }
}
