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
import java.util.List;
import java.util.Optional;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PackageNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.semantics.Resolution;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * LSP semantic tokens from the index: declarations and the usages that resolve to them, encoded as the
 * protocol's relative integer quintuples. Tokens never span lines.
 */
public final class SemanticTokensSupport {

    /** Token types, in legend order. */
    public static final List<String> TYPES = List.of("namespace", "class", "interface", "enum", "struct", "method",
            "property", "variable", "parameter", "enumMember", "type");
    /** Token modifiers, in legend order (bit positions). */
    public static final List<String> MODIFIERS = List.of("declaration", "static", "readonly");

    private SemanticTokensSupport() {
    }

    record Token(int line, int column, int length, int type, int modifiers) {
    }

    static List<Integer> encode(Snapshot snapshot, String uri) {
        List<Token> tokens = new ArrayList<>();
        for (NodeEntry entry : snapshot.index().entries(uri)) {
            Token token = tokenFor(snapshot, entry);
            if (token != null) {
                tokens.add(token);
            }
        }
        tokens.sort((a, b) -> a.line != b.line ? Integer.compare(a.line, b.line) : Integer.compare(a.column, b.column));
        List<Integer> data = new ArrayList<>(tokens.size() * 5);
        int previousLine = 0;
        int previousColumn = 0;
        for (Token token : tokens) {
            int deltaLine = token.line - previousLine;
            int deltaColumn = deltaLine == 0 ? token.column - previousColumn : token.column;
            if (deltaLine < 0 || deltaColumn < 0) {
                continue;
            }
            data.add(deltaLine);
            data.add(deltaColumn);
            data.add(token.length);
            data.add(token.type);
            data.add(token.modifiers);
            previousLine = token.line;
            previousColumn = token.column;
        }
        return data;
    }

    static Token tokenFor(Snapshot snapshot, NodeEntry entry) {
        Range range;
        ASTNode classified;
        int modifiers = 0;
        switch (entry.role()) {
            case DECLARATION -> {
                if (entry.node() instanceof PackageNode || entry.node() instanceof ImportNode) {
                    return null;
                }
                range = AstRanges.nameRange(entry.node(), entry.range(), snapshot.index().lines(entry.uri()));
                classified = entry.node();
                modifiers |= 1;
            }
            case TYPE_USAGE, VARIABLE_USAGE, METHOD_NAME, PROPERTY_NAME -> {
                range = entry.range();
                Optional<Resolution> resolution = snapshot.resolver().resolve(entry);
                classified = resolution.map(Resolution::primary).orElse(null);
                if (classified == null) {
                    classified = entry.node();
                }
            }
            default -> {
                return null;
            }
        }
        if (range == null || range.start().line() != range.end().line()) {
            return null;
        }
        int type = typeOf(classified, entry);
        if (type < 0) {
            return null;
        }
        if (isStatic(classified)) {
            modifiers |= 2;
        }
        if (isReadonly(classified)) {
            modifiers |= 4;
        }
        return new Token(range.start().line(), range.start().column(), range.end().column() - range.start().column(),
                type, modifiers);
    }

    static int typeOf(ASTNode node, NodeEntry entry) {
        if (node instanceof ClassNode classNode) {
            return switch (TypeNames.kindOf(classNode)) {
                case INTERFACE, TRAIT, ANNOTATION -> TYPES.indexOf("interface");
                case ENUM -> TYPES.indexOf("enum");
                case RECORD -> TYPES.indexOf("struct");
                case SCRIPT -> -1;
                default -> TYPES.indexOf("class");
            };
        }
        if (node instanceof MethodNode) {
            return TYPES.indexOf("method");
        }
        if (node instanceof PropertyNode) {
            return TYPES.indexOf("property");
        }
        if (node instanceof FieldNode field) {
            return field.isEnum() ? TYPES.indexOf("enumMember") : TYPES.indexOf("property");
        }
        if (node instanceof Parameter) {
            return TYPES.indexOf("parameter");
        }
        if (node instanceof VariableExpression) {
            return TYPES.indexOf("variable");
        }
        return switch (entry.role()) {
            case METHOD_NAME -> TYPES.indexOf("method");
            case PROPERTY_NAME -> TYPES.indexOf("property");
            case TYPE_USAGE -> TYPES.indexOf("type");
            case VARIABLE_USAGE -> TYPES.indexOf("variable");
            default -> -1;
        };
    }

    static boolean isStatic(ASTNode node) {
        return (node instanceof MethodNode method && method.isStatic())
                || (node instanceof FieldNode field && field.isStatic())
                || (node instanceof PropertyNode property && property.isStatic());
    }

    static boolean isReadonly(ASTNode node) {
        return (node instanceof FieldNode field && field.isFinal())
                || (node instanceof PropertyNode property && property.getField() != null && property.getField().isFinal());
    }
}
