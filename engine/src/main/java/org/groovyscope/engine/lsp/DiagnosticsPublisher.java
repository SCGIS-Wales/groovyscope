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
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.services.LanguageServices;

/**
 * Pushes diagnostics to the client after edits settle. Which documents are pushed follows
 * {@code diagnostics.push}: the documents this connection has open (the default), every compiled document, or
 * none. Documents that disappeared, or are no longer in scope, get an empty notification, so the client never
 * shows stale problems. The quiet window is {@code diagnostics.debounceMillis}.
 */
final class DiagnosticsPublisher {

    static final long DEBOUNCE_MILLIS = EngineConfig.Diagnostics.DEFAULT_DEBOUNCE_MILLIS;

    private final Supplier<LanguageServices> services;
    private final Supplier<LanguageClient> client;
    private volatile Supplier<Set<String>> openDocuments;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "groovyscope-diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, List<org.groovyscope.engine.model.Diagnostic>> lastPublished = new java.util.HashMap<>();
    private ScheduledFuture<?> pending;
    private volatile boolean stopped;
    // The heavy lane a publish runs on, like any request that compiles and lints; null runs it on this thread.
    private final RequestExecutors lanes;

    DiagnosticsPublisher(Supplier<LanguageServices> services, Supplier<LanguageClient> client) {
        this(services, client, Set::of);
    }

    DiagnosticsPublisher(Supplier<LanguageServices> services, Supplier<LanguageClient> client,
            Supplier<Set<String>> openDocuments) {
        this(services, client, openDocuments, null);
    }

    DiagnosticsPublisher(Supplier<LanguageServices> services, Supplier<LanguageClient> client,
            Supplier<Set<String>> openDocuments, RequestExecutors lanes) {
        this.services = services;
        this.client = client;
        this.openDocuments = openDocuments;
        this.lanes = lanes;
    }

    /** The documents the connection has open, bound once the text document service exists. */
    void openDocuments(Supplier<Set<String>> supplier) {
        this.openDocuments = supplier;
    }

    /** Publishes after the configured quiet window; an earlier pending publish is replaced. */
    synchronized void schedule() {
        schedule(settings().debounceMillis());
    }

    /** The delivery settings of the workspace this connection serves, or the defaults before it is bound. */
    private EngineConfig.Diagnostics settings() {
        LanguageServices current = services.get();
        return current == null ? EngineConfig.Diagnostics.DEFAULTS : current.workspace().config().diagnostics();
    }

    /**
     * The documents a publish covers under the configured scope: {@code null} for every compiled document,
     * the open set for {@code open}, an empty set for {@code none}.
     */
    private Set<String> scope(EngineConfig.Diagnostics settings) {
        return switch (settings.push()) {
            case EngineConfig.Diagnostics.PUSH_WORKSPACE -> null;
            case EngineConfig.Diagnostics.PUSH_NONE -> Set.of();
            default -> openDocuments.get();
        };
    }

    synchronized void schedule(long delayMillis) {
        if (pending != null) {
            pending.cancel(false);
        }
        if (executor.isShutdown()) {
            return; // the connection has ended or shut down; nobody is listening
        }
        try {
            pending = executor.schedule(this::publishOrRetry, delayMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pending = null;
        }
    }

    /** True once {@link #shutdown()} has stopped the publishing thread. */
    boolean isShutdown() {
        return executor.isShutdown();
    }

    /** A scheduled publish: when the heavy lane is full, it is tried again after the quiet window. */
    private void publishOrRetry() {
        if (!publishNow() && !stopped) {
            schedule();
        }
    }

    /**
     * Compiles if needed and publishes the diagnostics of the documents in scope now, on the heavy lane so it counts
     * against the same capacity as every other compile and lint. False when the lane refused it (busy) and nothing
     * was published.
     */
    boolean publishNow() {
        if (lanes == null) {
            publishInline();
            return true;
        }
        try {
            lanes.heavy(cancel -> {
                publishInline();
                return null;
            }).get();
            return true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
            return false; // busy, exiting or cancelled with the connection: nothing was sent
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void publishInline() {
        LanguageServices current = services.get();
        LanguageClient target = client.get();
        if (current == null || target == null || stopped) {
            return;
        }
        try {
            long started = System.nanoTime();
            EngineConfig.Diagnostics settings = settings();
            Set<String> inScope = scope(settings);
            Map<String, List<org.groovyscope.engine.model.Diagnostic>> all = inScope != null && inScope.isEmpty()
                    ? Map.of()
                    : current.quality().publishedDiagnostics(inScope);
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
            org.groovyscope.engine.log.EngineLog.debug("published diagnostics for %d of %d document(s) in %d ms (scope %s)",
                    changed.size() + gone.size(), all.size(), (System.nanoTime() - started) / 1_000_000,
                    settings.push());
        } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
            // Logged, never swallowed by the scheduler: a silent stop of diagnostics is the worst outcome.
            org.groovyscope.engine.log.EngineLog.error(e, "diagnostics failed for %s", "workspace");
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
