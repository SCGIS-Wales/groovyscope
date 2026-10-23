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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.callgraph.ImpactAnalyzer;
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Completion;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.model.Definition;
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
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.DynamicCodeFinder;
import org.groovyscope.engine.semantics.HoverRenderer;
import org.groovyscope.engine.semantics.Resolution;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.Symbols;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The queries over a workspace's snapshot: definitions, references, hover, symbols, the outline, rename, call
 * hierarchy, impact and the editor features ({@code completion}, folding, hints and the rest). Each reads the
 * snapshot it is handed by the owner, which rebuilds it first when stale.
 */
final class NavigationQueries {

    static final int WORKSPACE_SYMBOL_LIMIT = 500;
    /** Index entries visited between two cancellation checks in a whole-workspace walk. */
    static final int CHECKPOINT_EVERY = 256;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private final WorkspaceModel workspace;
    private final Supplier<Snapshot> snapshots;
    private final Supplier<CallGraph> callGraph;
    private final SnapshotManager manager;

    NavigationQueries(WorkspaceModel workspace, Supplier<Snapshot> snapshots, Supplier<CallGraph> callGraph,
            SnapshotManager manager) {
        this.workspace = workspace;
        this.snapshots = snapshots;
        this.callGraph = callGraph;
        this.manager = manager;
    }

    private ImpactAnalyzer impactAnalyzer(Snapshot current) {
        return new ImpactAnalyzer(current.index(), current.resolver(), callGraph.get(), current.symbols());
    }

    /** The impact of changing the declaration under the cursor. */
    Optional<ImpactReport> impact(String uri, Position position, ImpactReport.Change change) {
        Snapshot current = snapshots.get();
        return current.index().nodeAt(uri, position)
                .flatMap(entry -> current.resolver().resolve(entry))
                .filter(resolution -> current.index().declarationEntry(resolution.primary()).isPresent())
                .map(resolution -> impactAnalyzer(current).analyze(resolution.primary(), change));
    }

    /** The impact of changing every declaration a name denotes; overloads each get a report. */
    List<ImpactReport> impactOf(String qualifiedName, ImpactReport.Change change) {
        Snapshot current = snapshots.get();
        List<ImpactReport> reports = new ArrayList<>();
        for (ASTNode declaration : findDeclarations(current, qualifiedName)) {
            reports.add(impactAnalyzer(current).analyze(declaration, change));
        }
        return reports;
    }

