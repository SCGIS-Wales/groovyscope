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
package org.groovyscope.engine.graph;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * The relationships between the workspace's classes as a typed graph: inheritance ({@code extends},
 * {@code implements}), composition ({@code has}: a field or property typed with another workspace class), use
 * ({@code uses}: a non-private method takes or returns another workspace class) and calls ({@code calls}: the
 * call graph's edges aggregated per pair of classes, with the weakest confidence among them). Every call edge
 * carries its {@link Effects effect}: {@code write} when any of its calls changes state, {@code unknown} when one is
 * resolved only dynamically, {@code read} otherwise; at method level every method node carries its own. Scoped to
 * seed classes and their neighbourhood when asked, capped in size, and optionally expanded to one class's methods.
 */
public final class RelationshipGraph {

    /** Edge kinds in the order they are documented. */
    public static final List<String> KINDS = List.of("extends", "implements", "has", "uses", "calls");
    /** Default cap on nodes. */
    public static final int DEFAULT_MAX_NODES = 60;

    /** A class (or, at method level, a method with its {@code effect}; {@code null} for classes) in the graph. */
    public record Node(String id, String name, String kind, String packageName, String uri, int startLine, int endLine,
            int members, boolean test, String effect) {
    }

    /** A relation between two nodes; {@code effect} is set for calls only. */
    public record Edge(String from, String to, String kind, String confidence, int count, String effect) {
    }

    /** The graph. */
    public record Graph(List<Node> nodes, List<Edge> edges, boolean truncated, int totalClasses) {
    }

    private record EdgeKey(String from, String to, String kind) {
    }

    private RelationshipGraph() {
    }

    /**
     * The class-level graph. {@code seeds} empty means every workspace class; otherwise the seeds plus their
     * neighbours up to {@code depth} hops over the requested {@code kinds}.
     */
    public static Graph build(AstIndex index, CallGraph graph, Collection<ClassNode> seeds, Collection<String> kinds,
            int depth, int maxNodes) {
        Set<String> wanted = kinds == null || kinds.isEmpty() ? new LinkedHashSet<>(KINDS) : new LinkedHashSet<>(kinds);
        Map<String, ClassNode> workspace = workspaceClasses(index);
        Map<EdgeKey, int[]> counts = new LinkedHashMap<>();
        Map<EdgeKey, Confidence> confidences = new LinkedHashMap<>();
        Map<EdgeKey, String> effects = new LinkedHashMap<>();
        for (ClassNode classNode : workspace.values()) {
            declaredEdges(classNode, workspace, wanted, counts, confidences);
        }
        if (wanted.contains("calls")) {
            Effects model = Effects.of(workspace, graph);
            for (CallSite site : graph.edges()) {
                String from = ownerOf(site.caller());
                String to = ownerOf(site.callee());
                if (from == null || to == null || from.equals(to) || !workspace.containsKey(from)
                        || !workspace.containsKey(to)) {
                    continue;
                }
                EdgeKey key = new EdgeKey(from, to, "calls");
                add(counts, confidences, key, site.confidence());
                effects.merge(key, model.call(site), Effects::combine);
            }
        }
        Set<String> chosen = chooseNodes(workspace, counts.keySet(), seeds, depth, maxNodes);
        List<Node> nodes = new ArrayList<>();
        for (String name : chosen) {
            nodes.add(node(workspace.get(name), index));
        }
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<EdgeKey, int[]> entry : counts.entrySet()) {
            EdgeKey key = entry.getKey();
            if (chosen.contains(key.from()) && chosen.contains(key.to())) {
                edges.add(new Edge(key.from(), key.to(), key.kind(), confidences.get(key).name(), entry.getValue()[0],
                        effects.get(key)));
            }
        }
        boolean truncated = chosen.size() < reachable(workspace, counts.keySet(), seeds, depth).size();
        return new Graph(nodes, edges, truncated, workspace.size());
    }

    /**
     * The method-level graph of one or more classes: their methods as nodes, calls among them as edges, and
     * every other class called collapsed into one class node per class.
     */
    public static Graph methods(AstIndex index, CallGraph graph, Collection<ClassNode> seeds, int maxNodes) {
        Map<String, ClassNode> workspace = workspaceClasses(index);
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<EdgeKey, int[]> counts = new LinkedHashMap<>();
        Map<EdgeKey, Confidence> confidences = new LinkedHashMap<>();
        Map<EdgeKey, String> effects = new LinkedHashMap<>();
        Effects model = Effects.of(workspace, graph);
        Set<String> seedNames = new LinkedHashSet<>();
        for (ClassNode classNode : seeds) {
            seedNames.add(classNode.getName());
            for (MethodNode method : classNode.getMethods()) {
                if (method.isSynthetic() || method.getLineNumber() < 1 || method.isScriptBody()) {
                    continue;
                }
                String id = classNode.getName() + "#" + method.getName();
                Range range = AstRanges.rangeOf(method);
                nodes.putIfAbsent(id, new Node(id, method.getName(), "method", classNode.getPackageName(),
                        index.declarationEntry(method).map(NodeEntry::uri).orElse(null),
                        range == null ? 0 : range.start().line(), range == null ? 0 : range.end().line(),
                        method.getParameters().length, false, model.method(id)));
            }
        }
        for (CallSite site : graph.edges()) {
            String fromOwner = ownerOf(site.caller());
            String toOwner = ownerOf(site.callee());
            if (fromOwner == null || toOwner == null) {
                continue;
            }
            boolean fromSeed = seedNames.contains(fromOwner);
            boolean toSeed = seedNames.contains(toOwner);
            if (!fromSeed && !toSeed) {
                continue;
            }
            String from = fromSeed ? fromOwner + "#" + site.caller().name() : fromOwner;
            String to = toSeed ? toOwner + "#" + site.callee().name() : toOwner;
            if (!fromSeed && workspace.containsKey(fromOwner)) {
                nodes.putIfAbsent(fromOwner, node(workspace.get(fromOwner), index));
            } else if (!fromSeed) {
                continue;
            }
            if (!toSeed) {
                nodes.putIfAbsent(toOwner, workspace.containsKey(toOwner) ? node(workspace.get(toOwner), index)
                        : new Node(toOwner, simple(toOwner), "external", packageOf(toOwner), null, 0, 0, 0, false,
                                null));
            }
            if (nodes.containsKey(from) && nodes.containsKey(to)) {
                EdgeKey key = new EdgeKey(from, to, "calls");
                add(counts, confidences, key, site.confidence());
                effects.merge(key, model.call(site), Effects::combine);
            }
        }
        List<Node> chosen = new ArrayList<>(nodes.values());
        boolean truncated = chosen.size() > maxNodes;
        if (truncated) {
            chosen = new ArrayList<>(chosen.subList(0, maxNodes));
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Node node : chosen) {
            ids.add(node.id());
        }
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<EdgeKey, int[]> entry : counts.entrySet()) {
            EdgeKey key = entry.getKey();
            if (ids.contains(key.from()) && ids.contains(key.to())) {
                edges.add(new Edge(key.from(), key.to(), key.kind(), confidences.get(key).name(), entry.getValue()[0],
                        effects.get(key)));
            }
        }
        return new Graph(chosen, edges, truncated, workspace.size());
    }

    /** Workspace classes by qualified name: no generated, anonymous or closure classes. */
    static Map<String, ClassNode> workspaceClasses(AstIndex index) {
        Map<String, ClassNode> classes = new TreeMap<>();
        for (ClassNode classNode : index.classes()) {
            String name = classNode.getName();
            if (classNode.getLineNumber() < 1 || name.contains("$_") || name.contains("$Trait$")
                    || (classNode.getOuterClass() != null && name.matches(".*\\$\\d+$"))) {
                continue;
            }
            classes.put(name, classNode);
        }
        return classes;
    }

    private static void declaredEdges(ClassNode classNode, Map<String, ClassNode> workspace, Set<String> wanted,
            Map<EdgeKey, int[]> counts, Map<EdgeKey, Confidence> confidences) {
        String own = classNode.getName();
        if (wanted.contains("extends")) {
            ClassNode superClass = classNode.getUnresolvedSuperClass(false);
            if (superClass != null && workspace.containsKey(superClass.getName()) && !superClass.getName().equals(own)) {
                add(counts, confidences, new EdgeKey(own, superClass.getName(), "extends"), Confidence.STATIC);
            }
        }
        if (wanted.contains("implements")) {
            for (ClassNode iface : classNode.getInterfaces()) {
                if (workspace.containsKey(iface.getName()) && !iface.getName().equals(own)) {
                    add(counts, confidences, new EdgeKey(own, iface.getName(), "implements"), Confidence.STATIC);
                }
            }
        }
        if (wanted.contains("has")) {
            for (FieldNode field : classNode.getFields()) {
                if (!field.isSynthetic()) {
                    for (String type : referencedTypes(field.getType(), workspace)) {
                        if (!type.equals(own)) {
                            add(counts, confidences, new EdgeKey(own, type, "has"), Confidence.STATIC);
                        }
                    }
                }
            }
            for (PropertyNode property : classNode.getProperties()) {
                for (String type : referencedTypes(property.getType(), workspace)) {
                    if (!type.equals(own)) {
                        add(counts, confidences, new EdgeKey(own, type, "has"), Confidence.STATIC);
                    }
                }
            }
        }
        if (wanted.contains("uses")) {
            for (MethodNode method : classNode.getMethods()) {
                if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers()) || method.getLineNumber() < 1) {
                    continue;
                }
                List<ClassNode> types = new ArrayList<>();
                types.add(method.getReturnType());
                for (Parameter parameter : method.getParameters()) {
                    types.add(parameter.getType());
                }
                for (ClassNode type : types) {
                    for (String name : referencedTypes(type, workspace)) {
                        if (!name.equals(own)) {
                            add(counts, confidences, new EdgeKey(own, name, "uses"), Confidence.STATIC);
                        }
                    }
                }
            }
        }
    }

    /** The workspace classes a type mentions: itself, its component type, its generic arguments. */
    static List<String> referencedTypes(ClassNode type, Map<String, ClassNode> workspace) {
        List<String> found = new ArrayList<>();
        collect(type, workspace, found, 0);
        return found;
    }

    private static void collect(ClassNode type, Map<String, ClassNode> workspace, List<String> found, int depth) {
        if (type == null || depth > 3) {
            return;
        }
        if (type.isArray()) {
            collect(type.getComponentType(), workspace, found, depth + 1);
            return;
        }
        if (workspace.containsKey(type.getName()) && !found.contains(type.getName())) {
            found.add(type.getName());
        }
        GenericsType[] generics = type.getGenericsTypes();
        if (generics != null) {
            for (GenericsType generic : generics) {
                if (!generic.isPlaceholder() && !generic.isWildcard()) {
                    collect(generic.getType(), workspace, found, depth + 1);
                }
            }
        }
    }

    private static void add(Map<EdgeKey, int[]> counts, Map<EdgeKey, Confidence> confidences, EdgeKey key,
            Confidence confidence) {
        counts.computeIfAbsent(key, k -> new int[1])[0]++;
        confidences.merge(key, confidence, (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
    }

    /** The class a call-graph symbol belongs to (its container for members, its own name for classes). */
    static String ownerOf(Symbol symbol) {
        if (symbol == null) {
            return null;
        }
        if (symbol.container() != null && !symbol.container().isBlank()
                && symbol.kind() != org.groovyscope.engine.model.SymbolKind.CLASS) {
            return symbol.container();
        }
        return symbol.name();
    }

    private static Set<String> reachable(Map<String, ClassNode> workspace, Set<EdgeKey> edges,
            Collection<ClassNode> seeds, int depth) {
        if (seeds == null || seeds.isEmpty()) {
            return new LinkedHashSet<>(workspace.keySet());
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (EdgeKey key : edges) {
            adjacency.computeIfAbsent(key.from(), k -> new LinkedHashSet<>()).add(key.to());
            adjacency.computeIfAbsent(key.to(), k -> new LinkedHashSet<>()).add(key.from());
        }
        Set<String> seen = new LinkedHashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();
        for (ClassNode seed : seeds) {
            if (workspace.containsKey(seed.getName()) && seen.add(seed.getName())) {
                queue.add(new String[] {seed.getName(), "0"});
            }
        }
        while (!queue.isEmpty()) {
            String[] current = queue.poll();
            int hops = Integer.parseInt(current[1]);
            if (hops >= depth) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current[0], Set.of())) {
                if (seen.add(next)) {
                    queue.add(new String[] {next, String.valueOf(hops + 1)});
                }
            }
        }
        return seen;
    }

    /** The nodes to show: the reachable ones, the best connected first when the cap bites. */
    private static Set<String> chooseNodes(Map<String, ClassNode> workspace, Set<EdgeKey> edges,
            Collection<ClassNode> seeds, int depth, int maxNodes) {
        Set<String> reachable = reachable(workspace, edges, seeds, depth);
        if (reachable.size() <= maxNodes) {
            return reachable;
        }
        Map<String, Integer> degree = new LinkedHashMap<>();
        for (EdgeKey key : edges) {
            degree.merge(key.from(), 1, Integer::sum);
            degree.merge(key.to(), 1, Integer::sum);
        }
        List<String> ordered = new ArrayList<>(reachable);
        Set<String> seedNames = new LinkedHashSet<>();
        if (seeds != null) {
            for (ClassNode seed : seeds) {
                seedNames.add(seed.getName());
            }
        }
        ordered.sort((a, b) -> {
            boolean seedA = seedNames.contains(a);
            boolean seedB = seedNames.contains(b);
            if (seedA != seedB) {
                return seedA ? -1 : 1;
            }
            int byDegree = Integer.compare(degree.getOrDefault(b, 0), degree.getOrDefault(a, 0));
            return byDegree != 0 ? byDegree : a.compareTo(b);
        });
        return new LinkedHashSet<>(ordered.subList(0, maxNodes));
    }

    static Node node(ClassNode classNode, AstIndex index) {
        Range range = index.declarationEntry(classNode).map(NodeEntry::range).orElse(AstRanges.rangeOf(classNode));
        String uri = index.declarationEntry(classNode).map(NodeEntry::uri).orElse(null);
        int members = 0;
        for (MethodNode method : classNode.getMethods()) {
            if (!method.isSynthetic() && method.getLineNumber() > 0) {
                members++;
            }
        }
        members += classNode.getProperties().size();
        for (FieldNode field : classNode.getFields()) {
            if (!field.isSynthetic() && classNode.getProperty(field.getName()) == null) {
                members++;
            }
        }
        String name = classNode.getNameWithoutPackage().replace('$', '.');
        boolean test = (uri != null && uri.contains("/src/test/")) || name.endsWith("Test") || name.endsWith("Spec")
                || name.endsWith("Tests");
        String kind = classNode.isScript() ? "script" : TypeNames.keywordOf(classNode);
        return new Node(classNode.getName(), name, kind, classNode.getPackageName(), uri,
                range == null ? 0 : range.start().line(), range == null ? 0 : range.end().line(), members, test, null);
    }

    private static String simple(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    private static String packageOf(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(0, dot) : null;
    }
}
