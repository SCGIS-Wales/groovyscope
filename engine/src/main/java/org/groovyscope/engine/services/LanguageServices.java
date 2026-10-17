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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
                // A file that no longer parses keeps its last good AST so navigation degrades instead of vanishing.
                for (Map.Entry<String, ModuleNode> previous : snapshot.result().modules().entrySet()) {
                    if (!modules.containsKey(previous.getKey()) && sources.containsKey(previous.getKey())) {
                        modules.put(previous.getKey(), previous.getValue());
                    }
                }
                AstIndex index = AstIndex.build(modules, sources);
                Resolver resolver = new Resolver(index);
                version++;
                snapshot = new Snapshot(result, index, resolver, new Symbols(index), version);
                persistSymbols(snapshot, sources);
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
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
        background.submit(() -> {
            try {
                store.save(sources, symbols);
            } catch (IOException e) {
                org.groovyscope.engine.log.EngineLog.warn("%s", "symbol cache not saved: " + e.getMessage());
            }
        });
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

    /** The current snapshot, rebuilt first when stale. */
    public Snapshot snapshot() {
        if (isDirty()) {
            synchronized (lock) {
                // Waiting for a compile already under way may be enough; rebuild only when it missed an edit.
                return isDirty() ? refresh() : snapshot;
            }
        }
        return snapshot;
    }

    public Map<String, List<Diagnostic>> diagnostics() {
        return snapshot().result().diagnostics();
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

    private static Range nameRangeOfUsage(Snapshot current, NodeEntry usage, ASTNode declaration) {
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
