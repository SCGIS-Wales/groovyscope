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
    private final Set<String> published = new HashSet<>();
    private ScheduledFuture<?> pending;

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
        pending = executor.schedule(this::publishNow, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Compiles if needed and publishes every document's diagnostics now. */
    void publishNow() {
        LanguageServices current = services.get();
        LanguageClient target = client.get();
        if (current == null || target == null) {
            return;
        }
        try {
            Map<String, List<org.groovyscope.engine.model.Diagnostic>> all = current.diagnostics();
            Set<String> gone;
            synchronized (this) {
                gone = new HashSet<>(published);
                gone.removeAll(all.keySet());
                published.clear();
                published.addAll(all.keySet());
            }
            for (Map.Entry<String, List<org.groovyscope.engine.model.Diagnostic>> entry : all.entrySet()) {
                List<Diagnostic> converted = new ArrayList<>();
                for (org.groovyscope.engine.model.Diagnostic diagnostic : entry.getValue()) {
                    converted.add(Converters.toLsp(diagnostic));
                }
                target.publishDiagnostics(new PublishDiagnosticsParams(entry.getKey(), converted));
            }
            for (String uri : gone) {
                target.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
            }
        } catch (RuntimeException e) {
            System.err.println("groovyscope-engine: diagnostics failed: " + e);
        }
    }

    void shutdown() {
        executor.shutdownNow();
    }
}
