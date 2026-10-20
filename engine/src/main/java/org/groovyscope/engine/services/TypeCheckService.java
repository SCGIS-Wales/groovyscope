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
package org.groovyscope.engine.services;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The static type check of a workspace: run once per snapshot and shared by every request that asks while it runs
 * (single flight), with the project's compiler configuration script, and the whole-workspace note on the classes
 * it left dynamic.
 */
final class TypeCheckService {

    /** Most dynamic classes a coverage note names. */
    static final int COVERAGE_NAMES = 5;

    private final WorkspaceModel workspace;
    private final LanguageServices navigation;
    private final ProfileService profiles;
    private final TypeCheckAnalyzer typeChecker = new TypeCheckAnalyzer();

    /** One snapshot's type check: the findings per document and the classes it left dynamic. */
    record TypeCheckEntry(long version, Map<String, List<Diagnostic>> typed, List<String> dynamic) {
    }

    private volatile TypeCheckEntry typeCache;
    // The type check under way and the snapshot version it serves: requests for that version join it (E-12).
    private final Object typeLock = new Object();
    private CompletableFuture<TypeCheckEntry> typeJob;
    private long typeJobVersion = -1;
    private final AtomicInteger typeChecksRun = new AtomicInteger();

    TypeCheckService(WorkspaceModel workspace, LanguageServices navigation, ProfileService profiles) {
        this.workspace = workspace;
        this.navigation = navigation;
        this.profiles = profiles;
    }

    /** How many whole-workspace type checks this instance has run (for specs). */
    int typeChecksRun() {
        return typeChecksRun.get();
    }

    /**
     * The type check of the whole workspace, run once per snapshot: every {@code check} asks for all of it (the
     * checkers need every class), so a request for one file must not pay for a full compile each time. Requests that
     * arrive while it runs join it; it checks the snapshot's own sources, so its result matches the version it is
     * cached under.
     */
    TypeCheckEntry typeCheckAll() {
        while (true) {
            Snapshot current = navigation.snapshot();
            TypeCheckEntry cached = typeCache;
            if (cached != null && cached.version() == current.version()) {
                return cached;
            }
            org.groovyscope.engine.concurrent.MemoryGuard.requireHeadroom();
            CompletableFuture<TypeCheckEntry> job;
            boolean owner;
            synchronized (typeLock) {
                owner = typeJob == null || typeJobVersion != current.version();
                if (owner) {
                    typeJob = new CompletableFuture<>();
                    typeJobVersion = current.version();
                }
                job = typeJob;
            }
            if (owner) {
                return runTypeCheck(current, job);
            }
            TypeCheckEntry joined = joinTypeCheck(job);
            if (joined != null) {
                return joined;
            }
            // the owner was cancelled: this request runs the check itself on the next pass
        }
    }

    private TypeCheckEntry runTypeCheck(Snapshot current, CompletableFuture<TypeCheckEntry> job) {
        try {
            long started = System.nanoTime();
            typeChecksRun.incrementAndGet();
            TypeCheckAnalyzer.Result checked = typeChecker.check(current.result().sources(), workspace.classpath(),
                    workspace.config().trust(), workspace.config().typecheck(), workspace.config().groovy(),
                    current.result().unresolvedUris(), configScript());
            Map<String, List<Diagnostic>> typed = checked.diagnostics();
            TypeCheckEntry entry = new TypeCheckEntry(current.version(), typed, checked.dynamicClasses());
            synchronized (typeLock) {
                // A check of an older snapshot finishing late never replaces a newer one.
                if (typeCache == null || typeCache.version() < entry.version()) {
                    typeCache = entry;
                }
            }
            org.groovyscope.engine.log.EngineLog.debug("type-checked %d file(s) in %d ms (snapshot %d)", typed.size(),
                    (System.nanoTime() - started) / 1_000_000, current.version());
            job.complete(entry);
            return entry;
        } catch (RuntimeException | Error e) {
            job.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (typeLock) {
                if (typeJob == job) {
                    typeJob = null;
                }
            }
        }
    }

    /**
     * The project's compiler configuration script ({@code typecheck.configScript}, relative to the workspace root),
     * or {@code null} when there is no such file.
     */
    Path configScript() {
        Path script = workspace.root().resolve(workspace.config().typecheck().configScript()).normalize();
        return Files.isRegularFile(script) ? script : null;
    }

