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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;

/** Converts Groovy's one-based, end-inclusive-ish node positions to LSP ranges and finds name tokens. */
public final class AstRanges {

    private AstRanges() {
    }

    /** The node's range, or {@code null} for synthetic nodes without a position. */
    public static Range rangeOf(ASTNode node) {
        if (node == null || node.getLineNumber() < 1 || node.getColumnNumber() < 1) {
            return null;
        }
        int endLine = node.getLastLineNumber() >= node.getLineNumber() ? node.getLastLineNumber() : node.getLineNumber();
        int endColumn = node.getLastColumnNumber() >= 1 ? node.getLastColumnNumber() : node.getColumnNumber() + 1;
        Position start = new Position(node.getLineNumber() - 1, node.getColumnNumber() - 1);
        Position end = new Position(endLine - 1, endColumn - 1);
        if (!start.isBefore(end)) {
            end = new Position(start.line(), start.column() + 1);
        }
        return new Range(start, end);
    }

    /**
     * The range of a declaration's name token. Groovy records only the extent of whole declarations, so
     * the name is located in the source text, inside the declaration's own range.
     */
    public static Range nameRange(ASTNode declaration, Range declarationRange, TextLines lines) {
        if (declarationRange == null) {
            return null;
        }
        if (declaration instanceof VariableExpression) {
            return declarationRange;
        }
        String name = nameOf(declaration);
        if (name == null || name.isEmpty()) {
            return declarationRange;
        }
        String text = lines.slice(declarationRange);
        int startOffset = lines.offsetOf(declarationRange.start());
        Pattern pattern = patternFor(declaration, name);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int group = matcher.groupCount() >= 1 ? 1 : 0;
            int begin = matcher.start(group);
            if (begin > 0 && text.charAt(begin - 1) == '.') {
                continue;
            }
            return new Range(lines.positionOf(startOffset + begin), lines.positionOf(startOffset + matcher.end(group)));
        }
        return declarationRange;
    }

    /** The last occurrence of {@code name} as a whole word inside {@code range}, or the range itself. */
    public static Range nameWithin(Range range, String name, TextLines lines) {
        if (range == null || name == null || name.isEmpty()) {
            return range;
        }
        String text = lines.slice(range);
        int startOffset = lines.offsetOf(range.start());
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text);
        Range found = null;
        while (matcher.find()) {
            found = new Range(lines.positionOf(startOffset + matcher.start()), lines.positionOf(startOffset + matcher.end()));
        }
        return found == null ? range : found;
    }

    static String nameOf(ASTNode declaration) {
        if (declaration instanceof ConstructorNode constructor) {
            return constructor.getDeclaringClass().getNameWithoutPackage();
        }
        if (declaration instanceof MethodNode method) {
            return method.getName();
        }
        if (declaration instanceof ClassNode classNode) {
            String name = classNode.getNameWithoutPackage();
            int dollar = name.lastIndexOf('$');
            return dollar >= 0 ? name.substring(dollar + 1) : name;
        }
        if (declaration instanceof FieldNode field) {
            return field.getName();
        }
        if (declaration instanceof PropertyNode property) {
            return property.getName();
        }
        if (declaration instanceof Parameter parameter) {
            return parameter.getName();
        }
        if (declaration instanceof ImportNode importNode) {
            return importNode.getAlias() != null ? importNode.getAlias() : importNode.getClassName();
        }
        return null;
    }

    private static Pattern patternFor(ASTNode declaration, String name) {
        String quoted = Pattern.quote(name);
        if (declaration instanceof ClassNode) {
            return Pattern.compile("(?:class|interface|trait|enum|record|@interface)\\s+(" + quoted + ")\\b|\\b(" + quoted + ")\\b");
        }
        if (declaration instanceof MethodNode) {
            return Pattern.compile("\\b(" + quoted + ")\\s*\\(");
        }
        if (declaration instanceof FieldNode || declaration instanceof PropertyNode || declaration instanceof Parameter) {
            return Pattern.compile("\\b(" + quoted + ")\\b(?!\\s*\\()");
        }
        return Pattern.compile("\\b(" + quoted + ")\\b");
    }
}
