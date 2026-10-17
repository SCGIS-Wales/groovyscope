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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.groovyscope.engine.services.LanguageServices;

/**
 * Pushes diagnostics to the client after edits settle. Every compiled document gets a notification, and
 * documents that disappeared get an empty one, so the client never shows stale problems.
 */
final class DiagnosticsPublisher {

    static final long DEBOUNCE_MILLIS = 300;

    private final Supplier<LanguageServices> services;
    private final Supplier<LanguageClient> client;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "groovyscope-diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, List<org.groovyscope.engine.model.Diagnostic>> lastPublished = new java.util.HashMap<>();
    private ScheduledFuture<?> pending;
    private volatile boolean stopped;

    DiagnosticsPublisher(Supplier<LanguageServices> services, Supplier<LanguageClient> client) {
        this.services = services;
        this.client = client;
    }

    /** Publishes after {@link #DEBOUNCE_MILLIS} of quiet; an earlier pending publish is replaced. */
    synchronized void schedule() {
        schedule(DEBOUNCE_MILLIS);
    }

    synchronized void schedule(long delayMillis) {
        if (pending != null) {
            pending.cancel(false);
        }
        if (executor.isShutdown()) {
            return; // the connection has ended or shut down; nobody is listening
        }
        try {
            pending = executor.schedule(this::publishNow, delayMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pending = null;
        }
    }

    /** True once {@link #shutdown()} has stopped the publishing thread. */
    boolean isShutdown() {
        return executor.isShutdown();
    }

    /** Compiles if needed and publishes every document's diagnostics now. */
    void publishNow() {
        LanguageServices current = services.get();
        LanguageClient target = client.get();
        if (current == null || target == null || stopped) {
            return;
        }
        try {
            Map<String, List<org.groovyscope.engine.model.Diagnostic>> all = current.quality().publishedDiagnostics();
            Map<String, List<org.groovyscope.engine.model.Diagnostic>> changed = new java.util.LinkedHashMap<>();
            Set<String> gone;
            synchronized (this) {
                gone = new HashSet<>(lastPublished.keySet());
                gone.removeAll(all.keySet());
                for (Map.Entry<String, List<org.groovyscope.engine.model.Diagnostic>> entry : all.entrySet()) {
                    // Only documents whose diagnostics changed are sent: after an edit in a large workspace that is
                    // usually one or two files, not all of them.
                    if (!entry.getValue().equals(lastPublished.get(entry.getKey()))) {
                        changed.put(entry.getKey(), entry.getValue());
                    }
                }
                lastPublished.keySet().removeAll(gone);
                lastPublished.putAll(changed);
            }
            for (Map.Entry<String, List<org.groovyscope.engine.model.Diagnostic>> entry : changed.entrySet()) {
                if (stopped) {
                    return;
                }
                List<Diagnostic> converted = new ArrayList<>();
                for (org.groovyscope.engine.model.Diagnostic diagnostic : entry.getValue()) {
                    converted.add(Converters.toLsp(diagnostic, current.quality().fixable(diagnostic)));
                }
                target.publishDiagnostics(new PublishDiagnosticsParams(entry.getKey(), converted));
            }
            for (String uri : gone) {
                target.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
            }
        } catch (RuntimeException e) {
            org.groovyscope.engine.log.EngineLog.warn("%s", "diagnostics failed: " + e);
        }
    }

    /**
     * Stops publishing. Pending publishes are cancelled and a running one stops before its next notification,
     * but the thread is never interrupted: interrupting a thread that is writing to an interruptible channel (the
     * client's socket or pipe) closes that channel, and the reply to the client's {@code shutdown} request could
     * then never be sent.
     */
    synchronized void shutdown() {
        stopped = true;
        if (pending != null) {
            pending.cancel(false);
        }
        executor.shutdown();
    }
}
