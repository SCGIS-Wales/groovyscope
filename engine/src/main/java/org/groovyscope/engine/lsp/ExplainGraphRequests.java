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

import static org.groovyscope.engine.lsp.RequestParams.invalid;
import static org.groovyscope.engine.lsp.RequestParams.number;
import static org.groovyscope.engine.lsp.RequestParams.outside;
import static org.groovyscope.engine.lsp.RequestParams.positionOf;
import static org.groovyscope.engine.lsp.RequestParams.requireHeadroom;
import static org.groovyscope.engine.lsp.RequestParams.stringList;
import static org.groovyscope.engine.lsp.RequestParams.targets;
import static org.groovyscope.engine.lsp.RequestParams.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.explain.CodeFacts;
import org.groovyscope.engine.explain.TestSeams;
import org.groovyscope.engine.graph.RelationshipGraph;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.ImpactReport;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.practices.Explanations;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.services.Snapshot;

/**
 * Explanations and graphs: what a code or a declaration means ({@code groovyscope/explain}, {@code explainCode}),
 * what a change reaches ({@code impact}, {@code callGraph}), the relationship graph ({@code graph}) and diagram
 * layout ({@code layout}).
 */
final class ExplainGraphRequests {

    private final RequestExecutors requests;
    private final Function<Map<String, Object>, LanguageServices> services;

    ExplainGraphRequests(RequestExecutors requests, Function<Map<String, Object>, LanguageServices> services) {
        this.requests = requests;
        this.services = services;
    }

    /** What a diagnostic code means and how to fix it, or {@code null}. Params: {@code code}. */
    CompletableFuture<Map<String, Object>> explain(Map<String, Object> params) {
        return CompletableFuture.completedFuture(Explanations.explain(
                params == null || params.get("code") == null ? null : params.get("code").toString()).orElse(null));
    }

    /**
     * Box positions and edge routes for a diagram, from the layered layout of {@link
     * org.groovyscope.engine.graph.DiagramLayout}. Params: {@code boxes} ({@code id}, {@code width}, {@code height}),
     * {@code links} ({@code id}, {@code source}, {@code target}, {@code hierarchy}), {@code options}. Needs no
     * workspace: it only lays out what it is given.
     */
    CompletableFuture<Map<String, Object>> layout(Map<String, Object> params) {
        return requests.heavy(cancel -> LayoutJson.layout(params == null ? Map.of() : params));
    }

