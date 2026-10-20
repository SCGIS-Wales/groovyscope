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

import java.nio.file.Path
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.search.SearchFixtures
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The Phase 5 checks of PracticeFinder's groups: GS012 (wildcards), GS017, GS018, GS019, GS028, GS029, GS034 to GS037.
 * Each fixture line that should be reported ends with a comment naming its codes.
 */
class GeneralChecksSpec extends Specification {

    static final Set<String> CODES = ['GS012', 'GS017', 'GS018', 'GS019', 'GS028', 'GS029', 'GS034', 'GS035', 'GS036',
                                      'GS037'] as Set

    @Shared
    LanguageServices services = Fixtures.services('checks-sample')

    @Shared @TempDir Path root

    private static String uri(String name) {
        Fixtures.uri('checks-sample', 'src/main/groovy/sample/' + name)
    }

    private static Set<List> marked(String text) {
        Set<List> expected = [] as Set
        text.readLines().eachWithIndex { String line, int index ->
            (line =~ /\/\/ (GS\d{3}(?: GS\d{3})*)\s*$/).with { matcher ->
                if (matcher.find()) {
                    matcher.group(1).split(' ').each { expected << [it, index + 1] }
                }
            }
        }
        expected
    }

    private List<PracticeFinder.Practice> found(String name) {
        services.quality().practices(uri(name)).findAll { it.diagnostic().code() in CODES }
    }

    def 'exactly the marked lines are reported in #file'() {
        given:
        String text = Fixtures.text('checks-sample', 'src/main/groovy/sample/' + file)

        expect:
        found(file).collect { [it.diagnostic().code(), it.diagnostic().range().start().line() + 1] } as Set == marked(text)

        where:
        file << ['SqlUse.groovy', 'Pitfalls.groovy']
    }

    def 'severities follow what is proven'() {
        given:
        def severity = { String file, String code, String fragment ->
            found(file).find { it.diagnostic().code() == code && it.diagnostic().message().contains(fragment) }
                    .diagnostic().severity()
        }

        expect: 'a proven Sql receiver is an error; a boxed number in a condition a warning, a primitive information'
        severity('SqlUse.groovy', 'GS035', 'concatenat') == Diagnostic.Severity.ERROR
        severity('Pitfalls.groovy', 'GS028', "'count'") == Diagnostic.Severity.WARNING
        severity('Pitfalls.groovy', 'GS028', "'size'") == Diagnostic.Severity.INFORMATION
    }

    def 'a LIKE wildcard around an interpolation is reported without the quote-removing fix'() {
        when:
        def like = found('SqlUse.groovy').find { it.diagnostic().code() == 'GS012' }

        then:
        like.fix().empty
        like.diagnostic().message().contains("'%' + part + '%'")
    }

    def 'GString lookups get a toString fix'() {
        when:
        def fix = found('Pitfalls.groovy').find { it.diagnostic().code() == 'GS018' }.fix()

        then:
        fix*.newText() == ['"${first}".toString()']
    }

    // Secrets are written at run time so the repository never holds a string a secret scanner would flag.
    static final String GITHUB = 'gh' + 'p_' + ('a1B2c3D4e5' * 4).substring(0, 36)
    static final String AWS = 'AK' + 'IA' + 'ABCDEFGHIJKLMNOP'
    static final String JWT = 'ey' + 'JhbGciOiJIUzI1NiJ9.ey' + 'JzdWIiOiJhYmMifQ.c2lnbmF0dXJlLXZhbHVl'
    static final String KEY = '-----BEGIN ' + 'PRIVATE KEY-----'

    static String secrets() {
        """\
package sample

class Settings {
    String password = 'correct-horse-battery'
    String token = '${GITHUB}'
    static final String AWS_ID = '${AWS}'
    String session = '${JWT}'
    String key = '${KEY}'
    String label = 'plain text'
    String passwordHint = ''
    Map<String, String> login = [user: 'admin', secret: 'opensesame-123']

    void connect() {
        def apiKey = 'k-12345678'
        String fromEnv = System.getenv('API_KEY')
    }
}
""".stripIndent()
    }

    def 'hardcoded secrets: patterns are errors, names only information, and the allow list is honoured'() {
        given:
        def snapshot = SearchFixtures.compile(root, ['src/main/groovy/sample/Settings.groovy': secrets(),
                                                    'src/test/groovy/SettingsSpec.groovy': "class SettingsSpec { String password = 'test-password-1' }"])
        def run = { String name, EngineConfig config ->
            String uri = SearchFixtures.uri(root, name)
            PracticeFinder.find(uri, snapshot.result().modules().get(uri), snapshot.result().sources().get(uri), config)
                    .findAll { it.diagnostic().code() == 'GS019' }
        }
        def allow = EngineConfig.fromJson('{"secrets": {"allow": ["^correct-horse", "(?i)^apiKey$"]}}')

        when:
        def found = run('src/main/groovy/sample/Settings.groovy', EngineConfig.DEFAULTS)
        def byLine = found.collectEntries { [(it.diagnostic().range().start().line() + 1): it.diagnostic()] }

        then:
        byLine.keySet() == [4, 5, 6, 7, 8, 11, 14] as Set
        [5, 6, 7, 8].every { byLine[it].severity() == Diagnostic.Severity.ERROR }
        [4, 11, 14].every { byLine[it].severity() == Diagnostic.Severity.INFORMATION }
        byLine[5].message().contains('GitHub')
        !found.any { it.diagnostic().message().contains(GITHUB) }

        and: 'allowed values and names are left alone'
        run('src/main/groovy/sample/Settings.groovy', allow).collect { it.diagnostic().range().start().line() + 1 } as Set ==
                [5, 6, 7, 8, 11] as Set

        and: 'test sources get no name-based findings'
        run('src/test/groovy/SettingsSpec.groovy', EngineConfig.DEFAULTS).empty
    }
}
