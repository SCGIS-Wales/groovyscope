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
package org.groovyscope.engine.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.model.Position;

/**
 * Every positioned node of every module, queryable by document and position, plus the declarations and
 * classes of the workspace. Built once per compile; immutable afterwards.
 */
public final class AstIndex {

    private final Map<String, List<NodeEntry>> entriesByUri;
    private final Map<String, TextLines> linesByUri;
    private final Map<ASTNode, NodeEntry> declarationEntries;
    private final Map<String, ClassNode> classesByName;
    private final Map<String, List<ClassNode>> classesBySimpleName;
    private final List<NodeEntry> declarations;

    private AstIndex(Map<String, List<NodeEntry>> entriesByUri, Map<String, TextLines> linesByUri,
            Map<ASTNode, NodeEntry> declarationEntries, Map<String, ClassNode> classesByName,
            List<NodeEntry> declarations) {
        this.entriesByUri = entriesByUri;
        this.linesByUri = linesByUri;
        this.declarationEntries = declarationEntries;
        this.classesByName = classesByName;
        this.declarations = declarations;
        Map<String, List<ClassNode>> bySimpleName = new HashMap<>();
        for (ClassNode classNode : classesByName.values()) {
            bySimpleName.computeIfAbsent(simpleName(classNode.getName()), k -> new ArrayList<>()).add(classNode);
        }
        this.classesBySimpleName = bySimpleName;
    }

    static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /** Indexes the given modules; {@code sources} supplies the text used for name lookups. */
    public static AstIndex build(Map<String, ModuleNode> modules, Map<String, String> sources) {
        Map<String, List<NodeEntry>> entriesByUri = new TreeMap<>();
        Map<String, TextLines> linesByUri = new HashMap<>();
        Map<ASTNode, NodeEntry> declarationEntries = new IdentityHashMap<>();
        Map<String, ClassNode> classesByName = new HashMap<>();
        List<NodeEntry> declarations = new ArrayList<>();
        for (Map.Entry<String, ModuleNode> module : modules.entrySet()) {
            org.groovyscope.engine.concurrent.Cancellation.checkpoint();
            String uri = module.getKey();
            TextLines lines = new TextLines(sources.getOrDefault(uri, ""));
            linesByUri.put(uri, lines);
            IndexVisitor visitor = new IndexVisitor(uri, lines.fullRange());
            try {
                visitor.index(module.getValue());
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                // One tree the visitor cannot walk (a linkage error from a classpath type, a pathological depth)
                // costs that file's entries, not the whole index.
                org.groovyscope.engine.log.EngineLog.warn("%s", "not indexed: " + uri + ": " + e);
                continue;
            }
            List<NodeEntry> entries = visitor.entries();
            entries.sort((a, b) -> a.range().start().compareTo(b.range().start()));
            entriesByUri.put(uri, Collections.unmodifiableList(entries));
            for (NodeEntry entry : entries) {
                if (entry.isDeclaration()) {
                    declarationEntries.putIfAbsent(entry.node(), entry);
                    declarations.add(entry);
                }
            }
            for (ClassNode classNode : module.getValue().getClasses()) {
                if (!IndexVisitor.isGenerated(classNode)) {
                    classesByName.put(classNode.getName(), classNode);
                }
            }
        }
        return new AstIndex(entriesByUri, linesByUri, declarationEntries, classesByName,
                Collections.unmodifiableList(declarations));
    }

    public static AstIndex empty() {
        return new AstIndex(Map.of(), Map.of(), new IdentityHashMap<>(), Map.of(), List.of());
    }

    public Iterable<String> uris() {
        return entriesByUri.keySet();
    }

    public List<NodeEntry> entries(String uri) {
        return entriesByUri.getOrDefault(uri, List.of());
    }

    public List<NodeEntry> allEntries() {
        List<NodeEntry> all = new ArrayList<>();
        entriesByUri.values().forEach(all::addAll);
        return all;
    }

    public List<NodeEntry> declarations() {
        return declarations;
    }

    public TextLines lines(String uri) {
        return linesByUri.getOrDefault(uri, new TextLines(""));
    }

    /** The entry that declares {@code node}, when {@code node} is a workspace declaration. */
    public Optional<NodeEntry> declarationEntry(ASTNode node) {
        return Optional.ofNullable(declarationEntries.get(node));
    }

    /** A workspace class by fully qualified name. */
    public Optional<ClassNode> classNamed(String name) {
        return Optional.ofNullable(classesByName.get(name));
    }

    /** Workspace classes with that simple name (used when the compiler could not resolve a reference). */
    public List<ClassNode> classesWithSimpleName(String simpleName) {
        return classesBySimpleName.getOrDefault(simpleName, List.of());
    }

    public Iterable<ClassNode> classes() {
        return classesByName.values();
    }

    /** The innermost meaningful node at a position: names and usages win over the expressions around them. */
    public Optional<NodeEntry> nodeAt(String uri, Position position) {
        NodeEntry best = null;
        for (NodeEntry entry : entries(uri)) {
            if (!entry.range().contains(position)) {
                if (entry.range().start().isAfter(position)) {
                    break;
                }
                continue;
            }
            if (best == null || prefer(entry, best)) {
                best = entry;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean prefer(NodeEntry candidate, NodeEntry current) {
        long byExtent = Long.compare(candidate.range().extent(), current.range().extent());
        if (byExtent != 0) {
            return byExtent < 0;
        }
        return rank(candidate.role()) < rank(current.role());
    }

    private static int rank(NodeEntry.Role role) {
        return switch (role) {
            case METHOD_NAME, PROPERTY_NAME -> 0;
            case VARIABLE_USAGE, TYPE_USAGE -> 1;
            case DECLARATION -> 2;
            case CONSTRUCTOR_CALL, STATIC_CALL -> 3;
            case OTHER -> 4;
        };
    }
}
