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
package org.groovyscope.engine.callgraph;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.model.ImpactReport;
import org.groovyscope.engine.model.ImpactReport.Change;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.semantics.Members;
import org.groovyscope.engine.semantics.Resolution;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.Symbols;
import org.groovyscope.engine.workspace.TestSources;

/**
 * Turns the call graph into an answer to "what breaks if I change this?". Name-only matches are reported
 * for review, never counted as resolved; transitive traversal follows only static and inferred edges.
 */
public final class ImpactAnalyzer {

    /** How many levels of callers-of-callers to follow. */
    public static final int MAX_DEPTH = 5;

    private final AstIndex index;
    private final Resolver resolver;
    private final CallGraph graph;
    private final Symbols symbols;

    public ImpactAnalyzer(AstIndex index, Resolver resolver, CallGraph graph, Symbols symbols) {
        this.index = index;
        this.resolver = resolver;
        this.graph = graph;
        this.symbols = symbols;
    }

    /** The impact of changing {@code declaration} (a method, constructor, class, field or property). */
    public ImpactReport analyze(ASTNode declaration, Change change) {
        List<CallSite> direct = new ArrayList<>();
        List<CallSite> dynamic = new ArrayList<>();
        for (CallSite site : sitesTargeting(declaration)) {
            if (site.confidence() == Confidence.DYNAMIC_UNKNOWN) {
                dynamic.add(site);
            } else {
                direct.add(site);
            }
        }
        List<CallSite> transitive = transitiveCallers(declaration, direct);
        List<Symbol> overrides = declaration instanceof MethodNode method && !(method instanceof ConstructorNode)
                ? overridesOf(method)
                : List.of();
        List<CallSite> tests = new ArrayList<>();
        for (List<CallSite> sites : List.of(direct, transitive, dynamic)) {
            for (CallSite site : sites) {
                if (isTest(site)) {
                    tests.add(site);
                }
            }
        }
        int score = score(declaration, change, direct, transitive, overrides, dynamic);
        return new ImpactReport(symbols.of(declaration), change, direct, transitive, overrides, tests, dynamic,
                score, level(score), suggestions(declaration, change, direct, overrides, tests, dynamic));
    }

    /** Call sites that target the declaration, or references to it for non-methods. */
    List<CallSite> sitesTargeting(ASTNode declaration) {
        if (declaration instanceof MethodNode) {
            return graph.incoming(declaration);
        }
        List<CallSite> sites = new ArrayList<>();
        for (NodeEntry entry : index.allEntries()) {
            if (entry.isDeclaration() || entry.role() == NodeEntry.Role.OTHER) {
                continue;
            }
            Optional<Resolution> resolution = resolver.resolve(entry);
            if (resolution.isPresent() && resolution.get().includes(declaration)) {
                ASTNode caller = CallGraph.callerOf(entry);
                sites.add(new CallSite(symbols.of(caller), symbols.of(declaration),
                        new Location(entry.uri(), entry.range()), resolution.get().confidence()));
            }
        }
        return sites;
    }

    private List<CallSite> transitiveCallers(ASTNode declaration, List<CallSite> direct) {
        List<CallSite> result = new ArrayList<>();
        Set<ASTNode> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        visited.add(declaration);
        Deque<ASTNode> frontier = new ArrayDeque<>();
        for (CallSite site : direct) {
            ASTNode caller = callerNode(site);
            if (caller != null && visited.add(caller)) {
                frontier.add(caller);
            }
        }
        int depth = 1;
        while (!frontier.isEmpty() && depth < MAX_DEPTH) {
            Deque<ASTNode> next = new ArrayDeque<>();
            for (ASTNode caller : frontier) {
                for (CallSite site : graph.incoming(caller)) {
                    result.add(site);
                    if (site.confidence() == Confidence.DYNAMIC_UNKNOWN) {
                        continue; // reported, so a test reaching it is a possible test, but not followed further
                    }
                    ASTNode upstream = callerNode(site);
                    if (upstream != null && visited.add(upstream)) {
                        next.add(upstream);
                    }
                }
            }
            frontier = next;
            depth++;
        }
        return result;
    }

    /** The AST node behind a call site's caller symbol, found through the declaration index. */
    private ASTNode callerNode(CallSite site) {
        if (site.caller().location() == null) {
            return null;
        }
        for (NodeEntry entry : index.entries(site.caller().location().uri())) {
            if (entry.isDeclaration() && entry.range().equals(site.caller().location().range())
                    && (entry.node() instanceof MethodNode || entry.node() instanceof ClassNode)) {
                return entry.node();
            }
        }
        return null;
    }