    /** Declarations a dotted or hash-separated name refers to. */
    static List<ASTNode> findDeclarations(Snapshot current, String name) {
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
            for (MethodNode method : classNode.getDeclaredMethods(memberPart)) {
                if (!method.isSynthetic()) {
                    found.add(method);
                }
            }
            PropertyNode property = classNode.getProperty(memberPart);
            if (property != null) {
                found.add(property);
            } else {
                FieldNode field = classNode.getDeclaredField(memberPart);
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
    Optional<Symbol> callHierarchyItem(String uri, Position position) {
        Snapshot current = snapshots.get();
        return current.index().nodeAt(uri, position)
                .flatMap(entry -> current.resolver().resolve(entry))
                .map(Resolution::primary)
                .filter(node -> node instanceof MethodNode || node instanceof ClassNode)
                .filter(node -> current.index().declarationEntry(node).isPresent())
                .map(node -> current.symbols().of(node));
    }

    /** The declaration node a symbol (from {@link #callHierarchyItem}) stands for, or empty. */
    Optional<ASTNode> declarationOf(Symbol symbol) {
        Snapshot current = snapshots.get();
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
    List<CallSite> incomingCalls(Symbol symbol) {
        return declarationOf(symbol).map(node -> callGraph.get().incoming(node)).orElse(List.of());
    }

    /** Calls made from within the given declaration. */
    List<CallSite> outgoingCalls(Symbol symbol) {
        return declarationOf(symbol).map(node -> callGraph.get().outgoing(node)).orElse(List.of());
    }

    /** Constructs that defeat static analysis, in the given documents (all when empty). */
    List<DynamicCodeFinder.Finding> dynamicCode(Collection<String> uris) {
        return DynamicCodeFinder.find(snapshots.get().index(), uris);
    }

    /** Semantic tokens for a document, LSP-encoded. */
    List<Integer> semanticTokens(String uri) {
        return SemanticTokensSupport.encode(snapshots.get(), uri);
    }

    Optional<Definition> definition(String uri, Position position) {
        Snapshot current = snapshots.get();
        return current.index().nodeAt(uri, position)
                .flatMap(entry -> current.resolver().resolve(entry))
                .map(resolution -> new Definition(current.symbols().of(resolution.primary()), resolution.confidence()));
    }

    Optional<Definition> typeDefinition(String uri, Position position) {
        Snapshot current = snapshots.get();
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

    List<Reference> references(String uri, Position position, boolean includeDeclaration) {
        Snapshot current = snapshots.get();
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

    static List<Reference> referencesTo(Snapshot current, ASTNode declaration, boolean includeDeclaration) {
        List<Reference> references = new ArrayList<>();
        int visited = 0;
        for (NodeEntry candidate : current.index().allEntries()) {
            if (++visited % CHECKPOINT_EVERY == 0) {
                Cancellation.checkpoint(); // a whole-workspace walk: a cancelled request leaves part-way
            }
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

    Optional<Hover> hover(String uri, Position position) {
        Snapshot current = snapshots.get();
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
        String markdown = HoverRenderer.render(declaration, resolution.get(), inferred);
        List<Hover.InferredType> assigned = declaration instanceof VariableExpression local
                ? org.groovyscope.engine.practices.PracticeFinder.assignedTypes(entry.get().enclosingClass(),
                        entry.get().enclosingMethod(), local)
                : List.of();
        if (!assigned.isEmpty()) {
            List<String> seen = new ArrayList<>();
            for (Hover.InferredType type : assigned) {
                seen.add((type.type() == null ? "unknown" : type.type()) + " (line " + type.line() + ")");
            }
            markdown += "\n*assigned:* " + String.join(", ", seen) + "\n";
        }
        return Optional.of(new Hover(markdown, entry.get().range(), assigned));
    }

    List<Symbol> documentSymbols(String uri) {
        if (manager.answerFromCache()) {
            return nest(manager.cachedSymbols().stream()
                    .filter(s -> s.location() != null && s.location().uri().equals(uri)).toList());
        }
        return snapshots.get().symbols().documentSymbols(uri);
    }

    /**
     * The cache stores a document's declarations flat; an outline is a tree. Each symbol becomes a child of the
     * smallest other symbol whose range contains it, so a cached answer has the same shape as a compiled one.
     */
    static List<Symbol> nest(List<Symbol> flat) {
        Map<Symbol, List<Symbol>> children = new LinkedHashMap<>();
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

    List<Symbol> workspaceSymbols(String query) {
        if (manager.answerFromCache()) {
            String needle = query == null ? "" : query.toLowerCase();
            return manager.cachedSymbols().stream()
                    .filter(s -> s.name().toLowerCase().contains(needle))
                    .limit(WORKSPACE_SYMBOL_LIMIT)
                    .toList();
        }
        return snapshots.get().symbols().workspaceSymbols(query, WORKSPACE_SYMBOL_LIMIT);
    }

    List<Completion> completion(String uri, Position position) {
        return CompletionSupport.complete(snapshots.get(), uri, position);
    }

    Optional<SignatureHelp> signatureHelp(String uri, Position position) {
        return SignatureSupport.signatureHelp(snapshots.get(), uri, position);
    }

    /**
     * Implementations of the declaration under the cursor: overriding methods in workspace subclasses and
     * implementations for a method, subclasses and implementors for a class or interface.
     */
    List<Location> implementations(String uri, Position position) {
        return DocumentSupport.implementations(snapshots.get(), uri, position);
    }

    /** Occurrences of the symbol under the cursor within the same document, declarations and writes marked. */
    List<Highlight> highlights(String uri, Position position) {
        return DocumentSupport.highlights(snapshots.get(), uri, position);
    }

    /** Foldable classes, methods, closures, multi-line comments and import groups of a document. */
    List<FoldingRange> foldingRanges(String uri) {
        Snapshot current = snapshots.get();
        String text = workspace.text(uri);
        ModuleNode module = current.result().modules().get(uri);
        if (text != null && !text.equals(current.result().sources().get(uri))) {
            module = null; // the text moved on since the compile; fold by text only rather than at stale lines
        }
        return DocumentSupport.foldingRanges(module, text);
    }

    /** Inferred-type hints for {@code def} locals whose declaration lies within {@code range}. */
    List<InlayHint> inlayHints(String uri, Range range) {
        Snapshot current = snapshots.get();
        String text = current.result().sources().get(uri);
        if (text == null) {
            return List.of();
        }
        return DocumentSupport.inlayHints(current.result().modules().get(uri), new TextLines(text), range);
    }

    RenamePreparation prepareRename(String uri, Position position) {
        return prepareRename(snapshots.get(), uri, position);
    }

    private static RenamePreparation prepareRename(Snapshot current, String uri, Position position) {
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

    RenameResult rename(String uri, Position position, String newName) {
        if (newName == null || !IDENTIFIER.matcher(newName).matches()) {
            return RenameResult.refused("'" + newName + "' is not a valid identifier");
        }
        // One snapshot for the check and the edits: an edit in between cannot pull the node out from under it.
        Snapshot current = snapshots.get();
        RenamePreparation preparation = prepareRename(current, uri, position);
        if (preparation.isRefused()) {
            return RenameResult.refused(preparation.refusalReason());
        }
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
