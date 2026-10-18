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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.SymbolStore;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * One {@link LanguageServices} per workspace root, shared by every connection that opens that root, so
 * several Kiro sessions on the same project reuse one compile and one index.
 */
public final class WorkspaceRegistry implements Function<Path, LanguageServices> {

    private volatile EngineConfig config;
    private final Path cacheDir;
    private final Map<Path, LanguageServices> services = new ConcurrentHashMap<>();

    public WorkspaceRegistry(EngineConfig config) {
        this(config, null);
    }

    /** @param cacheDir where per-workspace symbol caches live; {@code null} disables caching */
    public WorkspaceRegistry(EngineConfig config, Path cacheDir) {
        this.config = config;
        this.cacheDir = cacheDir;
    }

    @Override
    public LanguageServices apply(Path root) {
        Path key = root.toAbsolutePath().normalize();
        return services.computeIfAbsent(key, k -> new LanguageServices(new WorkspaceModel(k, config), new GroovyCompiler(), openStore(k)));
    }

    private SymbolStore openStore(Path root) {
        if (cacheDir == null) {
            return null;
        }
        try {
            return SymbolStore.open(cacheDir.resolve(workspaceKey(root)).resolve("symbols.db"));
        } catch (IOException e) {
            org.groovyscope.engine.log.EngineLog.warn("%s", "symbol cache disabled: " + e.getMessage());
            return null;
        }
    }

    /** A short, stable directory name for a workspace root (first 16 hex digits of its SHA-256). */
    public static String workspaceKey(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
        int evicted = 0;
        long threshold = System.nanoTime() - idle.toNanos();
        for (Map.Entry<Path, LanguageServices> entry : services.entrySet()) {
            if (entry.getValue().lastUsedNanos() - threshold < 0 && services.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
                evicted++;
            }
        }
        return evicted;
    }

    public Iterable<Path> roots() {
        return services.keySet();
    }
}