    /** Waits for another request's type check, polling this request's cancel flag; null when that one was cancelled. */
    private static TypeCheckEntry joinTypeCheck(CompletableFuture<TypeCheckEntry> job) {
        while (true) {
            try {
                return job.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                org.groovyscope.engine.concurrent.Cancellation.checkpoint();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancellationException) {
                    return null;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("interrupted while waiting for the type check");
            }
        }
    }

    /**
     * The type-check part of {@code check}: the findings for {@code targets} (governed files following their
     * profile's checker policy), and on a whole-workspace check the coverage note, attached to a source rather than
     * one of the build {@code scripts}.
     */
    void addTo(Map<String, List<Diagnostic>> result, List<String> targets, Map<String, String> sources,
            boolean wholeWorkspace, Set<String> scripts) {
        Path script = configScript();
        boolean scripted = script != null && workspace.config().trust();
        List<String> dynamic = List.of();
        if (scripted || anyMayNeedTypeCheck(targets, sources)) {
            TypeCheckEntry entry = typeCheckAll();
            Map<String, List<Diagnostic>> typed = entry.typed();
            dynamic = entry.dynamic();
            for (String uri : targets) {
                for (Diagnostic diagnostic : profiles.checkerPolicy(uri, typed.getOrDefault(uri, List.of()))) {
                    if (!result.get(uri).contains(diagnostic)) {
                        result.get(uri).add(diagnostic);
                    }
                }
            }
        }
        if (wholeWorkspace && !result.isEmpty()) {
            if (dynamic.isEmpty() && !anyMayNeedTypeCheck(targets, sources) && !scripted) {
                dynamic = TypeCheckAnalyzer.dynamicClasses(navigation.snapshot().result().modules().values());
            }
            String noted = result.keySet().stream().filter(uri -> !scripts.contains(uri)).findFirst()
                    .orElse(result.keySet().iterator().next());
            Diagnostic coverage = coverage(noted, dynamic, scripted ? null : script);
            if (coverage != null) {
                result.get(coverage.uri()).add(coverage);
            }
        }
    }

    /**
     * Groovy's core static type checker alone (no Groovy 6 extensions) over {@code sources}, against the snapshot's
     * unresolved documents: for the fix's {@code @CompileStatic} verdicts.
     */
    TypeCheckAnalyzer.Result coreCheck(Map<String, String> sources) {
        return typeChecker.check(sources, workspace.classpath(), workspace.config().trust(), TypeCheckAnalyzer.CORE_ONLY,
                workspace.config().groovy(), navigation.snapshot().result().unresolvedUris());
    }

    /**
     * Whether the type check can report anything for {@code targets}: it only speaks about statically typed
     * code, so a request for files without {@code @CompileStatic} or {@code @TypeChecked} skips the whole-workspace
     * type-check compile (the costliest step of a single-file {@code check} after an edit).
     */
    static boolean anyMayNeedTypeCheck(Collection<String> targets, Map<String, String> sources) {
        Set<String> collectors = TypeCheckAnalyzer.staticCollectors(sources);
        for (String uri : targets) {
            if (TypeCheckAnalyzer.mayNeedTypeCheck(sources.get(uri), collectors)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The whole-workspace note on what the type check could not see: the classes it left dynamic, and a
     * configuration script left unapplied because the workspace is not trusted. {@code null} when there is neither.
     */
    static Diagnostic coverage(String uri, List<String> dynamic, Path unappliedScript) {
        List<String> notes = new ArrayList<>();
        if (!dynamic.isEmpty()) {
            List<String> named = dynamic.subList(0, Math.min(COVERAGE_NAMES, dynamic.size()));
            notes.add(dynamic.size() + (dynamic.size() == 1 ? " class is" : " classes are") + " dynamic, not type checked ("
                    + String.join(", ", named) + (dynamic.size() > named.size() ? ", ..." : "") + "): annotate them "
                    + "@CompileStatic or @TypeChecked, or apply @CompileStatic to all through a compiler configuration "
                    + "script (typecheck.configScript)");
        }
        if (unappliedScript != null) {
            notes.add(unappliedScript.getFileName() + " is not applied until the workspace is trusted (groovyscope "
                    + "trust): it is code from the repository");
        }
        return notes.isEmpty() ? null : new Diagnostic(uri, Range.of(0, 0, 0, 1), Diagnostic.Severity.INFORMATION,
                TypeCheckAnalyzer.SOURCE, "typecheck-coverage", String.join("; ", notes));
    }
}
