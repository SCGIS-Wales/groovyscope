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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.compile.CompilationResult;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.compile.LoaderHandle;
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.concurrent.Freshness;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.SymbolStore;
import org.groovyscope.engine.log.EngineLog;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.Symbols;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The compiled state of one workspace: the published {@link Snapshot}, when it is stale, the one compile per change
 * that every request needing a fresh snapshot shares (single flight), the background revalidation that lets a light
 * request answer from the published snapshot, the class loader each published compile keeps open, and the on-disk
 * symbol cache.
 */
final class SnapshotManager {

    /** How long {@link #close} waits for background work (a symbol save) before closing the store. */
    static final long CLOSE_DRAIN_SECONDS = 5;

    private final WorkspaceModel workspace;
    private final GroovyCompiler compiler;
    private final SymbolStore store;
    private final Object lock;
    private final Runnable onPublish;
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "groovyscope-background");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean backgroundCompileQueued = new AtomicBoolean();
    private volatile Future<?> backgroundCompile = CompletableFuture.completedFuture(null);
    // Held for the whole of a compile; a waiter polls its cancel flag instead of blocking on a monitor (E-13).
    private final ReentrantLock compileLock = new ReentrantLock();
    private volatile Snapshot snapshot = Snapshot.empty();
    /** Bumped on every workspace change; the snapshot is stale while it differs from {@link #compiled}. */
    private final AtomicLong changes = new AtomicLong(1);
    /** The {@link #changes} value the current snapshot reflects. */
    private volatile long compiled;
    private volatile List<Symbol> cachedSymbols;
    private long version;
    /** The compile under way, if any, that requests needing a fresh snapshot join instead of starting their own. */
    private final Object jobLock = new Object();
    private CompileJob inFlight;
    private volatile long lastUsedNanos = System.nanoTime();
    private volatile boolean closed;
    /** The change count of the last reconfigure: a snapshot older than that is never served stale. */
    private volatile long reconfiguredAt;
    /** The symbol save not yet written; a newer snapshot replaces it, so saves never queue up. */
    private final AtomicReference<Runnable> pendingSave = new AtomicReference<>();
    // The text each file had when it last parsed (shared with the sources map while unchanged), and the loader
    // of the last successful compile, closed when the next one is published. Both guarded by lock.
    private final Map<String, String> lastGood = new HashMap<>();
    private LoaderHandle openLoader;
    private volatile String compiledFingerprint;
    private volatile long lastDiskCheck = Long.MIN_VALUE;
    private volatile LongSupplier clock = System::nanoTime;

    /**
     * @param lock guards the published state together with the caches the owner derives from it
     * @param onPublish runs under {@code lock} whenever a compile is published, to drop those derived caches
     */
    SnapshotManager(WorkspaceModel workspace, GroovyCompiler compiler, SymbolStore store, Object lock,
            Runnable onPublish) {
        this.workspace = workspace;
        this.compiler = compiler;
        this.store = store;
        this.lock = lock;
        this.onPublish = onPublish;
    }

    /** True once at least one compile has produced a snapshot. */
    boolean hasCompiled() {
        return snapshot.version() > 0;
    }

    /** For specs: the clock the disk check window is measured on. */
    void clock(LongSupplier replacement) {
        clock = replacement;
    }

    /**
     * Marks the snapshot stale when files changed on disk since the last compile. Requests from the CLI and the
     * MCP server call this first: they have no editor sending change notifications.
     */
    void syncWithDisk() {
        long window = workspace.config().daemon().diskCheckMillis() * 1_000_000L;
        long now = clock.getAsLong();
        if (window > 0 && lastDiskCheck != Long.MIN_VALUE && now - lastDiskCheck < window) {
            return; // checked within daemon.diskCheckMillis: trusted for the rest of the window
        }
        lastDiskCheck = now;
        String recorded = compiledFingerprint;
        if (recorded != null && !recorded.equals(workspace.diskFingerprint())) {
            markDirty();
        }
    }

    /** Marks the snapshot stale; the next query recompiles. */
    void markDirty() {
        changes.incrementAndGet();
    }

    /** Until a compile under a new configuration is published, no request is answered from the old one. */
    void reconfigured() {
        reconfiguredAt = changes.get();
    }

    boolean isDirty() {
        return compiled != changes.get();
    }

    /** Recompiles the workspace when it changed since the last compile, and returns the snapshot. */
    Snapshot refresh() {
        acquireCompileLock();
        try {
            if (!isDirty()) {
                return snapshot;
            }
            // Captured before the sources are read: an edit that arrives while this compile runs leaves the
            // snapshot stale, so the next query recompiles instead of serving the pre-edit result.
            long target = changes.get();
            String fingerprint = null;
            Map<String, String> sources = Map.of();
            CompilationResult result = null;
            boolean published = false;
            try {
                // Taken before the sources are read, so an edit racing this compile shows up as a later change.
                long started = System.nanoTime();
                WorkspaceModel.CompileInput input = workspace.compileInput();
                fingerprint = input.fingerprint();
                sources = input.sources();
                result = compiler.compile(sources, workspace.classpath(), workspace.config().trust());
                EngineLog.debug("compiled %d file(s) of %s in %d ms (%d with diagnostics)",
                        sources.size(), workspace.root(), (System.nanoTime() - started) / 1_000_000,
                        result.diagnostics().values().stream().filter(list -> !list.isEmpty()).count());
                Map<String, ModuleNode> modules = new HashMap<>(result.modules());
                Map<String, String> lastTexts;
                synchronized (lock) {
                    // A file that no longer parses keeps a tree of its last good text, parsed again into this
                    // generation: a tree carried over from the previous compile would pin that whole generation
                    // (every file's AST and the loader with its jars) in memory for as long as the error lasts.
                    for (Map.Entry<String, String> source : sources.entrySet()) {
                        if (modules.containsKey(source.getKey())) {
                            lastGood.put(source.getKey(), source.getValue());
                        }
                    }
                    lastGood.keySet().retainAll(sources.keySet());
                    lastTexts = new HashMap<>(lastGood);
                }
                for (String uri : sources.keySet()) {
                    String text = lastTexts.get(uri);
                    if (!modules.containsKey(uri) && text != null) {
                        compiler.parseOnly(uri, text).ifPresent(module -> modules.put(uri, module));
                    }
                }
                AstIndex index = AstIndex.build(modules, sources);
                Resolver resolver = new Resolver(index);
                synchronized (lock) {
                    if (closed) {
                        // released while this compile ran: nothing is published, and its loader is closed below
                        throw new CancellationException("the workspace was closed during the compile");
                    }
                    version++;
                    snapshot = new Snapshot(result, index, resolver, new Symbols(index), version);
                    onPublish.run();
                    // Nothing references the previous generation now: release the jars its loader held open.
                    if (openLoader != null) {
                        openLoader.close();
                    }
                    openLoader = result.loader();
                    published = true;
                }
                persistSymbols(snapshot, sources);
            } catch (CancellationException e) {
                // Every request that wanted this compile is gone: nothing is recorded, the next request compiles.
                EngineLog.debug("compile of %s abandoned: %s", workspace.root(), e.getMessage());
                throw e;
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
                EngineLog.error(e, "compile of %s failed", workspace.root());
                // Recorded as a snapshot with the failure as a diagnostic, so later requests answer from it until
                // something changes, instead of repeating the same failure on every request.
                EngineLog.warn("%s", "analysis failed: " + e);
                synchronized (lock) {
                    version++;
                    snapshot = failedSnapshot(snapshot, sources, e, version);
                }
            } finally {
                // A result that was not published (cancelled or failed after the compile returned) owns a loader
                // nobody else will close.
                if (!published && result != null && result.loader() != null) {
                    result.loader().close();
                }
            }
            compiled = target;
            compiledFingerprint = fingerprint;
            return snapshot;
        } finally {
            compileLock.unlock();
        }
    }

    /** Waits for the compile lock, leaving (cancelled) when the request is cancelled or interrupted meanwhile. */
    private void acquireCompileLock() {
        try {
            while (!compileLock.tryLock(100, TimeUnit.MILLISECONDS)) {
                Cancellation.checkpoint();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted while waiting for the compile");
        }
    }

    /** The snapshot last published, without compiling. */
    Snapshot currentSnapshot() {
        return snapshot;
    }

    /** The compile last queued on the background thread (done when none is queued). */
    Future<?> backgroundCompile() {
        return backgroundCompile;
    }

    /** Queues one compile on the background thread, unless one is already queued. */
    private void revalidate() {
        if (!backgroundCompileQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            backgroundCompile = background.submit(() -> {
                try {
                    // Nobody waits on it, so it is abandoned only when the workspace closes.
                    Cancellation.run(() -> closed, this::snapshot);
                } catch (RuntimeException e) {
                    EngineLog.debug("background compile of %s failed: %s", workspace.root(), e);
                } finally {
                    backgroundCompileQueued.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            backgroundCompileQueued.set(false); // closed: nothing more to compile
        }
    }

    /** The previous snapshot's trees and index with the failure reported on the first document. */
    static Snapshot failedSnapshot(Snapshot previous, Map<String, String> sources, Throwable failure, long version) {
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
        }
        String first = sources.keySet().stream().sorted().findFirst().orElse(null);
        if (first != null) {
            diagnostics.put(first, List.of(new Diagnostic(first, Range.of(0, 0, 0, 1), Diagnostic.Severity.ERROR,
                    GroovyCompiler.SOURCE, "engine-crash", "groovyscope could not analyse the workspace: " + failure)));
        }
        Map<String, ModuleNode> modules = new HashMap<>();
        for (Map.Entry<String, ModuleNode> entry : previous.result().modules().entrySet()) {
            if (sources.containsKey(entry.getKey())) {
                modules.put(entry.getKey(), entry.getValue());
            }
        }
        CompilationResult result = new CompilationResult(modules, sources, diagnostics, 0, sources.keySet());
        return new Snapshot(result, previous.index(), previous.resolver(), previous.symbols(), version);
    }

    private void persistSymbols(Snapshot fresh, Map<String, String> sources) {
        if (store == null) {
            return;
        }
        List<Symbol> symbols = fresh.symbols().workspaceSymbols("", Integer.MAX_VALUE);
        Runnable save = () -> {
            try {
                store.save(sources, symbols);
            } catch (IOException e) {
                EngineLog.warn("%s", "symbol cache not saved: " + e.getMessage());
            }
        };
        // Only the newest snapshot is worth writing: a save still waiting is replaced, not queued behind.
        if (pendingSave.getAndSet(save) == null) {
            try {
                background.submit(() -> {
                    Runnable latest = pendingSave.getAndSet(null);
                    if (latest != null) {
                        latest.run();
                    }
                });
            } catch (RejectedExecutionException e) {
                pendingSave.set(null); // closed: nothing more to persist
            }
        }
    }

    /** Symbols from the cache for documents whose text has not changed since they were stored. */
    List<Symbol> cachedSymbols() {
        List<Symbol> cached = cachedSymbols;
        if (cached == null) {
            try {
                cached = store == null ? List.of() : store.load(workspace.compileSources());
            } catch (IOException e) {
                EngineLog.warn("%s", "symbol cache not read: " + e.getMessage());
                cached = List.of();
            }
            cachedSymbols = cached;
        }
        return cached;
    }

    /** Before the first compile, serve symbol searches from the cache and compile in the background. */
    boolean answerFromCache() {
        if (store == null || hasCompiled() || cachedSymbols().isEmpty()) {
            return false;
        }
        revalidate();
        return true;
    }

    /** Drops the symbols read from the cache (they are read again on demand). */
    void shed() {
        cachedSymbols = null;
    }

    /**
     * One compile that several requests wait for. The first requester runs it on its own thread; the others join
     * its future and poll their own cancel flag while they wait, so a cancelled request leaves without touching
     * the compile the rest still need. The compile itself is abandoned (at its next checkpoint) only when every
     * request interested in it has been cancelled.
     */
    private static final class CompileJob {
        final CompletableFuture<Snapshot> result = new CompletableFuture<>();
        final AtomicInteger interested = new AtomicInteger(1);
        final BooleanSupplier ownerCancelled;

        CompileJob(BooleanSupplier ownerCancelled) {
            this.ownerCancelled = ownerCancelled;
        }

        boolean abandoned() {
            return interested.get() == 0 || (interested.get() == 1 && ownerCancelled.getAsBoolean());
        }
    }

    /**
     * The current snapshot, rebuilt first when stale; one compile per change however many requests need it. A light
     * request about a document unchanged since the published compile (see {@link Freshness}) gets the published
     * snapshot at once while the compile runs in the background.
     */
    Snapshot snapshot() {
        if (closed) {
            throw new IllegalStateException("the workspace " + workspace.root() + " was closed (released while idle); "
                    + "reconnect to open it again");
        }
        lastUsedNanos = System.nanoTime();
        String staleOk = Freshness.staleOkUri();
        Snapshot published = snapshot;
        if (staleOk != null && isDirty() && published.version() > 0 && compiled >= reconfiguredAt
                && Objects.equals(published.result().sources().get(staleOk), workspace.text(staleOk))) {
            revalidate();
            return published;
        }
        if (isDirty() && published.version() > 0 && org.groovyscope.engine.concurrent.MemoryGuard.pressure()) {
            // Under heap pressure a compile is the whole-workspace step refused; a light request keeps the
            // published snapshot (E-18).
            if (staleOk != null) {
                return published;
            }
            org.groovyscope.engine.concurrent.MemoryGuard.requireHeadroom();
        }
        while (isDirty()) {
            CompileJob job;
            boolean owner;
            synchronized (jobLock) {
                owner = inFlight == null;
                if (owner) {
                    inFlight = new CompileJob(Cancellation.current());
                }
                job = inFlight;
            }
            if (owner) {
                return runJob(job);
            }
            Snapshot joined = joinJob(job);
            if (joined != null && !isDirty()) {
                return joined;
            }
            // The compile was abandoned by everyone else or missed an edit: this request starts the next one.
        }
        return snapshot;
    }

    private Snapshot runJob(CompileJob job) {
        try {
            Snapshot fresh = Cancellation.run(job::abandoned, this::refresh);
            job.result.complete(fresh);
            return fresh;
        } catch (RuntimeException | Error e) {
            job.result.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (jobLock) {
                if (inFlight == job) {
                    inFlight = null;
                }
            }
        }
    }

    /** Waits for another request's compile; null when that compile was abandoned. */
    private Snapshot joinJob(CompileJob job) {
        job.interested.incrementAndGet();
        try {
            while (true) {
                try {
                    return job.result.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    Cancellation.checkpoint();
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
                    throw new CancellationException("interrupted while waiting for the compile");
                }
            }
        } finally {
            job.interested.decrementAndGet();
        }
    }

    /** Files in the current snapshot, without touching the disk (0 before the first compile). */
    int fileCount() {
        return snapshot.result().sources().size();
    }

    /** When a request last asked for the snapshot, on the {@link System#nanoTime()} clock. */
    long lastUsedNanos() {
        return lastUsedNanos;
    }

    /** Marks this workspace as just used (a connection was handed it), so an idle eviction leaves it. */
    void touch() {
        lastUsedNanos = System.nanoTime();
    }

    /** True once {@link #close} ran: the workspace answers nothing more. */
    boolean isClosed() {
        return closed;
    }

    /**
     * Releases what the workspace holds: the kept compile's loader (and, under the same lock, the owner's derived
     * caches through {@code clearDerived}), pending saves and the background thread, which is drained (bounded)
     * before the symbol store closes so no save runs against a closed database.
     */
    void close(Runnable clearDerived) {
        closed = true;
        synchronized (lock) {
            if (openLoader != null) {
                openLoader.close();
                openLoader = null;
            }
            clearDerived.run();
        }
        background.shutdownNow();
        try {
            if (!background.awaitTermination(CLOSE_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                EngineLog.warn("%s", "background work of " + workspace.root()
                        + " still running at close; the symbol cache closes anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (store != null) {
            try {
                store.close();
            } catch (IOException e) {
                // nothing useful to do
            }
        }
    }

    /**
     * {@code targets} compiled on their own against the classes of the last full compile, when they are exactly the
     * documents whose text changed since it (no document added or removed, no Java source among them, the
     * snapshot otherwise current): what a check after a write needs, at the cost of one file instead of the
     * workspace. Empty when that does not hold; the caller then uses the snapshot. The snapshot is left as it
     * is (still stale), and the caller closes the result.
     */
    Optional<CompilationResult> compileOnly(Collection<String> targets) {
        Snapshot last = snapshot;
        if (!hasCompiled() || !isDirty() || targets.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> now = workspace.compileSources();
        Map<String, String> before = last.result().sources();
        if (!now.keySet().equals(before.keySet())) {
            return Optional.empty();
        }
        Map<String, String> changed = new HashMap<>();
        for (Map.Entry<String, String> source : now.entrySet()) {
            if (!source.getValue().equals(before.get(source.getKey()))) {
                changed.put(source.getKey(), source.getValue());
            }
        }
        // Exactly the targets: an unchanged target's findings could depend on what changed, so it takes the snapshot.
        if (changed.isEmpty() || !changed.keySet().equals(new HashSet<>(targets))
                || changed.keySet().stream().anyMatch(WorkspaceModel::isJavaUri)) {
            return Optional.empty();
        }
        long started = System.nanoTime();
        CompilationResult result = compiler.compile(changed, workspace.classpath(), workspace.config().trust(),
                knownClasses(last, changed.keySet()));
        EngineLog.debug("compiled %d changed file(s) of %s alone in %d ms",
                changed.size(), workspace.root(), (System.nanoTime() - started) / 1_000_000);
        return Optional.of(result);
    }

    /**
     * {@code texts} (candidate contents of workspace documents, new files included) compiled on their own against
     * the classes of the last full compile, as {@link #compileOnly} does but with the texts as given rather than
     * what changed on disk. When nothing has compiled yet, the first full compile runs here (shared, as every
     * request shares it). The snapshot is left as it is, and the caller closes the result.
     */
    CompilationResult compileOverlay(Map<String, String> texts) {
        Snapshot last = hasCompiled() ? snapshot : snapshot();
        long started = System.nanoTime();
        CompilationResult result = compiler.compile(texts, workspace.classpath(), workspace.config().trust(),
                knownClasses(last, texts.keySet()));
        EngineLog.debug("compiled %d candidate text(s) of %s over the snapshot in %d ms",
                texts.size(), workspace.root(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    /**
     * The type preview of {@code texts} ({@link org.groovyscope.engine.typecheck.TypePreview#check}) against the
     * classes of the last full compile, the texts' own documents left out. With {@code compileFirst} the first full
     * compile runs here when none has yet; without it the texts are then checked against the classpath alone.
     */
    org.groovyscope.engine.typecheck.TypePreview.Outcome typePreview(Map<String, String> texts,
            java.util.Set<String> speculative, boolean compileFirst,
            java.util.function.Consumer<Map<String, ModuleNode>> reader) {
        Snapshot last = hasCompiled() ? snapshot : compileFirst ? snapshot() : null;
        Map<String, ClassNode> known = last == null ? Map.of() : knownClasses(last, texts.keySet());
        long started = System.nanoTime();
        org.groovyscope.engine.typecheck.TypePreview.Outcome outcome = org.groovyscope.engine.typecheck.TypePreview
                .check(texts, workspace.classpath(), workspace.config().trust(), known, speculative, reader);
        EngineLog.debug("type preview of %d text(s), %d class(es) speculative, in %d ms", texts.size(),
                speculative.size(), (System.nanoTime() - started) / 1_000_000);
        return outcome;
    }

    /** The classes of {@code last} by name, minus those of {@code replaced} documents and of unresolved ones. */
    private static Map<String, ClassNode> knownClasses(Snapshot last, Collection<String> replaced) {
        Map<String, ClassNode> known = new HashMap<>();
        for (Map.Entry<String, ModuleNode> module : last.result().modules().entrySet()) {
            if (!replaced.contains(module.getKey()) && !last.result().unresolvedUris().contains(module.getKey())) {
                for (ClassNode classNode : module.getValue().getClasses()) {
                    known.put(classNode.getName(), classNode);
                }
            }
        }
        return known;
    }
}
