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
import org.groovyscope.engine.search.SearchFixtures
import org.groovyscope.engine.services.Snapshot
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** GS020 to GS027: names judged against the declared types and what the code does. */
class NamingFinderSpec extends Specification {

    @Shared @TempDir Path root
    @Shared Snapshot snapshot

    static final String SOURCE = '''\
package naming

class RemoveAllOnSelfRule {
    String name = 'RemoveAllOnSelf'
}

class ValidateInput {
    private boolean valid = false
    boolean isReady = true
    String retryCount = '3'
    String certificateExpiry = '2026-10-19T00:00:00Z'
    Map<String, String> userList = [:]
    List<String> names = []
    String token
    def data = [:]
    String strName = 'x'
    int cfgSize = 1

    String getToken() {
        token = 'new'
        return token
    }

    void orderTotal() { token = 'x' }
    String orderName() { 'x' }
    String getUser() { 'u' }
    String fetchAccount() { 'a' }

    void run(Map input) {
        def q = 1
        for (int i = 0; i < 2; i++) { }
        [1].each { x -> }
        String mgr = 'm'
    }
}

class OrderManager { }
'''

    static final String CLEAN = '''\
package naming

class InputValidator {
    boolean isValid = false
    // a property: `active` already reads as isActive(), and renaming it would give isIsActive()
    boolean active = true
    // idiomatic shorthand every reader knows
    String libDir = 'lib'
    String docRoot = 'docs'
    String orgName = 'acme'
    String prevVersion = '1'
    String subPath = 'a'
    String implClass = 'B'
    String initScript = 'c'
    int retryCount = 3
    Map<String, User> usersById = [:]

    boolean hasRetriesLeft() { retryCount > 0 }
    String getUser() { 'u' }
    String getAccount() { 'a' }
}

class User { }
'''

    def setupSpec() {
        snapshot = SearchFixtures.compile(root, ['naming/Messy.groovy': SOURCE, 'naming/Clean.groovy': CLEAN])
    }

    List<PracticeFinder.Practice> findings(String uri, EngineConfig config = EngineConfig.DEFAULTS) {
        NamingFinder.find(uri, snapshot.result().modules().get(uri), config)
    }

    def 'messy names get one finding each, by code'() {
        when:
        def found = findings(SearchFixtures.uri(root, 'naming/Messy.groovy'))
        def byCode = found.groupBy { it.diagnostic().code() }

        then:
        byCode.GS020*.diagnostic()*.message().any { it.contains("'valid'") }
        byCode.GS021*.diagnostic()*.message().any { it.contains("'getToken'") }
        byCode.GS022*.diagnostic()*.message().any { it.contains("'ValidateInput'") }
        !byCode.GS022*.diagnostic()*.message().any { it.contains("'RemoveAllOnSelfRule'") }
        byCode.GS022*.diagnostic()*.message().any { it.contains("'orderTotal'") }
        !found.any { it.diagnostic().message().contains("'orderName'") }
        byCode.GS023*.diagnostic()*.message().any { it.contains("'data'") }
        byCode.GS023*.diagnostic()*.message().any { it.contains("'OrderManager'") }
        byCode.GS024*.diagnostic()*.message().any { it.contains("'mgr'") }
        byCode.GS024*.diagnostic()*.message().any { it.contains("'q'") }
        byCode.GS024*.diagnostic()*.message().any { it.contains("'cfgSize'") }
        byCode.GS025*.diagnostic()*.message().any { it.contains("'strName'") }
        byCode.GS026*.diagnostic()*.message().any { it.contains("'retryCount'") }
        byCode.GS026*.diagnostic()*.message().any { it.contains("'userList'") }
        // Timestamp text carried on purpose is a naming question, so information rather than a warning.
        byCode.GS026.find { it.diagnostic().message().contains("'certificateExpiry'") }.diagnostic().severity() ==
                org.groovyscope.engine.model.Diagnostic.Severity.INFORMATION
        byCode.GS026.find { it.diagnostic().message().contains("'certificateExpiry'") }.diagnostic().message()
                .contains('certificateExpiryText')
        byCode.GS026.find { it.diagnostic().message().contains("'retryCount'") }.diagnostic().severity() !=
                org.groovyscope.engine.model.Diagnostic.Severity.INFORMATION
        byCode.GS027*.diagnostic()*.message().any { it.contains('get, fetch') }
        !found.any { it.diagnostic().message().contains("'isReady'") }
        !found.any { it.diagnostic().message().contains("'i'") || it.diagnostic().message().contains("'x'") }
        found.every { it.diagnostic().source() == 'groovyscope' && it.diagnostic().range() != null }
    }

    def 'clean names produce nothing'() {
        expect:
        findings(SearchFixtures.uri(root, 'naming/Clean.groovy')).isEmpty()
    }

    def 'the configuration allows abbreviations, records vocabulary and switches the checks off'() {
        given:
        def base = EngineConfig.DEFAULTS
        def tuned = base.toBuilder().practices(new EngineConfig.Practices(true, [:],
                new EngineConfig.Naming(true, ['cfg', 'mgr'], [fetch: 'get']))).build()
        def off = base.toBuilder().practices(new EngineConfig.Practices(true, [:],
                new EngineConfig.Naming(false, [], [:]))).build()

        when:
        def allowed = findings(SearchFixtures.uri(root, 'naming/Messy.groovy'), tuned)
        def none = findings(SearchFixtures.uri(root, 'naming/Messy.groovy'), off)

        then:
        !allowed.any { it.diagnostic().message().contains("'mgr'") || it.diagnostic().message().contains("'cfgSize'") }
        allowed.any { it.diagnostic().code() == 'GS027' && it.diagnostic().message().contains("vocabulary says 'get'") }
        none.isEmpty()
    }

    def 'words and boolean helpers'() {
        expect:
        NamingFinder.words('resolveApiAssignments') == ['resolve', 'api', 'assignments']
        NamingFinder.words('HTTPClient') == ['http', 'client']
        NamingFinder.words('some_snake_name') == ['some', 'snake', 'name']
        NamingFinder.words('') == []
        NamingFinder.looksBoolean('isValid')
        !NamingFinder.looksBoolean('is')
        !NamingFinder.looksBoolean('validate')
    }

    def 'naming findings arrive through the practice finder and the JSON configuration'() {
        given:
        def config = EngineConfig.fromJson('{"naming": {"enabled": true, "allowedAbbreviations": ["svc"], "vocabulary": {"fetch": "get"}}}')

        expect:
        config.practices().naming().allowedAbbreviations() == ['svc']
        config.practices().naming().vocabulary() == [fetch: 'get']
        EngineConfig.fromJson('{}').practices().naming().enabled()
        PracticeFinder.find(SearchFixtures.uri(root, 'naming/Messy.groovy'),
                snapshot.result().modules().get(SearchFixtures.uri(root, 'naming/Messy.groovy')), SOURCE, config)
                .any { it.diagnostic().code().startsWith('GS02') }
    }
}
