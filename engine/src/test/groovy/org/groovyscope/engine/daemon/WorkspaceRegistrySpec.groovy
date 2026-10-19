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
package org.groovyscope.engine.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.lsp.GroovyscopeLanguageServer
import spock.lang.Specification
import spock.lang.TempDir

/** A daemon started for one workspace serves that workspace only, under its configuration and trust (1.9). */
class WorkspaceRegistrySpec extends Specification {

    @TempDir
    Path temp

    def 'another root is refused, the same root through a link is served'() {
        given:
        Path mine = Files.createDirectories(temp.resolve('mine'))
        Path other = Files.createDirectories(temp.resolve('other'))
        Path link = Files.createSymbolicLink(temp.resolve('alias'), mine)
        def registry = new WorkspaceRegistry(EngineConfig.DEFAULTS, null, mine)

        when:
        registry.apply(other)

        then:
        def refused = thrown(WorkspaceRegistry.RootRefusedException)
        refused.message.contains("this daemon serves ${mine.toRealPath()}")

        expect:
        registry.apply(mine) != null
        registry.apply(link) != null
        new WorkspaceRegistry(EngineConfig.DEFAULTS, null, null).apply(other) != null
    }

    def 'the language server answers a refused root with invalid params'() {
        given:
        Path mine = Files.createDirectories(temp.resolve('mine'))
        Path other = Files.createDirectories(temp.resolve('other'))
        def registry = new WorkspaceRegistry(EngineConfig.DEFAULTS, null, mine)
        def server = new GroovyscopeLanguageServer({ int code -> }, mine, EngineConfig.DEFAULTS, registry, null)
        def params = new InitializeParams(workspaceFolders: [new WorkspaceFolder(other.toUri().toString(), 'other')])

        when:
        server.initialize(params)

        then:
        def error = thrown(ResponseErrorException)
        error.responseError.message.contains('needs its own')

        when:
        server.check([root: other.toString()]).get()

        then:
        def failed = thrown(java.util.concurrent.ExecutionException)
        failed.cause instanceof ResponseErrorException
    }

    def 'eviction never hands out a closed workspace, and a stale reference fails fast (E-19)'() {
        given:
        Path root = Files.createDirectories(temp.resolve('w'))
        Files.writeString(root.resolve('A.groovy'), 'class A { }')
        Path cache = Files.createDirectories(temp.resolve('cache'))
        def registry = new WorkspaceRegistry(EngineConfig.DEFAULTS, cache)

        when: 'a workspace just handed out is not idle, whatever the threshold'
        def first = registry.apply(root)
        first.snapshot()

        then:
        registry.evictIdle(java.time.Duration.ofMinutes(1)) == 0

        when: 'it is evicted once idle, the next apply gets a fresh one'
        int evicted = registry.evictIdle(java.time.Duration.ZERO)
        def second = registry.apply(root)

        then:
        evicted == 1
        first.isClosed()
        !second.isClosed()
        !second.is(first)
        second.snapshot().version() > 0

        when: 'the old reference is used after eviction'
        first.markDirty()
        first.snapshot()

        then:
        def closed = thrown(IllegalStateException)
        closed.message.contains('closed')

        cleanup:
        registry.evictIdle(java.time.Duration.ZERO)
    }
}
