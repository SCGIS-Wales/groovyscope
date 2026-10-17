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
    private volatile boolean dirty = true;
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

    /** Marks the snapshot stale; the next query recompiles. */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Recompiles the workspace now and returns the fresh snapshot. */
    public Snapshot refresh() {
        synchronized (lock) {
            Map<String, String> sources = workspace.sources();
            CompilationResult result = compiler.compile(sources, workspace.classpath(), workspace.config().trust());
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
            dirty = false;
            persistSymbols(snapshot, sources);
            return snapshot;
        }
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
                System.err.println("groovyscope-engine: symbol cache not saved: " + e.getMessage());
            }
        });
    }

    /** Symbols from the cache for documents whose text has not changed since they were stored. */
    private List<Symbol> cachedSymbols() {
        List<Symbol> cached = cachedSymbols;
        if (cached == null) {
            try {
                cached = store == null ? List.of() : store.load(workspace.sources());
            } catch (IOException e) {
                System.err.println("groovyscope-engine: symbol cache not read: " + e.getMessage());
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
        if (dirty) {
            return refresh();
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
            return cachedSymbols().stream().filter(s -> s.location().uri().equals(uri)).toList();
        }
        return snapshot().symbols().documentSymbols(uri);
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
