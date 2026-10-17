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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.IntConsumer
import org.groovyscope.engine.config.EngineConfig
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

@Timeout(30)
class MainSpec extends Specification {

    @TempDir
    Path temp

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream()
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream()
    PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8)
    PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)
    List<Integer> exits = []
    IntConsumer exitHandler = { int code -> exits << code }
    Map<String, String> environment = [:]

    int run(String... args) {
        Main.run(args, new ByteArrayInputStream(new byte[0]), out, err, Paths.get(''), exitHandler, environment)
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

    def 'configuration comes from the environment or a file'() {
        given:
        Path file = temp.resolve('config.json')
        Files.writeString(file, '{"workspace": {"trust": true, "sourceRoots": ["src"]}}')

        expect:
        Main.configFrom([], [(Main.CONFIG_ENV): '{"workspace": {"classpath": ["a.jar"]}}'], temp).classpath() == ['a.jar']
        Main.configFrom(['--config', file.toString()], [:], temp).with { trust() && sourceRoots() == ['src'] }
        Main.configFrom([], [:], temp) == EngineConfig.DEFAULTS
        Main.configFrom([], null, temp) == EngineConfig.DEFAULTS
    }

    def 'the configuration hash is the SHA-256 of the raw environment value, empty without one'() {
        given:
        String json = '{"workspace": {"maxFileBytes": 10}}'

        expect:
        Main.configFrom([], [(Main.CONFIG_ENV): json], temp).configHash() ==
                java.security.MessageDigest.getInstance('SHA-256').digest(json.getBytes('UTF-8')).encodeHex().toString()
        Main.configFrom([], [(Main.CONFIG_ENV): json], temp).maxFileBytes() == 10
        Main.configFrom([], [:], temp).configHash() == ''
        EngineConfig.hashOf(null) == ''
    }

    def 'bad configuration options exit 2 with a message'() {
        expect:
        run('lsp', '--stdio', '--config') == 2
        stderr().contains('--config needs a value')

        and:
        run('lsp', '--stdio', '--config', temp.resolve('missing.json').toString()) == 2

        and:
        run('lsp', '--stdio', '--bogus') == 2
        stderr().contains('unknown option: --bogus')
    }

    def 'daemon mode needs a socket and exits on its own when idle'() {
        given:
        Path socket = Files.createTempDirectory(Paths.get('/tmp'), 'gs').resolve('main.sock')

        expect:
        run('daemon') == 2
        stderr().contains('needs --socket')
        run('daemon', '--socket', socket.toString(), '--idle-seconds', 'soon') == 2
        stderr().contains('whole number')
        run('daemon', '--socket', socket.toString(), '--idle-seconds', '0', '--cache-dir', temp.resolve('cache').toString()) == 0
        !Files.exists(socket)

        cleanup:
        Files.deleteIfExists(socket)
        Files.deleteIfExists(socket.parent)
    }

    def 'lsp options accept a cache directory'() {
        expect:
        Main.parseOptions(['--cache-dir', 'cache'], [:], temp).cacheDir() == temp.resolve('cache')
        Main.parseOptions([], [:], temp).idleSeconds() == Main.DEFAULT_IDLE_SECONDS
        run('lsp', '--stdio', '--cache-dir', temp.resolve('cache').toString()) == 0
    }

    def 'invalid configuration JSON in the environment exits 2'() {
        given:
        environment[Main.CONFIG_ENV] = '{nope'

        expect:
        run('lsp', '--stdio') == 2
        stderr().contains('invalid engine configuration JSON')
    }
}
