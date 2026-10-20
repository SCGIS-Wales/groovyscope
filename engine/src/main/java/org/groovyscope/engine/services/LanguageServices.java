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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import org.codehaus.groovy.ast.ASTNode;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.compile.CompilationResult;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.compile.MissingDependencies;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.SymbolStore;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Completion;
import org.groovyscope.engine.model.Definition;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.FoldingRange;
import org.groovyscope.engine.model.Highlight;
import org.groovyscope.engine.model.Hover;
import org.groovyscope.engine.model.ImpactReport;
import org.groovyscope.engine.model.InlayHint;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Reference;
import org.groovyscope.engine.model.RenamePreparation;
import org.groovyscope.engine.model.RenameResult;
import org.groovyscope.engine.model.SignatureHelp;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.semantics.DynamicCodeFinder;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The engine's API, independent of any protocol. Each query runs against the current {@link Snapshot};
 * when documents changed since the last compile, the snapshot is rebuilt first so answers are never stale.
 * {@link SnapshotManager} keeps the compiled state and {@link NavigationQueries} answers the queries; this facade
 * holds the caches derived from the snapshot (the call graph, the quality services, the folded diagnostics).
 */
public final class LanguageServices {

    /** How long {@link #close} waits for background work (a symbol save) before closing the store. */
    static final long CLOSE_DRAIN_SECONDS = SnapshotManager.CLOSE_DRAIN_SECONDS;
    static final int WORKSPACE_SYMBOL_LIMIT = NavigationQueries.WORKSPACE_SYMBOL_LIMIT;
    /** Index entries visited between two cancellation checks in a whole-workspace walk. */
    static final int CHECKPOINT_EVERY = NavigationQueries.CHECKPOINT_EVERY;
    /** The code of the warning that names what the workspace budget left out of the analysis. */
    public static final String BUDGET_CODE = "GS101";

    private final WorkspaceModel workspace;
    private final Object lock = new Object();
    private final SnapshotManager snapshots;
    private final NavigationQueries queries;
    private volatile QualityServices quality;
    private volatile CallGraph graph;
    private volatile long graphVersion = -1;

    private record FoldedDiagnostics(long version, Map<String, List<Diagnostic>> diagnostics) {
    }

    private volatile FoldedDiagnostics folded;

    public LanguageServices(WorkspaceModel workspace, GroovyCompiler compiler) {
        this(workspace, compiler, null);
    }

    /**
     * @param store optional on-disk symbol cache; answers symbol searches before the first compile and is
     *              rewritten after every compile
     */
    public LanguageServices(WorkspaceModel workspace, GroovyCompiler compiler, SymbolStore store) {
        this.workspace = workspace;
        this.snapshots = new SnapshotManager(workspace, compiler, store, lock, () -> {
            graph = null;
            graphVersion = -1;
        });
        this.queries = new NavigationQueries(workspace, this::snapshot, this::callGraph, snapshots);
    }

    /** True once at least one compile has produced a snapshot. */
    public boolean hasCompiled() {
        return snapshots.hasCompiled();
    }

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

    /** The impact of changing the declaration under the cursor. */
    public Optional<ImpactReport> impact(String uri, Position position, ImpactReport.Change change) {
        return queries.impact(uri, position, change);
    }

    /**
     * The impact of changing every declaration a name denotes: {@code pkg.Class}, {@code pkg.Class.member},
     * {@code Class#member} or a bare {@code Class}; overloads each get a report.
     */
    public List<ImpactReport> impactOf(String qualifiedName, ImpactReport.Change change) {
        return queries.impactOf(qualifiedName, change);
    }

    /** Declarations a dotted or hash-separated name refers to. */
    public List<ASTNode> findDeclarations(Snapshot current, String name) {
        return NavigationQueries.findDeclarations(current, name);
    }

    /** The declaration (method, constructor, class) at a position, for call hierarchy, or empty. */
    public Optional<Symbol> callHierarchyItem(String uri, Position position) {
        return queries.callHierarchyItem(uri, position);
    }

    /** The declaration node a symbol (from {@link #callHierarchyItem}) stands for, or empty. */
    public Optional<ASTNode> declarationOf(Symbol symbol) {
        return queries.declarationOf(symbol);
    }

    /** Calls into the given declaration. */
    public List<CallSite> incomingCalls(Symbol symbol) {
        return queries.incomingCalls(symbol);
    }

    /** Calls made from within the given declaration. */
    public List<CallSite> outgoingCalls(Symbol symbol) {
        return queries.outgoingCalls(symbol);
    }