    /**
     * Impact of changing a declaration. Params: {@code symbol} (qualified name) or {@code uri} + {@code line}
     * + {@code character} (zero-based), {@code change} (signature|rename|delete|behaviour), {@code root}.
     */
    CompletableFuture<List<Map<String, Object>>> impact(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            ImpactReport.Change change;
            try {
                change = ImpactReport.Change.parse(params.get("change") == null ? null : params.get("change").toString());
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage());
            }
            List<ImpactReport> reports;
            if (params.get("symbol") != null && !params.get("symbol").toString().isBlank()) {
                reports = current.impactOf(params.get("symbol").toString(), change);
            } else if (params.get("uri") != null) {
                reports = outside(current, params.get("uri")) ? List.of()
                        : current.impact(params.get("uri").toString(), positionOf(params), change)
                                .map(List::of).orElse(List.of());
            } else {
                throw invalid("impact needs a symbol or a uri with line and character");
            }
            List<Map<String, Object>> converted = new ArrayList<>();
            for (ImpactReport report : reports) {
                converted.add(ReportJson.impact(report));
            }
            return converted;
        });
    }

    /**
     * Call graph around a declaration. Params: {@code symbol} or {@code uri} + {@code line} + {@code character},
     * {@code direction} (in|out), {@code depth} (default 2), {@code root}.
     */
    CompletableFuture<Map<String, Object>> callGraph(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            boolean outgoing = "out".equalsIgnoreCase(String.valueOf(params.getOrDefault("direction", "in")));
            int depth = params.get("depth") instanceof Number number ? Math.max(1, Math.min(6, number.intValue())) : 2;
            List<Symbol> roots = new ArrayList<>();
            if (params.get("symbol") != null && !params.get("symbol").toString().isBlank()) {
                for (ASTNode node : current.findDeclarations(current.snapshot(), params.get("symbol").toString())) {
                    roots.add(current.snapshot().symbols().of(node));
                }
            } else if (params.get("uri") != null) {
                if (!outside(current, params.get("uri"))) {
                    current.callHierarchyItem(params.get("uri").toString(), positionOf(params)).ifPresent(roots::add);
                }
            } else {
                throw invalid("callGraph needs a symbol or a uri with line and character");
            }
            return ReportJson.callGraph(current, roots, outgoing, depth);
        });
    }

    /**
     * What a class, method or file does, as facts. Params: {@code symbol} (a qualified or hash-separated name) or
     * {@code uri} (every class of the document), {@code root}.
     */
    CompletableFuture<Map<String, Object>> explainCode(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            Snapshot snapshot = current.snapshot();
            CallGraph graph = current.callGraph();
            String symbol = text(params, "symbol");
            String uri = text(params, "uri");
            List<Map<String, Object>> items = new ArrayList<>();
            if (symbol != null) {
                for (ASTNode declaration : current.findDeclarations(snapshot, symbol)) {
                    if (declaration instanceof ClassNode classNode) {
                        items.add(ExplainJson.classFacts(CodeFacts.ofClass(classNode, snapshot.index(), graph)));
                    } else if (declaration instanceof MethodNode method) {
                        Map<String, Object> facts = ExplainJson.methodFacts(
                                CodeFacts.ofMethod(method, snapshot.index(), graph));
                        facts.put("kind", "method");
                        facts.put("class", method.getDeclaringClass().getName());
                        facts.put("uri", snapshot.index().declarationEntry(method).map(NodeEntry::uri).orElse(null));
                        facts.put("testing", ExplainJson.seams(TestSeams.of(method, graph, current::declarationOf,
                                snapshot.result().modules(), current.workspace()::text)));
                        items.add(facts);
                    } else if (declaration instanceof PropertyNode property) {
                        Map<String, Object> facts = ExplainJson.fieldFacts(CodeFacts.fieldFacts(property.getField(), true));
                        facts.put("kind", "property");
                        facts.put("class", property.getDeclaringClass().getName());
                        items.add(facts);
                    } else if (declaration instanceof FieldNode field) {
                        Map<String, Object> facts = ExplainJson.fieldFacts(CodeFacts.fieldFacts(field, false));
                        facts.put("kind", "field");
                        facts.put("class", field.getOwner() == null ? null : field.getOwner().getName());
                        items.add(facts);
                    }
                }
            } else if (uri != null) {
                ModuleNode module = snapshot.result().modules().get(uri);
                if (module != null && current.workspace().contains(uri)) {
                    for (ClassNode classNode : module.getClasses()) {
                        if (classNode.getLineNumber() > 0 && !classNode.getName().contains("$_")) {
                            items.add(ExplainJson.classFacts(CodeFacts.ofClass(classNode, snapshot.index(), graph)));
                        }
                    }
                }
            } else {
                throw invalid("explainCode needs a symbol or a uri");
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("found", !items.isEmpty());
            response.put("items", items);
            return response;
        });
    }

    /**
     * The relationship graph. Params: {@code symbols} (seed classes) or {@code uris} (seed documents), {@code kinds}
     * (extends, implements, has, uses, calls), {@code depth} (hops from the seeds, default 1), {@code maxNodes}
     * (default 60), {@code level} ({@code class} or {@code method}), {@code root}.
     */
    CompletableFuture<Map<String, Object>> graph(Map<String, Object> params) {
        return requests.heavy(cancel -> {
            LanguageServices current = services.apply(params);
            requireHeadroom();
            Snapshot snapshot = current.snapshot();
            List<ClassNode> seeds = new ArrayList<>();
            for (String symbol : stringList(params.get("symbols"))) {
                for (ASTNode declaration : current.findDeclarations(snapshot, symbol)) {
                    if (declaration instanceof ClassNode classNode) {
                        seeds.add(classNode);
                    } else if (declaration instanceof MethodNode method) {
                        seeds.add(method.getDeclaringClass());
                    }
                }
            }
            // A folder seeds every class of the documents under it, as in check, fix and find_duplicates.
            for (String uri : targets(current, params)) {
                ModuleNode module = snapshot.result().modules().get(uri);
                if (module != null) {
                    for (ClassNode classNode : module.getClasses()) {
                        if (classNode.getLineNumber() > 0 && !classNode.getName().contains("$_")) {
                            seeds.add(classNode);
                        }
                    }
                }
            }
            boolean scoped = !stringList(params.get("symbols")).isEmpty() || !stringList(params.get("uris")).isEmpty();
            if (scoped && seeds.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("nodes", List.of());
                empty.put("edges", List.of());
                empty.put("truncated", false);
                empty.put("totalClasses", 0);
                empty.put("note", "no workspace class matches the given symbols or paths (a folder counts the Groovy"
                        + " files under it); check the spelling, or pass symbols as Class or package.Class");
                return empty;
            }
            Integer depth = number(params, "depth");
            Integer maxNodes = number(params, "maxNodes");
            int cap = maxNodes == null ? RelationshipGraph.DEFAULT_MAX_NODES : Math.max(1, maxNodes);
            String level = text(params, "level");
            if ("method".equals(level)) {
                return ExplainJson.graph(RelationshipGraph.methods(snapshot.index(), current.callGraph(), seeds, cap));
            }
            List<String> kinds = stringList(params.get("kinds"));
            for (String kind : kinds) {
                if (!RelationshipGraph.KINDS.contains(kind)) {
                    throw invalid("unknown edge kind " + kind + "; expected one of "
                            + String.join(", ", RelationshipGraph.KINDS));
                }
            }
            return ExplainJson.graph(RelationshipGraph.build(snapshot.index(), current.callGraph(), seeds, kinds,
                    depth == null ? 1 : Math.max(0, depth), cap));
        });
    }
}
