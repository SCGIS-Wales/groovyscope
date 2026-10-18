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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.explain.CodeFacts;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.model.SymbolKind;

/**
 * Whether each workspace method only reads or also changes state, and so whether a call to it is a read or a write.
 * A method writes when it assigns or mutates a field or another object's property, mutates a parameter, writes a
 * file, sends a non-fetch HTTP request, changes a database, starts a process, exits or sets a system property; or
 * when it calls, statically or by inference, a method that writes. A constructor only initialises the new object:
 * setting its own fields, directly or through its class's own methods, is not a write. A call resolved only
 * dynamically is {@code unknown}: the method it reaches may not be the one guessed.
 *
 * <p>Methods are keyed {@code Class#name} (overloads share a key, as on the method diagram); constructors are
 * {@code Class#<init>}.
 */
public record Effects(Map<String, String> methods) {

    private static final Logger LOG = Logger.getLogger(Effects.class.getName());

    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String UNKNOWN = "unknown";

    /** A resolved call between two workspace methods; {@code sameClass} calls share the receiver's own state. */
    private record Link(String caller, String callee, boolean sameClass) {
    }

    /**
     * The effects of every workspace method, propagated up the call graph to a fixed point. Each method has two
     * flags: it changes its own object's fields, or it changes something outside (another object, a parameter, a
     * file, a database, a remote service, a process, the environment). A call within a class passes both on; a call
     * to another class turns the callee's own changes into outside changes for the caller.
     */
    public static Effects of(Map<String, ClassNode> workspace, CallGraph graph) {
        Set<String> constructors = new HashSet<>();
        Set<String> own = new HashSet<>();
        Set<String> outside = new HashSet<>();
        Set<String> known = new HashSet<>();
        Set<String> dynamic = new HashSet<>();
        for (ClassNode classNode : workspace.values()) {
            List<MethodNode> methods = new ArrayList<>(classNode.getMethods());
            methods.addAll(classNode.getDeclaredConstructors());
            for (MethodNode method : methods) {
                if (method.isSynthetic()) {
                    continue;
                }
                String key = key(classNode.getName(), method);
                known.add(key);
                if (method instanceof ConstructorNode) {
                    constructors.add(key);
                }
                try {
                    for (String change : CodeFacts.stateChanges(method)) {
                        (change.startsWith("field ") ? own : outside).add(key);
                    }
                } catch (RuntimeException e) {
                    // One body the visitor cannot read leaves that method unknown; the diagram is still drawn.
                    LOG.log(Level.FINE, "no effect for " + key, e);
                    dynamic.add(key);
                }
            }
        }
        Map<String, List<Link>> callers = new HashMap<>();
        for (CallSite site : graph.edges()) {
            String caller = key(site.caller());
            String callee = key(site.callee());
            if (caller == null || callee == null || !known.contains(caller)) {
                continue;
            }
            String calleeOwner = RelationshipGraph.ownerOf(site.callee());
            boolean sameClass = calleeOwner.equals(RelationshipGraph.ownerOf(site.caller()));
            if (site.confidence() == Confidence.DYNAMIC_UNKNOWN) {
                dynamic.add(caller);
            } else if (known.contains(callee)) {
                callers.computeIfAbsent(callee, k -> new ArrayList<>()).add(new Link(caller, callee, sameClass));
            } else if (workspace.containsKey(calleeOwner) && isSetter(site.callee())) {
                (sameClass ? own : outside).add(caller);
            } else if (!workspace.containsKey(calleeOwner) && externalWrite(site.callee())) {
                outside.add(caller);
            }
        }
        Deque<String> pending = new ArrayDeque<>(own);
        pending.addAll(outside);
        while (!pending.isEmpty()) {
            String callee = pending.poll();
            for (Link link : callers.getOrDefault(callee, List.of())) {
                boolean changed = false;
                // A constructor's own fields belong to the object it makes, never to its caller's.
                boolean ownWrite = own.contains(callee) && !constructors.contains(callee);
                if (link.sameClass()) {
                    changed |= ownWrite && own.add(link.caller());
                    changed |= outside.contains(callee) && outside.add(link.caller());
                } else if (outside.contains(callee) || ownWrite) {
                    changed = outside.add(link.caller());
                }
                if (changed) {
                    pending.add(link.caller());
                }
            }
        }
        Map<String, String> effects = new HashMap<>();
        for (String method : known) {
            boolean writes = outside.contains(method) || own.contains(method) && !constructors.contains(method);
            effects.put(method, writes ? WRITE : dynamic.contains(method) ? UNKNOWN : READ);
        }
        return new Effects(Map.copyOf(effects));
    }

    /** The effect of one method by key, {@code null} when it is not a workspace method. */
    public String method(String key) {
        return methods.get(key);
    }

    /**
     * The effect of one call: what the callee does, {@code unknown} when the call is resolved only dynamically. A
     * library callee writes when it changes a file, a database, a remote service, a process or the object it is
     * called on (a collection or builder).
     */
    public String call(CallSite site) {
        if (site.confidence() == Confidence.DYNAMIC_UNKNOWN) {
            return UNKNOWN;
        }
        String callee = key(site.callee());
        String known = callee == null ? null : methods.get(callee);
        if (known != null) {
            return known;
        }
        Symbol symbol = site.callee();
        return symbol != null && (isSetter(symbol) || externalWrite(symbol) || CodeFacts.mutates(symbol.name()))
                ? WRITE : READ;
    }

    /** Combines the effects of several calls between the same two boxes: any write wins, then any unknown. */
    public static String combine(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (WRITE.equals(a) || WRITE.equals(b)) {
            return WRITE;
        }
        return UNKNOWN.equals(a) || UNKNOWN.equals(b) ? UNKNOWN : READ;
    }

    static String key(Symbol symbol) {
        String owner = RelationshipGraph.ownerOf(symbol);
        if (owner == null || symbol.kind() == SymbolKind.CLASS) {
            return null;
        }
        return owner + "#" + (symbol.kind() == SymbolKind.CONSTRUCTOR ? "<init>" : symbol.name());
    }

    private static String key(String owner, MethodNode method) {
        return owner + "#" + (method instanceof ConstructorNode ? "<init>" : method.getName());
    }

    private static boolean externalWrite(Symbol callee) {
        String owner = RelationshipGraph.ownerOf(callee);
        String name = callee.kind() == SymbolKind.CONSTRUCTOR ? "<init>" : callee.name();
        return CodeFacts.writesExternally(CodeFacts.externalOf(owner, name, null), owner, name);
    }

    /** A generated property setter ({@code setTitle}), which has no body in the AST to inspect. */
    private static boolean isSetter(Symbol callee) {
        String name = callee.name();
        return callee.kind() != SymbolKind.CONSTRUCTOR && name != null && name.length() > 3 && name.startsWith("set")
                && Character.isUpperCase(name.charAt(3));
    }
}
