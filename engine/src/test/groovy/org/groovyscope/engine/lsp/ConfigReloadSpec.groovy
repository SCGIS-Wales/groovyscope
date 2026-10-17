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

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.daemon.WorkspaceRegistry
import spock.lang.Specification

/** groovyscope/reloadConfig: a new configuration applies in place, without a restart. */
class ConfigReloadSpec extends Specification {

    static final String PROJECT = 'basic-app'
    static final String WITH_CLASSPATH = '{"workspace": {"classpath": ["lib/extra.jar"]}}'

    GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ }, Fixtures.project(PROJECT), EngineConfig.DEFAULTS)
    String root = Fixtures.project(PROJECT).toString()

    Map call(String method, Map params) {
        server."$method"([root: root] + params).get(60, TimeUnit.SECONDS) as Map
    }

    def 'a reload replaces the classpath, marks the snapshot stale and changes the reported hash'() {
        given:
        call('check', [uris: []])
        def before = server.status().get(10, TimeUnit.SECONDS).configHash

        when:
        def reply = call('reloadConfig', [config: WITH_CLASSPATH])

        then:
        reply.reloaded == true
        reply.configHash != before
        server.status().get(10, TimeUnit.SECONDS).configHash == reply.configHash
        server.services().workspace().config().classpath() == ['lib/extra.jar']
        server.services().isDirty()

        when: 'the same configuration arrives again'
        def again = call('reloadConfig', [config: WITH_CLASSPATH])

        then:
        again.reloaded == false
        again.configHash == reply.configHash
    }

    def 'a malformed or missing configuration is refused with the reason'() {
        when:
        call('reloadConfig', [config: text])

        then:
        def failure = thrown(ExecutionException)
        failure.cause.message.contains(expected)

        where:
        text        | expected
        ''          | 'needs the configuration JSON'
        '{"lint": ' | 'not accepted'
    }

    def 'the daemon registry reloads every open workspace and the ones opened later'() {
        given:
        def registry = new WorkspaceRegistry(EngineConfig.DEFAULTS)
        def open = registry.apply(Fixtures.project(PROJECT))
        def replacement = EngineConfig.fromJson(WITH_CLASSPATH).withConfigHash(EngineConfig.hashOf(WITH_CLASSPATH))

        when:
        registry.reload(replacement)

        then:
        registry.config().is(replacement)
        open.workspace().config().classpath() == ['lib/extra.jar']
        open.isDirty()
        registry.apply(Fixtures.project('search-sample')).workspace().config().classpath() == ['lib/extra.jar']
    }
}
