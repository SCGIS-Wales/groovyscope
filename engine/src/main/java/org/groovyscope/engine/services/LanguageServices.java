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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.groovyscope.engine.concurrent.Cancellation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.callgraph.ImpactAnalyzer;
import org.groovyscope.engine.compile.CompilationResult;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.compile.LoaderHandle;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.SymbolStore;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Completion;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.model.ImpactReport;
import org.groovyscope.engine.model.Definition;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Hover;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Reference;
import org.groovyscope.engine.model.RenamePreparation;
import org.groovyscope.engine.model.RenameResult;
import org.groovyscope.engine.model.SignatureHelp;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.HoverRenderer;
import org.groovyscope.engine.semantics.Resolution;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.Symbols;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The engine's API, independent of any protocol. Each query runs against the current {@link Snapshot};
 * when documents changed since the last compile, the snapshot is rebuilt first so answers are never stale.
 */
public final class LanguageServices {

    static final int WORKSPACE_SYMBOL_LIMIT = 500;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private final WorkspaceModel workspace;
    private final GroovyCompiler compiler;
    private final SymbolStore store;
    private final Object lock = new Object();
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "groovyscope-background");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean backgroundRefreshQueued = new AtomicBoolean();
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
    /** The symbol save not yet written; a newer snapshot replaces it, so saves never queue up. */
    private final AtomicReference<Runnable> pendingSave = new AtomicReference<>();

    public LanguageServices(WorkspaceModel workspace, GroovyCompiler compiler) {
        this(workspace, compiler, null);
    }

    /**
     * @param store optional on-disk symbol cache; answers symbol searches before the first compile and is
     *              rewritten after every compile
     */
    public LanguageServices(WorkspaceModel workspace, GroovyCompiler compiler, SymbolStore store) {
        this.workspace = workspace;
        this.compiler = compiler;
        this.store = store;
    }

    /** True once at least one compile has produced a snapshot. */
    public boolean hasCompiled() {
        return snapshot.version() > 0;
    }

    private volatile QualityServices quality;
    private volatile CallGraph graph;
    private volatile long graphVersion = -1;
    // The text each file had when it last parsed (shared with the sources map while unchanged), and the loader
    // of the last successful compile, closed when the next one is published. Both guarded by lock.
    private final Map<String, String> lastGood = new HashMap<>();
    private LoaderHandle openLoader;

    /** The call graph of the current snapshot, built on first use and kept until the next compile. */
    public CallGraph callGraph() {
        Snapshot current = snapshot();
        synchronized (lock) {
            if (graph == null || graphVersion != current.version()) {
                graph = CallGraph.build(current.index(), current.resolver(), current.symbols());
                graphVersion = current.version();
            }
            return graph;
        }
    }

    private ImpactAnalyzer impactAnalyzer(Snapshot current) {
        return new ImpactAnalyzer(current.index(), current.resolver(), callGraph(), current.symbols());
    }

    /** The impact of changing the declaration under the cursor. */
    public Optional<ImpactReport> impact(String uri, Position position, ImpactReport.Change change) {
        Snapshot current = snapshot();
        return current.index().nodeAt(uri, position)
                .flatMap(entry -> current.resolver().resolve(entry))
                .filter(resolution -> current.index().declarationEntry(resolution.primary()).isPresent())
                .map(resolution -> impactAnalyzer(current).analyze(resolution.primary(), change));
    }

    /**
     * The impact of changing every declaration a name denotes: {@code pkg.Class}, {@code pkg.Class.member},
     * {@code Class#member} or a bare {@code Class}; overloads each get a report.
     */
    public List<ImpactReport> impactOf(String qualifiedName, ImpactReport.Change change) {
        Snapshot current = snapshot();
        List<ImpactReport> reports = new ArrayList<>();
        for (ASTNode declaration : findDeclarations(current, qualifiedName)) {
            reports.add(impactAnalyzer(current).analyze(declaration, change));
        }
        return reports;
    }

    /** Declarations a dotted or hash-separated name refers to. */
    public List<ASTNode> findDeclarations(Snapshot current, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        int hash = trimmed.indexOf('#');
        String classPart = hash >= 0 ? trimmed.substring(0, hash) : trimmed;
        String memberPart = hash >= 0 ? trimmed.substring(hash + 1) : null;
        List<ClassNode> classes = classesNamed(current, classPart);
        if (classes.isEmpty() && memberPart == null && classPart.contains(".")) {
            int dot = classPart.lastIndexOf('.');
            classes = classesNamed(current, classPart.substring(0, dot));
            memberPart = classPart.substring(dot + 1);
        }
        List<ASTNode> found = new ArrayList<>();
        for (ClassNode classNode : classes) {
            if (memberPart == null || memberPart.isBlank()) {
                found.add(classNode);
                continue;
            }
            for (org.codehaus.groovy.ast.MethodNode method : classNode.getDeclaredMethods(memberPart)) {
                if (!method.isSynthetic()) {
                    found.add(method);
                }
            }
            org.codehaus.groovy.ast.PropertyNode property = classNode.getProperty(memberPart);
            if (property != null) {
                found.add(property);
            } else {
                org.codehaus.groovy.ast.FieldNode field = classNode.getDeclaredField(memberPart);
                if (field != null) {
                    found.add(field);
                }
            }
        }
        return found;
    }

    private static List<ClassNode> classesNamed(Snapshot current, String name) {
        Optional<ClassNode> exact = current.index().classNamed(name);
        if (exact.isPresent()) {
            return List.of(exact.get());
        }
        return current.index().classesWithSimpleName(name);
    }

    /** The declaration (method, constructor, class) at a position, for call hierarchy, or empty. */
    public Optional<Symbol> callHierarchyItem(String uri, Position position) {
        Snapshot current = snapshot();
        return current.index().nodeAt(uri, position)
                .flatMap(entry -> current.resolver().resolve(entry))
                .map(Resolution::primary)
                .filter(node -> node instanceof org.codehaus.groovy.ast.MethodNode || node instanceof ClassNode)
                .filter(node -> current.index().declarationEntry(node).isPresent())
                .map(node -> current.symbols().of(node));
    }

    /** The declaration node a symbol (from {@link #callHierarchyItem}) stands for, or empty. */
    public Optional<ASTNode> declarationOf(Symbol symbol) {
        Snapshot current = snapshot();
        if (symbol == null || symbol.location() == null) {
            return Optional.empty();
        }
        for (NodeEntry entry : current.index().entries(symbol.location().uri())) {
            if (entry.isDeclaration() && entry.range().equals(symbol.location().range())
                    && Symbols.nameOf(entry.node()) != null && Symbols.nameOf(entry.node()).equals(symbol.name())) {
                return Optional.of(entry.node());
            }
        }
        return Optional.empty();
    }

    /** Calls into the given declaration. */
    public List<CallSite> incomingCalls(Symbol symbol) {
        return declarationOf(symbol).map(node -> callGraph().incoming(node)).orElse(List.of());
    }

    /** Calls made from within the given declaration. */
    public List<CallSite> outgoingCalls(Symbol symbol) {
        return declarationOf(symbol).map(node -> callGraph().outgoing(node)).orElse(List.of());
    }

    /** Constructs that defeat static analysis, in the given documents (all when empty). */
    public List<org.groovyscope.engine.semantics.DynamicCodeFinder.Finding> dynamicCode(java.util.Collection<String> uris) {
        return org.groovyscope.engine.semantics.DynamicCodeFinder.find(snapshot().index(), uris);
    }

    /** Semantic tokens for a document, LSP-encoded. */
    public List<Integer> semanticTokens(String uri) {
        return SemanticTokensSupport.encode(snapshot(), uri);
    }

    /** Lint, type-check, format and quick fixes over this workspace, sharing its caches. */
    public QualityServices quality() {
        QualityServices current = quality;
        if (current == null) {
            synchronized (lock) {
                if (quality == null) {
                    quality = new QualityServices(workspace, this);
                }
                current = quality;
            }
        }
        return current;
    }

    public WorkspaceModel workspace() {
        return workspace;
    }

    private volatile String compiledFingerprint;

    /**
     * Marks the snapshot stale when files changed on disk since the last compile. Requests from the CLI and the
     * MCP server call this first: they have no editor sending change notifications.
     */
    public void syncWithDisk() {
        String recorded = compiledFingerprint;
        if (recorded != null && !recorded.equals(workspace.diskFingerprint())) {
            markDirty();
        }
    }

    /** Marks the snapshot stale; the next query recompiles. */
    public void markDirty() {
        changes.incrementAndGet();
    }

    /**
     * Applies a new configuration: the workspace model takes it, every cache derived from the old one (quality
     * services with their rule sets and analysers, the call graph, folded diagnostics) is dropped, and the
     * snapshot is marked stale so the next query recompiles against the new classpath and settings.
     */
    public void reconfigure(EngineConfig replacement) {
        workspace.reconfigure(replacement);
        synchronized (lock) {
            quality = null;
            graph = null;
            graphVersion = -1;
            folded = null;
        }
        markDirty();
    }

    public boolean isDirty() {
        return compiled != changes.get();
    }

    /** Recompiles the workspace now and returns the fresh snapshot. */
    public Snapshot refresh() {
        synchronized (lock) {
            // Captured before the sources are read: an edit that arrives while this compile runs leaves the
            // snapshot stale, so the next query recompiles instead of serving the pre-edit result.
            long target = changes.get();
            String fingerprint = null;
            Map<String, String> sources = Map.of();
            try {
                // Taken before the sources are read, so an edit racing this compile shows up as a later change.
                long started = System.nanoTime();
                fingerprint = workspace.diskFingerprint();
                sources = workspace.compileSources();
                CompilationResult result = compiler.compile(sources, workspace.classpath(), workspace.config().trust());
                org.groovyscope.engine.log.EngineLog.debug("compiled %d file(s) of %s in %d ms (%d with diagnostics)",
                        sources.size(), workspace.root(), (System.nanoTime() - started) / 1_000_000,
                        result.diagnostics().values().stream().filter(list -> !list.isEmpty()).count());
                Map<String, ModuleNode> modules = new HashMap<>(result.modules());
                // A file that no longer parses keeps a tree of its last good text, parsed again into this
                // generation: a tree carried over from the previous compile would pin that whole generation
                // (every file's AST and the loader with its jars) in memory for as long as the error lasts.
                for (Map.Entry<String, String> source : sources.entrySet()) {
                    String uri = source.getKey();
                    if (modules.containsKey(uri)) {
                        lastGood.put(uri, source.getValue());
                    } else {
                        String text = lastGood.get(uri);
                        if (text != null) {
                            compiler.parseOnly(uri, text).ifPresent(module -> modules.put(uri, module));
                        }
                    }
                }
                lastGood.keySet().retainAll(sources.keySet());
                AstIndex index = AstIndex.build(modules, sources);
                Resolver resolver = new Resolver(index);
                version++;
                snapshot = new Snapshot(result, index, resolver, new Symbols(index), version);
                graph = null;
                graphVersion = -1;
                // Nothing references the previous generation now: release the jars its loader held open.
                if (openLoader != null) {
                    openLoader.close();
                }
                openLoader = result.loader();
                persistSymbols(snapshot, sources);
            } catch (CancellationException e) {
                // Every request that wanted this compile is gone: nothing is recorded, the next request compiles.
                org.groovyscope.engine.log.EngineLog.debug("compile of %s abandoned: %s", workspace.root(), e.getMessage());
                throw e;
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
                org.groovyscope.engine.log.EngineLog.error(e, "compile of %s failed", workspace.root());
                // Recorded as a snapshot with the failure as a diagnostic, so later requests answer from it until
                // something changes, instead of repeating the same failure on every request.
                org.groovyscope.engine.log.EngineLog.warn("%s", "analysis failed: " + e);
                version++;
                snapshot = failedSnapshot(snapshot, sources, e, version);
            }
            compiled = target;
            compiledFingerprint = fingerprint;
            return snapshot;
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
                org.groovyscope.engine.log.EngineLog.warn("%s", "symbol cache not saved: " + e.getMessage());
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
            } catch (java.util.concurrent.RejectedExecutionException e) {
                pendingSave.set(null); // closed: nothing more to persist
            }
        }
    }

    /** Symbols from the cache for documents whose text has not changed since they were stored. */
    private List<Symbol> cachedSymbols() {
        List<Symbol> cached = cachedSymbols;
        if (cached == null) {
            try {
                cached = store == null ? List.of() : store.load(workspace.compileSources());
            } catch (IOException e) {
                org.groovyscope.engine.log.EngineLog.warn("%s", "symbol cache not read: " + e.getMessage());
                cached = List.of();
            }
            cachedSymbols = cached;
        }
        return cached;
    }

    /** Before the first compile, serve symbol searches from the cache and compile in the background. */
    private boolean answerFromCache() {
        if (store == null || hasCompiled() || cachedSymbols().isEmpty()) {
            return false;
        }
        if (backgroundRefreshQueued.compareAndSet(false, true)) {
            background.submit(() -> {
                try {
                    refresh();
                } finally {
                    backgroundRefreshQueued.set(false);
                }
            });
        }
        return true;
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

    /** The current snapshot, rebuilt first when stale; one compile per change however many requests need it. */
    public Snapshot snapshot() {
        lastUsedNanos = System.nanoTime();
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
    public int fileCount() {
        return snapshot.result().sources().size();
    }

    /** When a request last asked for the snapshot, on the {@link System#nanoTime()} clock. */
    public long lastUsedNanos() {
        return lastUsedNanos;
    }

    /** Releases what this workspace holds: the kept compile's loader, pending saves and the background thread. */
    public void close() {
        synchronized (lock) {
            if (openLoader != null) {
                openLoader.close();
                openLoader = null;
            }
            graph = null;
            quality = null;
        }
        background.shutdownNow();
        if (store != null) {
            try {
                store.close();
            } catch (java.io.IOException e) {
                // nothing useful to do
            }
        }
    }

    private record FoldedDiagnostics(long version, Map<String, List<Diagnostic>> diagnostics) {
    }

    private volatile FoldedDiagnostics folded;

    /**
     * Compile diagnostics per document. With no classpath configured, unresolved imports are folded into one
     * {@code GS100} finding per file (see {@link org.groovyscope.engine.compile.MissingDependencies}); with a
     * classpath they stay real errors.
     */
    public Map<String, List<Diagnostic>> diagnostics() {
        Snapshot current = snapshot();
        if (!workspace.classpath().isEmpty()) {
            return current.result().diagnostics();
        }
        FoldedDiagnostics cached = folded;
        if (cached == null || cached.version() != current.version()) {
            cached = new FoldedDiagnostics(current.version(), Map.copyOf(
                    org.groovyscope.engine.compile.MissingDependencies.fold(current.result().diagnostics(),
                            current.result().sources(), org.groovyscope.engine.practices.PracticeFinder.severity(
                                    org.groovyscope.engine.compile.MissingDependencies.CODE, workspace.config()))));
            folded = cached;
        }
        return cached.diagnostics();
    }

    public Optional<Definition> definition(String uri, Position position) {
        Snapshot current = snapshot();
        return current.index().nodeAt(uri, position)
                .flatMap(entry -> current.resolver().resolve(entry))
                .map(resolution -> new Definition(current.symbols().of(resolution.primary()), resolution.confidence()));
    }

    public Optional<Definition> typeDefinition(String uri, Position position) {
        Snapshot current = snapshot();
        Optional<NodeEntry> entry = current.index().nodeAt(uri, position);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Optional<Resolution> resolution = current.resolver().resolve(entry.get());
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        ASTNode declaration = resolution.get().primary();
        ClassNode type = declaration instanceof VariableExpression variable
                ? current.resolver().typeOfVariable(variable)
                : Resolver.typeOfMember(declaration);
        if (type == null) {
            return Optional.empty();
        }
        Resolution classResolution = current.resolver().classDeclaration(type);
        if (classResolution == null) {
            return Optional.empty();
        }
        return Optional.of(new Definition(current.symbols().of(classResolution.primary()), resolution.get().confidence()));
    }

    public List<Reference> references(String uri, Position position, boolean includeDeclaration) {
        Snapshot current = snapshot();
        Optional<NodeEntry> entry = current.index().nodeAt(uri, position);
        if (entry.isEmpty()) {
            return List.of();
        }
        Optional<Resolution> resolution = current.resolver().resolve(entry.get());
        if (resolution.isEmpty()) {
            return List.of();
        }
        return referencesTo(current, resolution.get().primary(), includeDeclaration);
    }

    List<Reference> referencesTo(Snapshot current, ASTNode declaration, boolean includeDeclaration) {
        List<Reference> references = new ArrayList<>();
        for (NodeEntry candidate : current.index().allEntries()) {
            if (candidate.role() == NodeEntry.Role.OTHER) {
                continue;
            }
            if (candidate.isDeclaration()) {
                if (includeDeclaration && candidate.node() == declaration) {
                    TextLines lines = current.index().lines(candidate.uri());
                    Range name = AstRanges.nameRange(declaration, candidate.range(), lines);
                    references.add(new Reference(new Location(candidate.uri(), name), Confidence.STATIC));
                }
                continue;
            }
            Optional<Resolution> resolution = current.resolver().resolve(candidate);
            if (resolution.isPresent() && resolution.get().includes(declaration)) {
                Range name = nameRangeOfUsage(current, candidate, declaration);
                references.add(new Reference(new Location(candidate.uri(), name), resolution.get().confidence()));
            }
        }
        return references;
    }

    static Range nameRangeOfUsage(Snapshot current, NodeEntry usage, ASTNode declaration) {
        String name = Symbols.nameOf(declaration);
        if (usage.role() == NodeEntry.Role.TYPE_USAGE || usage.role() == NodeEntry.Role.CONSTRUCTOR_CALL
                || usage.role() == NodeEntry.Role.STATIC_CALL) {
            return AstRanges.nameWithin(usage.range(), name, current.index().lines(usage.uri()));
        }
        return usage.range();
    }

    public Optional<Hover> hover(String uri, Position position) {
        Snapshot current = snapshot();
        Optional<NodeEntry> entry = current.index().nodeAt(uri, position);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Optional<Resolution> resolution = current.resolver().resolve(entry.get());
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        ASTNode declaration = resolution.get().primary();
        ClassNode inferred = declaration instanceof VariableExpression variable && variable.isDynamicTyped()
                ? current.resolver().typeOfVariable(variable)
                : null;
        return Optional.of(new Hover(HoverRenderer.render(declaration, resolution.get(), inferred), entry.get().range()));
    }

    public List<Symbol> documentSymbols(String uri) {
        if (answerFromCache()) {
            return nest(cachedSymbols().stream()
                    .filter(s -> s.location() != null && s.location().uri().equals(uri)).toList());
        }
        return snapshot().symbols().documentSymbols(uri);
    }

    /**
     * The cache stores a document's declarations flat; an outline is a tree. Each symbol becomes a child of the
     * smallest other symbol whose range contains it, so a cached answer has the same shape as a compiled one.
     */
    static List<Symbol> nest(List<Symbol> flat) {
        Map<Symbol, List<Symbol>> children = new java.util.LinkedHashMap<>();
        List<Symbol> roots = new ArrayList<>();
        for (Symbol symbol : flat) {
            Symbol parent = null;
            for (Symbol candidate : flat) {
                if (candidate != symbol && encloses(candidate, symbol)
                        && (parent == null || encloses(parent, candidate))) {
                    parent = candidate;
                }
            }
            if (parent == null) {
                roots.add(symbol);
            } else {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(symbol);
            }
        }
        List<Symbol> result = new ArrayList<>();
        for (Symbol root : roots) {
            result.add(withChildren(root, children));
        }
        return result;
    }

    private static boolean encloses(Symbol outer, Symbol inner) {
        Range a = outer.location().range();
        Range b = inner.location().range();
        return a.contains(b) && !a.equals(b);
    }

    private static Symbol withChildren(Symbol symbol, Map<Symbol, List<Symbol>> children) {
        List<Symbol> nested = new ArrayList<>();
        for (Symbol child : children.getOrDefault(symbol, List.of())) {
            nested.add(withChildren(child, children));
        }
        return new Symbol(symbol.name(), symbol.kind(), symbol.container(), symbol.location(), symbol.selectionRange(),
                symbol.detail(), nested);
    }

    public List<Symbol> workspaceSymbols(String query) {
        if (answerFromCache()) {
            String needle = query == null ? "" : query.toLowerCase();
            return cachedSymbols().stream()
                    .filter(s -> s.name().toLowerCase().contains(needle))
                    .limit(WORKSPACE_SYMBOL_LIMIT)
                    .toList();
        }
        return snapshot().symbols().workspaceSymbols(query, WORKSPACE_SYMBOL_LIMIT);
    }

    public List<Completion> completion(String uri, Position position) {
        return CompletionSupport.complete(snapshot(), uri, position);
    }

    public Optional<SignatureHelp> signatureHelp(String uri, Position position) {
        return SignatureSupport.signatureHelp(snapshot(), uri, position);
    }

    /**
     * Implementations of the declaration under the cursor: overriding methods in workspace subclasses and
     * implementations for a method, subclasses and implementors for a class or interface.
     */
    public List<Location> implementations(String uri, Position position) {
        return DocumentSupport.implementations(snapshot(), uri, position);
    }

    /** Occurrences of the symbol under the cursor within the same document, declarations and writes marked. */
    public List<org.groovyscope.engine.model.Highlight> highlights(String uri, Position position) {
        return DocumentSupport.highlights(snapshot(), uri, position);
    }

    /** Foldable classes, methods, closures, multi-line comments and import groups of a document. */
    public List<org.groovyscope.engine.model.FoldingRange> foldingRanges(String uri) {
        Snapshot current = snapshot();
        String text = workspace.text(uri);
        ModuleNode module = current.result().modules().get(uri);
        if (text != null && !text.equals(current.result().sources().get(uri))) {
            module = null; // the text moved on since the compile; fold by text only rather than at stale lines
        }
        return DocumentSupport.foldingRanges(module, text);
    }

    /** Inferred-type hints for {@code def} locals whose declaration lies within {@code range}. */
    public List<org.groovyscope.engine.model.InlayHint> inlayHints(String uri, Range range) {
        Snapshot current = snapshot();
        String text = current.result().sources().get(uri);
        if (text == null) {
            return List.of();
        }
        return DocumentSupport.inlayHints(current.result().modules().get(uri), new TextLines(text), range);
    }

    public RenamePreparation prepareRename(String uri, Position position) {
        Snapshot current = snapshot();
        Optional<NodeEntry> entry = current.index().nodeAt(uri, position);
        if (entry.isEmpty()) {
            return RenamePreparation.refused("nothing to rename at this position");
        }
        Optional<Resolution> resolution = current.resolver().resolve(entry.get());
        if (resolution.isEmpty()) {
            return RenamePreparation.refused("the symbol at this position cannot be resolved");
        }
        if (resolution.get().confidence() == Confidence.DYNAMIC_UNKNOWN) {
            return RenamePreparation.refused("the target is matched by name only (dynamic receiver); rename refused");
        }
        ASTNode declaration = resolution.get().primary();
        if (current.index().declarationEntry(declaration).isEmpty()) {
            return RenamePreparation.refused("the declaration lives outside the workspace");
        }
        String name = Symbols.nameOf(declaration);
        Range range = entry.get().isDeclaration()
                ? AstRanges.nameRange(declaration, entry.get().range(), current.index().lines(uri))
                : nameRangeOfUsage(current, entry.get(), declaration);
        return new RenamePreparation(range, name, null);
    }

    public RenameResult rename(String uri, Position position, String newName) {
        if (newName == null || !IDENTIFIER.matcher(newName).matches()) {
            return RenameResult.refused("'" + newName + "' is not a valid identifier");
        }
        RenamePreparation preparation = prepareRename(uri, position);
        if (preparation.isRefused()) {
            return RenameResult.refused(preparation.refusalReason());
        }
        Snapshot current = snapshot();
        NodeEntry entry = current.index().nodeAt(uri, position).orElseThrow();
        ASTNode declaration = current.resolver().resolve(entry).orElseThrow().primary();
        List<Reference> references = referencesTo(current, declaration, true);
        long unknown = references.stream().filter(r -> r.confidence() == Confidence.DYNAMIC_UNKNOWN).count();
        if (unknown > 0) {
            return RenameResult.refused(unknown + " reference(s) are matched by name only (dynamic dispatch); "
                    + "rename refused rather than guessed");
        }
        Map<String, List<TextEdit>> edits = new HashMap<>();
        for (Reference reference : references) {
            List<TextEdit> list = edits.computeIfAbsent(reference.location().uri(), k -> new ArrayList<>());
            TextEdit edit = new TextEdit(reference.location().range(), newName);
            if (!list.contains(edit)) {
                list.add(edit);
            }
        }
        return RenameResult.of(edits);
    }
}
