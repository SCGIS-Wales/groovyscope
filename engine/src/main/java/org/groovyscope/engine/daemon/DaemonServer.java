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
package org.groovyscope.engine.daemon;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.lsp.GroovyscopeLanguageServer;

/**
 * The warm engine: one JVM listening on a Unix domain socket, speaking LSP to every client that connects.
 * Each connection gets its own protocol front end; workspaces are shared through a {@link WorkspaceRegistry}.
 * The process exits when no client has been connected for the idle timeout, or when a client asks it to.
 */
public final class DaemonServer implements Closeable {

    private final Path socketPath;
    private final WorkspaceRegistry registry;
    private final EngineConfig config;
    private final Duration idleTimeout;
    private final Runnable onExit;
    private final Instant started = Instant.now();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>(Instant.now());
    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicBoolean exitRequested = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private ServerSocketChannel server;
    private Thread acceptor;
    private ScheduledExecutorService watchdog;

    /**
     * @param socketPath where to listen; a stale file at that path is removed first
     * @param config the engine configuration shared by every workspace
     * @param idleTimeout how long to stay alive with no connections; zero or negative exits at once
     * @param onExit called once, from a daemon thread, when the server decides to stop
     */
    public DaemonServer(Path socketPath, EngineConfig config, Duration idleTimeout, Runnable onExit) {
        this(socketPath, config, idleTimeout, onExit, null);
    }

    /** @param cacheDir where per-workspace symbol caches live; {@code null} disables caching */
    public DaemonServer(Path socketPath, EngineConfig config, Duration idleTimeout, Runnable onExit, Path cacheDir) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
        this.config = Objects.requireNonNull(config, "config");
        this.registry = new WorkspaceRegistry(config, cacheDir);
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.onExit = Objects.requireNonNull(onExit, "onExit");
    }

    /** Binds the socket and starts accepting connections. */
    public void start() throws IOException {
        if (stopped.get()) {
            throw new IllegalStateException("daemon already stopped");
        }
        if (!open.compareAndSet(false, true)) {
            throw new IllegalStateException("daemon already started");
        }
        Files.createDirectories(socketPath.toAbsolutePath().getParent());
        Files.deleteIfExists(socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
        acceptor = new Thread(this::acceptLoop, "groovyscope-daemon-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "groovyscope-daemon-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        long periodMillis = Math.max(250, Math.min(15_000, idleTimeout.toMillis() / 4));
        watchdog.scheduleAtFixedRate(this::checkIdle, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        System.err.println("groovyscope-engine: daemon listening on " + socketPath);
    }

    private void acceptLoop() {
        while (open.get()) {
            SocketChannel channel;
            try {
                channel = server.accept();
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (open.get()) {
                    System.err.println("groovyscope-engine: accept failed: " + e);
                }
                return;
            }
            connections.incrementAndGet();
            totalConnections.incrementAndGet();
            lastActivity.set(Instant.now());
            Thread worker = new Thread(() -> serve(channel), "groovyscope-conn-" + totalConnections.get());
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(SocketChannel channel) {
        try (InputStream in = Channels.newInputStream(channel); OutputStream out = Channels.newOutputStream(channel)) {
            GroovyscopeLanguageServer front = new GroovyscopeLanguageServer(
                    code -> closeQuietly(channel), socketPath.toAbsolutePath().getParent(), config, registry,
                    this::requestExit);
            Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(front, in, out);
            front.connect(launcher.getRemoteProxy());
            launcher.startListening().get();
        } catch (IOException | ExecutionException e) {
            // The client went away; nothing to report.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(channel);
            connections.decrementAndGet();
            lastActivity.set(Instant.now());
        }
    }

    private void checkIdle() {
        if (connections.get() > 0) {
            return;
        }
        Duration idle = Duration.between(lastActivity.get(), Instant.now());
        if (idle.compareTo(idleTimeout) >= 0) {
            System.err.println("groovyscope-engine: idle for " + idle.toSeconds() + "s, exiting");
            requestExit();
        }
    }

    /** Stops accepting and signals the owner to exit; safe to call more than once. */
    public void requestExit() {
        if (exitRequested.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> {
                close();
                onExit.run();
            }, "groovyscope-daemon-exit");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public int connectionCount() {
        return connections.get();
    }

    public long totalConnections() {
        return totalConnections.get();
    }

    public Path socketPath() {
        return socketPath;
    }

    public Instant started() {
        return started;
    }

    public WorkspaceRegistry registry() {
        return registry;
    }

    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        stopped.set(true);
        if (!open.compareAndSet(true, false)) {
            return;
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                // nothing useful to do
            }
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            // nothing useful to do
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // already closed
        }
    }
}
