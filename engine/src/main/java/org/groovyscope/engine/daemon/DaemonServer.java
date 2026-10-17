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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
 *
 * <p>The socket lives in a directory only its owner can enter (created {@code 0700}; an existing directory that
 * another user owns, or that group or others can write, is refused) and is itself {@code 0600}. A socket a live
 * daemon still answers on is never replaced, and on exit only the socket this instance bound is removed.
 */
public final class DaemonServer implements Closeable {

    /** First pause after a failed accept; doubled on each further failure up to {@link #MAX_BACKOFF_MILLIS}. */
    static final long MIN_BACKOFF_MILLIS = 100;
    static final long MAX_BACKOFF_MILLIS = 1_000;

    /** Accepts one connection; the seam lets tests drive the accept loop. */
    @FunctionalInterface
    interface Acceptor {
        SocketChannel accept() throws IOException;
    }

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
    private volatile Object boundFileKey;

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
        try {
            prepareDirectory(socketPath.toAbsolutePath().getParent());
            removeStaleSocket(socketPath);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            boundFileKey = fileKey(socketPath);
            restrictToOwner(socketPath);
        } catch (IOException | RuntimeException e) {
            open.set(false);
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // the original failure is the one worth reporting
                }
                if (boundFileKey != null) {
                    Files.deleteIfExists(socketPath);
                }
                server = null;
            }
            throw e;
        }
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
        org.groovyscope.engine.log.EngineLog.info("%s", "daemon listening on " + socketPath);
    }

    /**
     * Creates the socket directory {@code 0700} when missing; an existing one must belong to this user and must
     * not be writable by group or others, or another user could replace the socket. File systems without POSIX
     * permissions are not checked.
     */
    static void prepareDirectory(Path directory) throws IOException {
        boolean posix = isPosix(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (posix) {
                Files.createDirectories(directory,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(directory);
            }
            return;
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("socket directory " + directory + " is not a directory");
        }
        if (!posix) {
            return;
        }
        PosixFileAttributes attributes = Files.readAttributes(directory, PosixFileAttributes.class);
        UserPrincipal me = directory.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        if (!attributes.owner().equals(me)) {
            throw new IOException("socket directory " + directory + " is owned by " + attributes.owner().getName()
                    + ", not " + me.getName() + "; refusing to start");
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("socket directory " + directory + " is writable by group or others ("
                    + PosixFilePermissions.toString(permissions) + "); refusing to start");
        }
    }

    /** Removes a leftover socket file, unless a live daemon still answers on it. */
    static void removeStaleSocket(Path socket) throws IOException {
        if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        boolean live;
        try {
            SocketChannel probe = SocketChannel.open(UnixDomainSocketAddress.of(socket));
            probe.close();
            live = true;
        } catch (IOException e) {
            // Nothing listens: the file is left over from a daemon that died.
            live = false;
        }
        if (live) {
            throw new IOException("another daemon is serving " + socket);
        }
        Files.deleteIfExists(socket);
    }

    private static void restrictToOwner(Path socket) throws IOException {
        if (isPosix(socket)) {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static boolean isPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** The file system identity of a path (inode and device on Unix), or {@code null} when unavailable. */
    static Object fileKey(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    private void acceptLoop() {
        acceptLoop(server::accept, () -> open.get() && server.isOpen(), this::handle);
    }

    /**
     * Accepts until {@code running} turns false. A failed accept (too many open files, an interrupted system
     * call) is logged and retried after a pause that grows from {@link #MIN_BACKOFF_MILLIS} to
     * {@link #MAX_BACKOFF_MILLIS}; only a closed server channel ends the loop.
     */
    static void acceptLoop(Acceptor acceptor, BooleanSupplier running, Consumer<SocketChannel> handler) {
        long backoff = MIN_BACKOFF_MILLIS;
        while (running.getAsBoolean()) {
            SocketChannel channel;
            try {
                channel = acceptor.accept();
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (!running.getAsBoolean()) {
                    return;
                }
                org.groovyscope.engine.log.EngineLog.warn("%s", "accept failed, retrying in " + backoff + " ms: " + e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = nextBackoff(backoff);
                continue;
            }
            backoff = MIN_BACKOFF_MILLIS;
            handler.accept(channel);
        }
    }

    static long nextBackoff(long current) {
        return Math.min(MAX_BACKOFF_MILLIS, Math.max(MIN_BACKOFF_MILLIS, current * 2));
    }

    private void handle(SocketChannel channel) {
        connections.incrementAndGet();
        totalConnections.incrementAndGet();
        lastActivity.set(Instant.now());
        Thread worker = new Thread(() -> serve(channel), "groovyscope-conn-" + totalConnections.get());
        worker.setDaemon(true);
        worker.start();
    }

    private void serve(SocketChannel channel) {
        GroovyscopeLanguageServer front = null;
        try (InputStream in = Channels.newInputStream(channel); OutputStream out = Channels.newOutputStream(channel)) {
            front = new GroovyscopeLanguageServer(
                    code -> closeQuietly(channel), socketPath.toAbsolutePath().getParent(), config, registry,
                    this::requestExit);
            Launcher<LanguageClient> launcher = new LSPLauncher.Builder<LanguageClient>().setLocalService(front)
                    .setRemoteInterface(LanguageClient.class).setInput(in).setOutput(out)
                    .wrapMessages(org.groovyscope.engine.lsp.GroovyscopeLanguageServer.messageLogging()).create();
            front.connect(launcher.getRemoteProxy());
            launcher.startListening().get();
        } catch (IOException | ExecutionException e) {
            // The client went away; nothing to report.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (front != null) {
                try {
                    // The client is gone however it left: release its open documents and diagnostics thread.
                    front.connectionClosed();
                } catch (RuntimeException e) {
                    org.groovyscope.engine.log.EngineLog.warn("%s", "connection cleanup failed: " + e);
                }
            }
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
            org.groovyscope.engine.log.EngineLog.info("%s", "idle for " + idle.toSeconds() + "s, exiting");
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
        Object bound = boundFileKey;
        Object current = fileKey(socketPath);
        if (bound != null && !bound.equals(current)) {
            // Another daemon has bound the path since: its socket is not ours to remove.
            return;
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