    /** Constructs that defeat static analysis, in the given documents (all when empty). */
    public List<DynamicCodeFinder.Finding> dynamicCode(Collection<String> uris) {
        return queries.dynamicCode(uris);
    }

    /** Semantic tokens for a document, LSP-encoded. */
    public List<Integer> semanticTokens(String uri) {
        return queries.semanticTokens(uri);
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

    /** For specs: the clock the disk check window is measured on. */
    void clock(LongSupplier replacement) {
        snapshots.clock(replacement);
    }

    /**
     * Marks the snapshot stale when files changed on disk since the last compile. Requests from the CLI and the
     * MCP server call this first: they have no editor sending change notifications.
     */
    public void syncWithDisk() {
        snapshots.syncWithDisk();
    }

    /** Marks the snapshot stale; the next query recompiles. */
    public void markDirty() {
        snapshots.markDirty();
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
        snapshots.markDirty();
        // Until a compile under the new settings is published, no request is answered from the old one.
        snapshots.reconfigured();
    }

    public boolean isDirty() {
        return snapshots.isDirty();
    }

    /** Recompiles the workspace when it changed since the last compile, and returns the snapshot. */
    public Snapshot refresh() {
        return snapshots.refresh();
    }

    /** The snapshot last published, without compiling. */
    public Snapshot currentSnapshot() {
        return snapshots.currentSnapshot();
    }

    /** The compile last queued on the background thread (done when none is queued). */
    public Future<?> backgroundCompile() {
        return snapshots.backgroundCompile();
    }

    /** The previous snapshot's trees and index with the failure reported on the first document. */
    static Snapshot failedSnapshot(Snapshot previous, Map<String, String> sources, Throwable failure, long version) {
        return SnapshotManager.failedSnapshot(previous, sources, failure, version);
    }

    /**
     * The current snapshot, rebuilt first when stale; one compile per change however many requests need it. A light
     * request about a document unchanged since the published compile gets the published snapshot at once while the
     * compile runs in the background.
     */
    public Snapshot snapshot() {
        return snapshots.snapshot();
    }

    /** Files in the current snapshot, without touching the disk (0 before the first compile). */
    public int fileCount() {
        return snapshots.fileCount();
    }

    /** When a request last asked for the snapshot, on the {@link System#nanoTime()} clock. */
    public long lastUsedNanos() {
        return snapshots.lastUsedNanos();
    }

    /** Marks this workspace as just used (a connection was handed it), so an idle eviction leaves it. */
    public void touch() {
        snapshots.touch();
    }

    /** True once {@link #close} ran: the workspace answers nothing more. */
    public boolean isClosed() {
        return snapshots.isClosed();
    }

    /**
     * Releases what this workspace holds: the kept compile's loader, pending saves and the background thread, which
     * is drained (bounded) before the symbol store closes so no save runs against a closed database.
     */
    public void close() {
        snapshots.close(() -> {
            graph = null;
            QualityServices released = quality;
            quality = null;
            if (released != null) {
                released.close();
            }
        });
    }

    /**
     * Compile diagnostics per document. With no classpath configured, unresolved imports are folded into one
     * {@code GS100} finding per file (see {@link MissingDependencies}); with a classpath they stay real errors.
     */
    public Map<String, List<Diagnostic>> diagnostics() {
        Snapshot current = snapshot();
        Map<String, List<Diagnostic>> base = current.result().diagnostics();
        if (workspace.classpath().isEmpty()) {
            FoldedDiagnostics cached = folded;
            if (cached == null || cached.version() != current.version()) {
                cached = new FoldedDiagnostics(current.version(), Map.copyOf(MissingDependencies.fold(
                        current.result().diagnostics(), current.result().sources(),
                        PracticeFinder.severity(MissingDependencies.CODE, workspace.config()))));
                folded = cached;
            }
            base = cached.diagnostics();
        }
        return withBudgetWarning(base, current.result().sources().keySet());
    }

    /**
     * The compile diagnostics of a result that is not the snapshot (see {@link #compileOnly}): unresolved library
     * imports folded the same way when no classpath is configured.
     */
    public Map<String, List<Diagnostic>> diagnosticsOf(CompilationResult result) {
        if (!workspace.classpath().isEmpty()) {
            return result.diagnostics();
        }
        return MissingDependencies.fold(result.diagnostics(), result.sources(),
                PracticeFinder.severity(MissingDependencies.CODE, workspace.config()));
    }

    /**
     * {@code targets} compiled on their own against the classes of the last full compile, when they are exactly the
     * documents whose text changed since it; empty when that does not hold (see {@link SnapshotManager#compileOnly}).
     * The caller closes the result.
     */
    public Optional<CompilationResult> compileOnly(Collection<String> targets) {
        return snapshots.compileOnly(targets);
    }

    /** Adds one {@code GS101} warning on the first document when the budget left files out; else {@code base}. */
    Map<String, List<Diagnostic>> withBudgetWarning(Map<String, List<Diagnostic>> base, Set<String> uris) {
        List<java.nio.file.Path> omitted = workspace.omitted();
        if (omitted.isEmpty() || uris.isEmpty()) {
            return base;
        }
        String first = uris.stream().sorted().findFirst().orElseThrow();
        EngineConfig.Budget budget = workspace.config().budget();
        String example = workspace.root().relativize(omitted.get(0)).toString();
        Diagnostic warning = new Diagnostic(first, Range.of(0, 0, 0, 1), Diagnostic.Severity.WARNING, "groovyscope",
                BUDGET_CODE, omitted.size() + " file(s) left out of the analysis (first: " + example
                + "): the workspace is over workspace.maxFiles (" + budget.maxFiles() + ") or workspace.maxTotalBytes ("
                + budget.maxTotalBytes() + "); raise them in .groovyscope.yaml or narrow workspace.sourceRoots");
        Map<String, List<Diagnostic>> copy = new HashMap<>(base);
        List<Diagnostic> list = new ArrayList<>(copy.getOrDefault(first, List.of()));
        list.add(warning);
        copy.put(first, list);
        return copy;
    }

    /** Drops every cache that can be rebuilt from the snapshot: what memory pressure asks for. */
    public void shed() {
        // The fields are volatile and only caches: no lock, so shedding never waits behind a request (E-18).
        graph = null;
        graphVersion = -1;
        quality = null;
        folded = null;
        snapshots.shed();
    }

    public Optional<Definition> definition(String uri, Position position) {
        return queries.definition(uri, position);
    }

    public Optional<Definition> typeDefinition(String uri, Position position) {
        return queries.typeDefinition(uri, position);
    }

    public List<Reference> references(String uri, Position position, boolean includeDeclaration) {
        return queries.references(uri, position, includeDeclaration);
    }

    List<Reference> referencesTo(Snapshot current, ASTNode declaration, boolean includeDeclaration) {
        return NavigationQueries.referencesTo(current, declaration, includeDeclaration);
    }

    static Range nameRangeOfUsage(Snapshot current, NodeEntry usage, ASTNode declaration) {
        return NavigationQueries.nameRangeOfUsage(current, usage, declaration);
    }

    public Optional<Hover> hover(String uri, Position position) {
        return queries.hover(uri, position);
    }

    public List<Symbol> documentSymbols(String uri) {
        return queries.documentSymbols(uri);
    }

    /** The flat symbols of a cached outline nested into a tree (see {@link NavigationQueries#nest}). */
    static List<Symbol> nest(List<Symbol> flat) {
        return NavigationQueries.nest(flat);
    }

    public List<Symbol> workspaceSymbols(String query) {
        return queries.workspaceSymbols(query);
    }

    public List<Completion> completion(String uri, Position position) {
        return queries.completion(uri, position);
    }

    public Optional<SignatureHelp> signatureHelp(String uri, Position position) {
        return queries.signatureHelp(uri, position);
    }

    /**
     * Implementations of the declaration under the cursor: overriding methods in workspace subclasses and
     * implementations for a method, subclasses and implementors for a class or interface.
     */
    public List<Location> implementations(String uri, Position position) {
        return queries.implementations(uri, position);
    }

    /** Occurrences of the symbol under the cursor within the same document, declarations and writes marked. */
    public List<Highlight> highlights(String uri, Position position) {
        return queries.highlights(uri, position);
    }

    /** Foldable classes, methods, closures, multi-line comments and import groups of a document. */
    public List<FoldingRange> foldingRanges(String uri) {
        return queries.foldingRanges(uri);
    }

    /** Inferred-type hints for {@code def} locals whose declaration lies within {@code range}. */
    public List<InlayHint> inlayHints(String uri, Range range) {
        return queries.inlayHints(uri, range);
    }

    public RenamePreparation prepareRename(String uri, Position position) {
        return queries.prepareRename(uri, position);
    }

    public RenameResult rename(String uri, Position position, String newName) {
        return queries.rename(uri, position, newName);
    }
}