    /** Methods of the same name and erased parameter types in subclasses (overriding) and superclasses (overridden). */
    List<Symbol> overridesOf(MethodNode method) {
        Set<Symbol> found = new LinkedHashSet<>();
        ClassNode owner = method.getDeclaringClass();
        for (ClassNode superType : Members.hierarchy(owner)) {
            if (superType == owner) {
                continue;
            }
            for (MethodNode candidate : Members.readable(() -> superType.getDeclaredMethods(method.getName()))) {
                if (Members.sameErasure(candidate, method) && !candidate.isSynthetic()) {
                    found.add(symbols.of(candidate));
                }
            }
        }
        for (ClassNode classNode : index.classes()) {
            if (classNode == owner || !Members.hierarchy(classNode).contains(owner)) {
                continue;
            }
            for (MethodNode candidate : Members.readable(() -> classNode.getDeclaredMethods(method.getName()))) {
                if (Members.sameErasure(candidate, method) && !candidate.isSynthetic()) {
                    found.add(symbols.of(candidate));
                }
            }
        }
        return new ArrayList<>(found);
    }

    static boolean isTest(CallSite site) {
        Location location = site.caller().location();
        if (location == null) {
            return false;
        }
        String uri = location.uri();
        String container = site.caller().container() == null ? "" : site.caller().container();
        String name = site.caller().name() == null ? "" : site.caller().name();
        return uri.contains("/src/test/") || uri.contains("/test/groovy/") || TestSources.isTestPath(uri)
                || TestSources.isTestName(container) || name.endsWith("Spec") || name.endsWith("Test");
    }

    static int score(ASTNode declaration, Change change, List<CallSite> direct, List<CallSite> transitive,
            List<Symbol> overrides, List<CallSite> dynamic) {
        int score = direct.size() * 8 + transitive.size() * 2 + overrides.size() * 10 + dynamic.size() * 15;
        if (declaration instanceof MethodNode method && Modifier.isPublic(method.getModifiers())) {
            score += 10;
        }
        if (change == Change.DELETE || change == Change.RENAME) {
            score += 10;
        }
        if (change == Change.BEHAVIOUR) {
            score = Math.max(score - 10, 0);
        }
        return Math.min(100, score);
    }

    static String level(int score) {
        if (score >= 60) {
            return "high";
        }
        return score >= 25 ? "medium" : "low";
    }

    static List<String> suggestions(ASTNode declaration, Change change, List<CallSite> direct, List<Symbol> overrides,
            List<CallSite> tests, List<CallSite> dynamic) {
        List<String> suggestions = new ArrayList<>();
        if (!dynamic.isEmpty()) {
            suggestions.add("review " + dynamic.size() + " call site(s) matched by name only (dynamic dispatch) by hand");
        }
        if (!overrides.isEmpty() && (change == Change.SIGNATURE || change == Change.RENAME || change == Change.DELETE)) {
            suggestions.add("update " + overrides.size() + " overriding or overridden method(s) together");
        }
        if (!tests.isEmpty()) {
            Set<String> files = new LinkedHashSet<>();
            Set<String> possible = new LinkedHashSet<>();
            for (CallSite test : tests) {
                files.add(test.caller().location().uri());
                if (test.confidence() == Confidence.DYNAMIC_UNKNOWN) {
                    possible.add(test.caller().location().uri());
                }
            }
            for (CallSite test : tests) {
                if (test.confidence() != Confidence.DYNAMIC_UNKNOWN) {
                    possible.remove(test.caller().location().uri());
                }
            }
            suggestions.add("run the " + files.size() + " test file(s) that reach this symbol"
                    + (possible.isEmpty() ? "" : " (" + possible.size() + " possibly, through calls matched by name only)"));
        } else if (!direct.isEmpty()) {
            suggestions.add("no test reaches this symbol; add one before changing it");
        }
        if (change == Change.SIGNATURE && !direct.isEmpty()) {
            suggestions.add("update " + direct.size() + " direct call site(s) for the new signature");
        }
        if (change == Change.RENAME) {
            suggestions.add("use `rename` through the LSP: it refuses when any site is matched by name only");
        }
        if (declaration instanceof MethodNode method && method.getDeclaringClass() != null
                && method.getDeclaringClass().isScript()) {
            suggestions.add("script-level method: callers outside the workspace (pipelines, CLI) cannot be seen");
        }
        return suggestions;
    }
}
