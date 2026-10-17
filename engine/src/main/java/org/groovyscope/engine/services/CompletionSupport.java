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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Completion;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.SymbolKind;
import org.groovyscope.engine.semantics.Members;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.Symbols;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * Completion proposals. After a dot: members of the receiver's known type. Otherwise: locals and
 * parameters in scope, members of the enclosing class, workspace classes and keywords.
 */
final class CompletionSupport {

    static final int LIMIT = 200;
    private static final Pattern PREFIX = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)$");
    private static final List<String> KEYWORDS = List.of("abstract", "as", "assert", "boolean", "break", "byte",
            "case", "catch", "char", "class", "const", "continue", "def", "default", "do", "double", "else", "enum",
            "extends", "false", "final", "finally", "float", "for", "goto", "if", "implements", "import", "in",
            "instanceof", "int", "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "record", "return", "sealed", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "trait", "transient", "true", "try", "val", "var", "void",
            "volatile", "while", "yield");

    private CompletionSupport() {
    }

    static List<Completion> complete(Snapshot snapshot, String uri, Position position) {
        AstIndex index = snapshot.index();
        TextLines lines = index.lines(uri);
        String line = lines.line(position.line());
        String before = line.substring(0, Math.min(position.column(), line.length()));
        Matcher matcher = PREFIX.matcher(before);
        String prefix = matcher.find() ? matcher.group(1) : "";
        String head = before.substring(0, before.length() - prefix.length()).stripTrailing();
        Map<String, Completion> proposals = new LinkedHashMap<>();
        if (head.endsWith(".")) {
            Position receiverEnd = new Position(position.line(), head.length() - 1);
            ClassNode type = receiverType(snapshot, uri, receiverEnd);
            if (type != null) {
                members(type, prefix, proposals);
            }
            return new ArrayList<>(proposals.values()).subList(0, Math.min(LIMIT, proposals.size()));
        }
        Optional<NodeEntry> context = index.nodeAt(uri, position);
        ClassNode enclosingClass = context.map(NodeEntry::enclosingClass).orElse(null);
        MethodNode enclosingMethod = context.map(NodeEntry::enclosingMethod).orElse(null);
        locals(index, uri, position, enclosingMethod, prefix, proposals);
        if (enclosingClass != null) {
            members(enclosingClass, prefix, proposals);
        }
        for (ClassNode classNode : index.classes()) {
            if (!classNode.isScript()) {
                add(proposals, Symbols.nameOf(classNode), TypeNames.kindOf(classNode), TypeNames.signature(classNode), prefix);
            }
        }
        for (String keyword : KEYWORDS) {
            add(proposals, keyword, null, "keyword", prefix);
        }
        List<Completion> ordered = new ArrayList<>(proposals.values());
        return ordered.subList(0, Math.min(LIMIT, ordered.size()));
    }

    private static ClassNode receiverType(Snapshot snapshot, String uri, Position receiverEnd) {
        AstIndex index = snapshot.index();
        Resolver resolver = snapshot.resolver();
        Position inside = new Position(receiverEnd.line(), Math.max(0, receiverEnd.column() - 1));
        Optional<NodeEntry> entry = index.nodeAt(uri, inside);
        if (entry.isEmpty()) {
            return null;
        }
        NodeEntry found = entry.get();
        ASTNode node = found.node();
        if (node instanceof ClassNode classNode) {
            return resolver.usable(classNode);
        }
        if (node instanceof Expression expression) {
            Resolver.Receiver receiver = resolver.receiverOf(expression, false, found);
            if (receiver.type() != null) {
                return receiver.type();
            }
        }
        return resolver.resolve(found)
                .map(resolution -> resolver.usable(Resolver.typeOfMember(resolution.primary())))
                .orElse(null);
    }

    private static void locals(AstIndex index, String uri, Position position, MethodNode enclosingMethod, String prefix,
            Map<String, Completion> proposals) {
        for (NodeEntry entry : index.entries(uri)) {
            if (!entry.isDeclaration() || entry.range().start().isAfter(position)) {
                continue;
            }
            ASTNode node = entry.node();
            if (node instanceof VariableExpression variable && entry.enclosingMethod() == enclosingMethod) {
                add(proposals, variable.getName(), SymbolKind.VARIABLE, TypeNames.signature(variable), prefix);
            } else if (node instanceof Parameter parameter && entry.enclosingMethod() == enclosingMethod) {
                add(proposals, parameter.getName(), SymbolKind.PARAMETER, TypeNames.parameter(parameter), prefix);
            }
        }
    }

    private static void members(ClassNode type, String prefix, Map<String, Completion> proposals) {
        for (ASTNode member : Members.allProperties(type)) {
            if (member instanceof PropertyNode property) {
                add(proposals, property.getName(), SymbolKind.PROPERTY, TypeNames.signature(property), prefix);
            } else if (member instanceof FieldNode field) {
                add(proposals, field.getName(), SymbolKind.FIELD, TypeNames.signature(field), prefix);
            }
        }
        for (MethodNode method : Members.allMethods(type)) {
            if (method.getName().contains("$") || method.getName().startsWith("<")) {
                continue;
            }
            String label = method.getName() + "(" + TypeNames.parameters(method.getParameters()) + ")";
            String insert = method.getParameters().length == 0 ? method.getName() + "()" : method.getName() + "(";
            if (startsWith(method.getName(), prefix) && !proposals.containsKey(label)) {
                proposals.put(label, new Completion(label, SymbolKind.METHOD, TypeNames.signature(method), insert));
            }
        }
    }

    private static void add(Map<String, Completion> proposals, String name, SymbolKind kind, String detail, String prefix) {
        if (name == null || !startsWith(name, prefix) || proposals.containsKey(name)) {
            return;
        }
        proposals.put(name, new Completion(name, kind, detail, name));
    }

    private static boolean startsWith(String name, String prefix) {
        return prefix.isEmpty() || name.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
