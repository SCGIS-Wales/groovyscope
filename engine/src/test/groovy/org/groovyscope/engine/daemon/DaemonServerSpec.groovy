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
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
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

    // Short (socket paths are limited to about 100 bytes) and private: the daemon refuses shared directories.
    Path socketDir = Files.createTempDirectory(Paths.get('/tmp'), 'gs')
    Path socket = socketDir.resolve('d.sock')
    CountDownLatch exited = new CountDownLatch(1)
    List<SocketChannel> channels = []

    def cleanup() {
        channels.each { try { it.close() } catch (IOException ignored) { } }
        if (Files.exists(socketDir)) {
            Files.walk(socketDir).sorted(Comparator.reverseOrder()).each { Files.deleteIfExists(it) }
        }
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
        status.configHash == ''

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
        status.memory.usedMb instanceof Number
        status.memory.maxMb >= status.memory.usedMb
        status.load.workerThreads >= 2
        status.load.heavy instanceof Number

        cleanup:
        server.close()
        server.close()
    }

    def 'a missing socket directory is created private; shared or foreign directories are refused'() {
        given:
        Path nested = socketDir.resolve('run/inner')
        Path shared = Files.createDirectory(socketDir.resolve('shared'))
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString('rwxrwxrwx'))
        Path groupWritable = Files.createDirectory(socketDir.resolve('group'))
        Files.setPosixFilePermissions(groupWritable, PosixFilePermissions.fromString('rwxrwx---'))
        Path aFile = Files.writeString(socketDir.resolve('plain'), 'x')

        when:
        DaemonServer.prepareDirectory(nested)

        then:
        PosixFilePermissions.toString(Files.getPosixFilePermissions(nested)) == 'rwx------'

        when: 'an existing private directory is accepted as it is'
        DaemonServer.prepareDirectory(nested)

        then:
        notThrown(IOException)

        when: 'a container user with no account name (an arbitrary UID) is still the owner'
        String name = System.getProperty('user.name')
        System.setProperty('user.name', 'no-such-account-groovyscope')
        try {
            DaemonServer.prepareDirectory(nested)
        } finally {
            System.setProperty('user.name', name)
        }

        then:
        notThrown(IOException)

        when:
        DaemonServer.prepareDirectory(shared)

        then:
        def worldWritable = thrown(IOException)
        worldWritable.message.contains('writable by group or others')

        when:
        DaemonServer.prepareDirectory(groupWritable)

        then:
        thrown(IOException)

        when:
        DaemonServer.prepareDirectory(aFile)

        then:
        def notDirectory = thrown(IOException)
        notDirectory.message.contains('not a directory')

        when: 'the system temporary directory belongs to root and everybody can write to it'
        DaemonServer.prepareDirectory(Paths.get('/tmp'))

        then:
        thrown(IOException)

        when: 'start reports the refusal and can be retried'
        def refused = new DaemonServer(shared.resolve('x.sock'), EngineConfig.DEFAULTS, Duration.ofMinutes(5), exited::countDown)
        refused.start()

        then:
        thrown(IOException)
        !refused.isOpen()
    }

    def 'the socket is private to its owner and a live daemon is never replaced'() {
        given:
        def first = new DaemonServer(socket, EngineConfig.DEFAULTS, Duration.ofMinutes(5), exited::countDown)
        first.start()
        def second = new DaemonServer(socket, EngineConfig.DEFAULTS, Duration.ofMinutes(5), {})

        expect:
        PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)) == 'rw-------'

        when:
        second.start()

        then:
        def refused = thrown(IOException)
        refused.message == "another daemon is serving ${socket}".toString()
        first.isOpen()
        connect().remote.status().get(20, TimeUnit.SECONDS).engine != null

        cleanup:
        first.close()
    }

    def 'closing removes the socket only while it is still the one this daemon bound'() {
        given:
        def server = new DaemonServer(socket, EngineConfig.DEFAULTS, Duration.ofMinutes(5), exited::countDown)
        server.start()

        when: 'another process has replaced the socket file'
        Files.delete(socket)
        Files.writeString(socket, 'someone else')
        server.close()

        then:
        Files.exists(socket)
        Files.readString(socket) == 'someone else'
    }

    def 'the accept loop survives transient failures and stops when the channel closes'() {
        given:
        def outcomes = [new IOException('Too many open files'), new IOException('Too many open files'), 'ok',
                        new ClosedChannelException()]
        def accepted = []
        int calls = 0
        def acceptor = {
            def next = outcomes[calls++]
            if (next instanceof Throwable) {
                throw next
            }
            return SocketChannel.open()
        } as DaemonServer.Acceptor

        when:
        long started = System.nanoTime()
        DaemonServer.acceptLoop(acceptor, { true } as BooleanSupplier, { accepted << it } as Consumer)
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000

        then:
        calls == 4
        accepted.size() == 1
        elapsedMillis >= DaemonServer.MIN_BACKOFF_MILLIS * 3

        cleanup:
        accepted.each { it.close() }
    }

    def 'a failure after the server stopped ends the loop, and the backoff grows to its ceiling'() {
        given:
        boolean running = true
        int calls = 0
        def acceptor = {
            calls++
            running = false
            throw new IOException('closed under us')
        } as DaemonServer.Acceptor

        when:
        DaemonServer.acceptLoop(acceptor, { running } as BooleanSupplier, { } as Consumer)

        then:
        calls == 1
        DaemonServer.nextBackoff(100) == 200
        DaemonServer.nextBackoff(800) == 1000
        DaemonServer.nextBackoff(1000) == 1000
        DaemonServer.nextBackoff(0) == 100
    }

    def 'documents a dropped connection opened stop overriding the disk for other clients'() {
        given:
        def server = new DaemonServer(socket, EngineConfig.DEFAULTS, Duration.ofMinutes(5), exited::countDown)
        server.start()
        String root = Fixtures.project('basic-app').toUri().toString()
        String greeter = Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy')
        def editor = connect()
        def cli = connect()
        editor.remote.initialize(new InitializeParams(rootUri: root)).get(20, TimeUnit.SECONDS)
        cli.remote.initialize(new InitializeParams(rootUri: root)).get(20, TimeUnit.SECONDS)
        def workspace = server.registry().apply(Fixtures.project('basic-app')).workspace()

        when: 'both clients have the document open, one with unsaved text'
        editor.remote.textDocumentService.didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(greeter, 'groovy', 1, 'class Unsaved {}\n')))
        editor.remote.status().get(20, TimeUnit.SECONDS) // a reply means the notification before it was handled
        cli.remote.textDocumentService.didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(greeter, 'groovy', 1, 'class Unsaved {}\n')))
        cli.remote.status().get(20, TimeUnit.SECONDS)
        cli.remote.textDocumentService.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(greeter)))
        cli.remote.status().get(20, TimeUnit.SECONDS)

        then: 'one client closing it leaves the other client\'s overlay'
        workspace.openText(greeter) == 'class Unsaved {}\n'

        when: 'the editor goes away without closing anything'
        editor.channel.close()
        waitFor { server.connectionCount() == 1 }

        then:
        !workspace.isOpen(greeter)
        workspace.text(greeter) == Fixtures.text('basic-app', 'src/main/groovy/app/Greeter.groovy')

        cleanup:
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
