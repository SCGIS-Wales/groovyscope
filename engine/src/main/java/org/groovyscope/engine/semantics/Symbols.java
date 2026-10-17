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
package org.groovyscope.engine.semantics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PackageNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.model.SymbolKind;

/** Builds {@link Symbol}s from declarations, with locations when they live in the workspace. */
public final class Symbols {

    private final AstIndex index;

    public Symbols(AstIndex index) {
        this.index = index;
    }

    /** A symbol for any declaration node; external ones get no location. */
    public Symbol of(ASTNode declaration) {
        Optional<NodeEntry> entry = index.declarationEntry(declaration);
        Location location = null;
        Range selection = null;
        if (entry.isPresent()) {
            NodeEntry found = entry.get();
            location = new Location(found.uri(), found.range());
            selection = AstRanges.nameRange(declaration, found.range(), index.lines(found.uri()));
        }
        return new Symbol(nameOf(declaration), kindOf(declaration), containerOf(declaration), location, selection,
                detailOf(declaration), List.of());
    }

    /** Document outline: classes with their members, in source order. */
    public List<Symbol> documentSymbols(String uri) {
        List<Symbol> roots = new ArrayList<>();
        for (NodeEntry entry : index.entries(uri)) {
            if (!entry.isDeclaration() || !(entry.node() instanceof ClassNode classNode)) {
                continue;
            }
            List<Symbol> children = new ArrayList<>();
            for (NodeEntry member : index.entries(uri)) {
                if (!member.isDeclaration() || member.node() == classNode || !entry.range().contains(member.range())) {
                    continue;
                }
                ASTNode node = member.node();
                boolean direct = (node instanceof MethodNode method && method.getDeclaringClass() == classNode)
                        || (node instanceof FieldNode field && field.getOwner() == classNode)
                        || (node instanceof PropertyNode property && property.getDeclaringClass() == classNode)
                        || (node instanceof ClassNode inner && inner.getOuterClass() == classNode);
                if (direct && !(node instanceof ClassNode)) {
                    children.add(of(node));
                }
            }
            Symbol base = of(classNode);
            roots.add(new Symbol(base.name(), base.kind(), base.container(), base.location(), base.selectionRange(),
                    base.detail(), children));
        }
        return roots;
    }

    /** Declarations whose name contains the query (case-insensitive); empty query matches all. */
    public List<Symbol> workspaceSymbols(String query, int limit) {
        String needle = query == null ? "" : query.toLowerCase();
        List<Symbol> matches = new ArrayList<>();
        for (NodeEntry entry : index.declarations()) {
            ASTNode node = entry.node();
            if (node instanceof Parameter || node instanceof VariableExpression || node instanceof ImportNode
                    || node instanceof PackageNode) {
                continue;
            }
            String name = nameOf(node);
            if (name != null && name.toLowerCase().contains(needle)) {
                matches.add(of(node));
                if (matches.size() >= limit) {
                    break;
                }
            }
        }
        return matches;
    }

    public static String nameOf(ASTNode node) {
        if (node instanceof ClassNode classNode) {
            if (classNode.isScript()) {
                return classNode.getNameWithoutPackage();
            }
            String name = classNode.getNameWithoutPackage();
            int dollar = name.lastIndexOf('$');
            return dollar >= 0 ? name.substring(dollar + 1) : name;
        }
        if (node instanceof ConstructorNode constructor) {
            return constructor.getDeclaringClass().getNameWithoutPackage();
        }
        if (node instanceof MethodNode method) {
            return method.getName();
        }
        if (node instanceof FieldNode field) {
            return field.getName();
        }
        if (node instanceof PropertyNode property) {
            return property.getName();
        }
        if (node instanceof Parameter parameter) {
            return parameter.getName();
        }
        if (node instanceof VariableExpression variable) {
            return variable.getName();
        }
        if (node instanceof ImportNode importNode) {
            return importNode.getAlias() != null ? importNode.getAlias() : importNode.getClassName();
        }
        if (node instanceof PackageNode packageNode) {
            return packageNode.getName();
        }
        return null;
    }

    public static SymbolKind kindOf(ASTNode node) {
        if (node instanceof ClassNode classNode) {
            return TypeNames.kindOf(classNode);
        }
        if (node instanceof ConstructorNode) {
            return SymbolKind.CONSTRUCTOR;
        }
        if (node instanceof MethodNode) {
            return SymbolKind.METHOD;
        }
        if (node instanceof FieldNode field) {
            return field.isEnum() ? SymbolKind.ENUM_CONSTANT : SymbolKind.FIELD;
        }
        if (node instanceof PropertyNode) {
            return SymbolKind.PROPERTY;
        }
        if (node instanceof Parameter) {
            return SymbolKind.PARAMETER;
        }
        if (node instanceof VariableExpression) {
            return SymbolKind.VARIABLE;
        }
        if (node instanceof ImportNode) {
            return SymbolKind.IMPORT;
        }
        return SymbolKind.PACKAGE;
    }

    public static String containerOf(ASTNode node) {
        if (node instanceof ClassNode classNode) {
            ClassNode outer = classNode.getOuterClass();
            return outer != null ? outer.getName() : classNode.getPackageName();
        }
        if (node instanceof MethodNode method) {
            return method.getDeclaringClass() == null ? null : method.getDeclaringClass().getName();
        }
        if (node instanceof FieldNode field) {
            return field.getOwner() == null ? null : field.getOwner().getName();
        }
        if (node instanceof PropertyNode property) {
            return property.getDeclaringClass() == null ? null : property.getDeclaringClass().getName();
        }
        return null;
    }

    public static String detailOf(ASTNode node) {
        if (node instanceof ClassNode classNode) {
            return TypeNames.signature(classNode);
        }
        if (node instanceof MethodNode method) {
            return TypeNames.signature(method);
        }
        if (node instanceof FieldNode field) {
            return TypeNames.signature(field);
        }
        if (node instanceof PropertyNode property) {
            return TypeNames.signature(property);
        }
        if (node instanceof Parameter parameter) {
            return TypeNames.parameter(parameter);
        }
        if (node instanceof VariableExpression variable) {
            return TypeNames.signature(variable);
        }
        if (node instanceof ImportNode importNode) {
            return "import " + (importNode.isStatic() ? "static " : "") + importNode.getText();
        }
        if (node instanceof PackageNode packageNode) {
            return "package " + packageNode.getName();
        }
        return null;
    }
}
