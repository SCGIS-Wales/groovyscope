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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.semantics.Resolution;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.Symbols;

/**
 * Every call in the workspace as an edge from the containing method (or script, or class initialiser)
 * to each candidate target, with the resolver's confidence. Built once per snapshot.
 */
public final class CallGraph {

    private final Map<ASTNode, List<CallSite>> incoming = new IdentityHashMap<>();
    private final Map<ASTNode, List<CallSite>> outgoing = new IdentityHashMap<>();
    private final List<CallSite> edges = new ArrayList<>();
    private final Symbols symbols;

    private CallGraph(Symbols symbols) {
        this.symbols = symbols;
    }

    /** Walks every call-like entry in the index and resolves it. */
    public static CallGraph build(AstIndex index, Resolver resolver, Symbols symbols) {
        CallGraph graph = new CallGraph(symbols);
        for (NodeEntry entry : index.allEntries()) {
            if (!isCall(entry)) {
                continue;
            }
            Optional<Resolution> resolution = resolver.resolve(entry);
            if (resolution.isEmpty()) {
                continue;
            }
            ASTNode caller = callerOf(entry);
            Symbol callerSymbol = symbols.of(caller);
            Location site = new Location(entry.uri(), entry.range());
            for (ASTNode target : resolution.get().candidates()) {
                if (!(target instanceof MethodNode)) {
                    continue;
                }
                CallSite edge = new CallSite(callerSymbol, symbols.of(target), site, resolution.get().confidence());
                graph.edges.add(edge);
                graph.incoming.computeIfAbsent(target, k -> new ArrayList<>()).add(edge);
                graph.outgoing.computeIfAbsent(caller, k -> new ArrayList<>()).add(edge);
            }
        }
        return graph;
    }

    static boolean isCall(NodeEntry entry) {
        return switch (entry.role()) {
            case METHOD_NAME, STATIC_CALL, CONSTRUCTOR_CALL -> true;
            default -> false;
        };
    }

    /**
     * The method containing a call. Field initialisers have no method, and a script's statements sit in a
     * synthetic, positionless {@code run()}; both are attributed to the enclosing class so clients can show them.
     */
    static ASTNode callerOf(NodeEntry entry) {
        MethodNode method = entry.enclosingMethod();
        if (method != null && method.getLineNumber() > 0) {
            return method;
        }
        ClassNode enclosing = entry.enclosingClass();
        return enclosing != null ? enclosing : entry.node();
    }

    /** Calls that target {@code declaration}. */
    public List<CallSite> incoming(ASTNode declaration) {
        return Collections.unmodifiableList(incoming.getOrDefault(declaration, List.of()));
    }

    /** Calls made from within {@code container} (a method, constructor, or script class). */
    public List<CallSite> outgoing(ASTNode container) {
        return Collections.unmodifiableList(outgoing.getOrDefault(container, List.of()));
    }

    public List<CallSite> edges() {
        return Collections.unmodifiableList(edges);
    }

    public int edgeCount() {
        return edges.size();
    }

    public long dynamicEdgeCount() {
        return edges.stream().filter(e -> e.confidence() == Confidence.DYNAMIC_UNKNOWN).count();
    }

    public Symbols symbols() {
        return symbols;
    }
}
