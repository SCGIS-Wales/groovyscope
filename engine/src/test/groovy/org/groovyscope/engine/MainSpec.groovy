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
package org.groovyscope.engine

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.function.IntConsumer
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class MainSpec extends Specification {

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream()
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream()
    PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8)
    PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)
    List<Integer> exits = []
    IntConsumer exitHandler = { int code -> exits << code }

    int run(String... args) {
        Main.run(args, new ByteArrayInputStream(new byte[0]), out, err, Paths.get(''), exitHandler)
    }

    String stdout() { outBytes.toString(StandardCharsets.UTF_8) }

    String stderr() { errBytes.toString(StandardCharsets.UTF_8) }

    def '--version prints the engine version'() {
        expect:
        run('--version') == 0
        stdout().trim() ==~ /\d+\.\d+\.\d+.*/
        stderr().empty
    }

    def 'help is printed for #label'() {
        expect:
        run(args as String[]) == 0
        stdout().contains('usage: groovyscope-engine')

        where:
        label        | args
        'no args'    | []
        '--help'     | ['--help']
        '-h'         | ['-h']
    }

    def 'unknown arguments exit 2 with usage on stderr'() {
        expect:
        run('frobnicate', '--now') == 2
        stdout().empty
        stderr().contains('unknown arguments: frobnicate --now')
        stderr().contains('usage: groovyscope-engine')
    }

    def 'lsp --stdio returns 0 when the input stream closes immediately'() {
        expect:
        run('lsp', '--stdio') == 0
        exits.empty
    }

    def 'lsp --stdio redirects System.out to stderr while serving'() {
        given:
        PrintStream original = System.out

        when:
        run('lsp', '--stdio')

        then:
        System.out.is(original)
    }
}
