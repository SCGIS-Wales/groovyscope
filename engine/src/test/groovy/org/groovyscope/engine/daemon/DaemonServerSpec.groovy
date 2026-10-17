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

import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.lsp.RecordingLanguageClient
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.Timeout

/** Two clients on one socket share a workspace; idle and explicit shutdown both end the daemon. */
@Timeout(90)
class DaemonServerSpec extends Specification {

    /** The client-side view of the server including groovyscope's custom methods. */
    interface GroovyscopeRemote extends LanguageServer {
        @JsonRequest('groovyscope/status')
        java.util.concurrent.CompletableFuture<Map<String, Object>> status()

        @JsonNotification('groovyscope/shutdownDaemon')
        void shutdownDaemon()
    }

    Path socket = Paths.get('/tmp', "gs-${UUID.randomUUID().toString().take(8)}.sock")
    CountDownLatch exited = new CountDownLatch(1)
    List<SocketChannel> channels = []

    def cleanup() {
        channels.each { try { it.close() } catch (IOException ignored) { } }
        Files.deleteIfExists(socket)
    }

    private Connection connect() {
        SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))
        channels << channel
        def client = new RecordingLanguageClient()
        Launcher<GroovyscopeRemote> launcher = new LSPLauncher.Builder<GroovyscopeRemote>()
            .setLocalService(client)
            .setRemoteInterface(GroovyscopeRemote)
            .setInput(Channels.newInputStream(channel))
            .setOutput(Channels.newOutputStream(channel))
            .create()
        launcher.startListening()
        new Connection(remote: launcher.remoteProxy, client: client, channel: channel)
    }

    static class Connection {
        GroovyscopeRemote remote
        RecordingLanguageClient client
        SocketChannel channel
    }

    def 'two connections share one workspace and status reports daemon mode'() {
        given:
        def server = new DaemonServer(socket, EngineConfig.DEFAULTS, Duration.ofMinutes(5), exited::countDown)
        server.start()
        String root = Fixtures.project('basic-app').toUri().toString()

        when:
        def first = connect()
        def second = connect()
        first.remote.initialize(new InitializeParams(rootUri: root)).get(20, TimeUnit.SECONDS)
        second.remote.initialize(new InitializeParams(rootUri: root)).get(20, TimeUnit.SECONDS)
        first.remote.initialized(new InitializedParams())
        def symbols = first.remote.workspaceService.symbol(new WorkspaceSymbolParams('Greeter')).get(60, TimeUnit.SECONDS)
        def status = second.remote.status().get(20, TimeUnit.SECONDS)

        then:
        server.connectionCount() == 2
        server.totalConnections() == 2
        server.registry().size() == 1
        symbols.getRight()*.name.contains('Greeter')
        status.daemon == true
        status.root == Fixtures.project('basic-app').toString()
        status.files == 6

        when: 'one client leaves normally'
        first.remote.shutdown().get(20, TimeUnit.SECONDS)
        first.remote.exit()
        waitFor { server.connectionCount() == 1 }

        then:
        server.connectionCount() == 1
        !exited.await(500, TimeUnit.MILLISECONDS)

        when: 'the other asks the daemon to stop'
        second.remote.shutdownDaemon()

        then:
        exited.await(20, TimeUnit.SECONDS)
        !server.isOpen()
        !Files.exists(socket)
    }

    def 'the daemon exits by itself after the idle timeout'() {
        given:
        def server = new DaemonServer(socket, EngineConfig.DEFAULTS, Duration.ofMillis(600), exited::countDown)

        when:
        server.start()

        then:
        exited.await(20, TimeUnit.SECONDS)
        !Files.exists(socket)

        when: 'a stopped daemon cannot be started again'
        server.start()

        then:
        thrown(IllegalStateException)
    }

    def 'a stale socket file is replaced and a second close is harmless'() {
        given:
        Files.writeString(socket, 'stale')
        def server = new DaemonServer(socket, EngineConfig.DEFAULTS, Duration.ofMinutes(5), exited::countDown)

        when:
        server.start()
        def connection = connect()
        def status = connection.remote.status().get(20, TimeUnit.SECONDS)

        then:
        status.engine ==~ /\d+\.\d+\.\d+.*/
        status.root == null

        cleanup:
        server.close()
        server.close()
    }

    def 'workspace keys are short, stable and distinct'() {
        expect:
        WorkspaceRegistry.workspaceKey(Paths.get('/a/b')) == WorkspaceRegistry.workspaceKey(Paths.get('/a/./b'))
        WorkspaceRegistry.workspaceKey(Paths.get('/a/b')) != WorkspaceRegistry.workspaceKey(Paths.get('/a/c'))
        WorkspaceRegistry.workspaceKey(Paths.get('/a/b')).length() == 16
    }

    private static void waitFor(Closure<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(50)
        }
    }
}
