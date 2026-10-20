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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.SymbolStore;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.workspace.WorkspaceModel;
import org.groovyscope.engine.util.Hashes;

/**
 * One {@link LanguageServices} per workspace root, shared by every connection that opens that root, so
 * several Kiro sessions on the same project reuse one compile and one index.
 */
public final class WorkspaceRegistry implements Function<Path, LanguageServices> {

    private volatile EngineConfig config;
    private final Path cacheDir;
    private final Path onlyRoot;
    private final Map<Path, LanguageServices> services = new ConcurrentHashMap<>();

    /** A workspace other than the one a daemon was started for: its configuration and trust were never read. */
    public static final class RootRefusedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        RootRefusedException(String message) {
            super(message);
        }
    }

    public WorkspaceRegistry(EngineConfig config) {
        this(config, null);
    }

    /** @param cacheDir where per-workspace symbol caches live; {@code null} disables caching */
    public WorkspaceRegistry(EngineConfig config, Path cacheDir) {
        this(config, cacheDir, null);
    }

    /**
     * @param onlyRoot the one workspace this registry serves (its configuration, trust included, is the one given);
     *     {@code null} serves any root
     */
    public WorkspaceRegistry(EngineConfig config, Path cacheDir, Path onlyRoot) {
        this.config = config;
        this.cacheDir = cacheDir;
        this.onlyRoot = onlyRoot == null ? null : canonical(onlyRoot);
    }

    @Override
    public LanguageServices apply(Path root) {
        Path key = root.toAbsolutePath().normalize();
        if (onlyRoot != null && !onlyRoot.equals(canonical(key))) {
            throw new RootRefusedException("this daemon serves " + onlyRoot + " with that workspace's configuration "
                    + "and trust; " + key + " needs its own (run groovyscope there)");
        }
        // Created or touched inside the map's atomic compute, so an eviction of this root either happened before
        // (a fresh workspace is made) or sees it as just used and keeps it (E-19).
        return services.compute(key, (k, existing) -> {
            LanguageServices current = existing == null || existing.isClosed()
                    ? new LanguageServices(new WorkspaceModel(k, config), new GroovyCompiler(), openStore(k))
                    : existing;
            current.touch();
            return current;
        });
    }

    /** The real path when it exists (symbolic links resolved), else the normalised absolute path. */
    static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (java.io.IOException e) {
            return absolute;
        }
    }

    private SymbolStore openStore(Path root) {
        if (cacheDir == null) {
            return null;
        }
        Path file = cacheDir.resolve(workspaceKey(root)).resolve("symbols.db");
        try {
            return SymbolStore.open(file);
        } catch (IOException e) {
            // The store is only a cache: a damaged file (a crash or a full disk mid-write) is set aside and a fresh
            // one built, rather than leaving the cache off for this workspace on every start.
            if (damaged(e) && setAside(file)) {
                org.groovyscope.engine.log.EngineLog.warn("%s", "symbol cache was damaged (" + e.getMessage()
                        + "); kept as symbols.db.damaged and rebuilt");
                try {
                    return SymbolStore.open(file);
                } catch (IOException again) {
                    e = again;
                }
            }
            org.groovyscope.engine.log.EngineLog.warn("%s", "symbol cache disabled: " + e.getMessage());
            return null;
        }
    }

    /**
     * Whether opening failed because the file is not a database or is corrupt, never because another process holds
     * the lock: a busy store is healthy and stays where it is.
     */
    static boolean damaged(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.sqlite.SQLiteException sqlite) {
                org.sqlite.SQLiteErrorCode code = sqlite.getResultCode();
                return code == org.sqlite.SQLiteErrorCode.SQLITE_NOTADB || code == org.sqlite.SQLiteErrorCode.SQLITE_CORRUPT;
            }
        }
        return false;
    }

    /** Moves a store file and its journal files aside; false when there was nothing to move or it could not be. */
    static boolean setAside(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".damaged"), StandardCopyOption.REPLACE_EXISTING);
            for (String journal : List.of("-wal", "-shm")) {
                Files.deleteIfExists(file.resolveSibling(file.getFileName() + journal));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A short, stable directory name for a workspace root (first 16 hex digits of its SHA-256). */
    public static String workspaceKey(Path root) {
        return Hashes.sha256Hex(root.toAbsolutePath().normalize().toString(), 8);
    }

    /** The configuration every workspace currently runs with. */
    public EngineConfig config() {
        return config;
    }

    /**
     * Replaces the configuration for every open workspace and for the ones opened later, without a restart: each
     * workspace drops its caches and recompiles on its next request. Connections keep their open documents.
     */
    public void reload(EngineConfig replacement) {
        config = Objects.requireNonNull(replacement, "replacement");
        for (LanguageServices open : services.values()) {
            open.reconfigure(replacement);
        }
    }

    public int size() {
        return services.size();
    }

    /** Every open workspace drops its derived caches (memory pressure). */
    public void shedAll() {
        for (LanguageServices open : services.values()) {
            open.shed();
        }
    }

    /**
     * Releases every workspace that answered no request for {@code idle} or longer: its loader, trees and index
     * are dropped and its background thread stopped. Returns how many were released. Callers make sure no
     * connection still uses the registry (the daemon calls this only when none is open).
     */
    public int evictIdle(java.time.Duration idle) {
        long threshold = System.nanoTime() - idle.toNanos();
        List<LanguageServices> released = new java.util.ArrayList<>();
        for (Path root : List.copyOf(services.keySet())) {
            // Removed atomically per root against apply(): a workspace handed out meanwhile was touched and stays.
            services.computeIfPresent(root, (k, open) -> {
                if (open.lastUsedNanos() - threshold > 0) {
                    return open;
                }
                released.add(open);
                return null;
            });
        }
        // Closed outside the map: closing waits for background work, which must not hold the map's lock.
        released.forEach(LanguageServices::close);
        return released.size();
    }

    public Iterable<Path> roots() {
        return services.keySet();
    }
}
