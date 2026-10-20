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
package org.groovyscope.engine.lsp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.groovyscope.engine.concurrent.MemoryGuard;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.log.EngineLog;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.version.EngineVersion;

/** {@code groovyscope/status} and {@code groovyscope/reloadConfig}: the engine's state and its configuration. */
final class StatusRequests {

    private final RequestExecutors requests;
    private final Supplier<LanguageServices> services;
    private final Supplier<Path> workspaceRoot;
    private final boolean daemon;
    private final Supplier<EngineConfig> liveConfig;
    private final Consumer<EngineConfig> reloader;

    StatusRequests(RequestExecutors requests, Supplier<LanguageServices> services, Supplier<Path> workspaceRoot,
            boolean daemon, Supplier<EngineConfig> liveConfig, Consumer<EngineConfig> reloader) {
        this.requests = requests;
        this.services = services;
        this.workspaceRoot = workspaceRoot;
        this.daemon = daemon;
        this.liveConfig = liveConfig;
        this.reloader = reloader;
    }

    /** Daemon and engine state for the Python front end and {@code groovyscope doctor}. */
    CompletableFuture<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("engine", EngineVersion.current());
        status.put("codenarc", EngineVersion.codenarc());
        Path root = workspaceRoot.get();
        status.put("root", root == null ? null : root.toString());
        status.put("daemon", daemon);
        status.put("configHash", liveConfig.get().configHash());
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        memory.put("maxMb", runtime.maxMemory() / (1024 * 1024));
        memory.put("pressure", MemoryGuard.pressure());
        memory.put("oomExit", MemoryGuard.exitsOnOutOfMemory());
        status.put("memory", memory);
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("heavy", requests.heavyLoad());
        load.put("workerThreads", requests.heavyThreads());
        status.put("load", load);
        LanguageServices current = services.get();
        if (current != null) {
            // Answered from what is already known: a probe must stay cheap on a busy daemon, so no tree walk here.
            status.put("dirty", current.isDirty());
            status.put("files", current.fileCount());
            status.put("trust", current.workspace().config().trust());
        }
        return CompletableFuture.completedFuture(status);
    }

    /**
     * Replaces the engine configuration in place: the front end sends the same effective JSON it would hand over
     * at start (a re-exported classpath, an edited {@code .groovyscope.yaml}), every open workspace drops its
     * caches and recompiles on its next request, and {@code status} reports the new {@code configHash}. Params:
     * {@code config} (the JSON text). Returns {@code configHash} and {@code reloaded}.
     */
    CompletableFuture<Map<String, Object>> reloadConfig(Map<String, Object> params) {
        return requests.light(cancel -> {
            Object json = params == null ? null : params.get("config");
            if (!(json instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("reloadConfig needs the configuration JSON in `config`");
            }
            EngineConfig replacement;
            try {
                replacement = EngineConfig.fromJson(text).withConfigHash(EngineConfig.hashOf(text));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("configuration not accepted: " + e.getMessage(), e);
            }
            boolean changed = !replacement.configHash().equals(liveConfig.get().configHash());
            if (changed) {
                reloader.accept(replacement);
                EngineLog.info("configuration reloaded (%s)", replacement.configHash());
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("configHash", liveConfig.get().configHash());
            response.put("reloaded", changed);
            return response;
        });
    }
}
