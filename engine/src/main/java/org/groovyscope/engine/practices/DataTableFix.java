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
package org.groovyscope.engine.practices;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.log.EngineLog;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.practices.TestMethods.Test;
import org.groovyscope.engine.semantics.Members;

/**
 * The fix of GS044 for Spock: feature methods of one shape that differ only in their literals become the first of
 * them with a {@code where:} table, each varying literal a column named after where it is used (the parameter it is
 * passed to, the property or variable it is given to, {@code expected} for what a result is compared with, else
 * {@code a}, {@code b} and so on), one row per feature with repeated rows dropped; the other features are deleted.
 * It changes which tests exist, so it is applied only when GS044 is named. No fix is offered when the rewrite could
 * read wrongly: a data driven or annotated feature, a literal over several lines, a feature sharing its lines with
 * other code, or one with a comment directly above it.
 */
final class DataTableFix {

    /** Groovy's reserved words, never a column name. */
    static final Set<String> RESERVED = Set.of("as", "assert", "break", "case", "catch", "class", "const", "continue",
            "def", "default", "do", "else", "enum", "extends", "false", "finally", "for", "goto", "if", "implements",
            "import", "in", "instanceof", "interface", "new", "null", "package", "return", "super", "switch", "this",
            "throw", "throws", "trait", "true", "try", "var", "void", "while", "boolean", "byte", "char", "short",
            "int", "long", "float", "double", "record", "yield", "sealed", "permits", "it", "where", "expect", "given",
            "when", "then", "setup", "cleanup", "and", "_");

    private DataTableFix() {
    }

    /** The edits merging {@code group} into its first feature, or none when the rewrite is not safe to offer. */
    static List<TextEdit> edits(PracticeContext context, TestMethods methods, ClassNode owner, List<Test> group) {
        try {
            return build(context.lines(), methods, owner, group);
        } catch (RuntimeException e) {
            EngineLog.debug("no where: table for %s: %s", group.get(0).method().getName(), e);
            return List.of();
        }
    }

    private static List<TextEdit> build(TextLines lines, TestMethods methods, ClassNode owner, List<Test> group) {
        Test first = group.get(0);
        int count = first.literals().size();
        for (Test test : group) {
            if (!test.spock() || test.dataDriven() || !test.method().getAnnotations().isEmpty()
                    || test.literals().size() != count || !ownLines(lines, test.method())) {
                return List.of();
            }
        }
        List<List<String>> texts = new ArrayList<>();
        for (Test test : group) {
            List<String> row = new ArrayList<>();
            for (ConstantExpression literal : test.literals()) {
                String text = methods.text(literal);
                if (text == null) {
                    return List.of();
                }
                row.add(text);
            }
            texts.add(row);
        }
        List<Integer> varying = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            for (List<String> row : texts) {
                if (!row.get(i).equals(texts.get(0).get(i))) {
                    if (row.get(i).contains("\n") || texts.get(0).get(i).contains("\n")) {
                        return List.of();
                    }
                    varying.add(i);
                    break;
                }
            }
        }
        List<TextEdit> edits = new ArrayList<>();
        if (!varying.isEmpty()) {
            edits.add(new TextEdit(AstRanges.rangeOf(first.method()), merged(lines, methods, owner, first, varying, texts)));
        }
        for (Test test : group.subList(1, group.size())) {
            Range range = AstRanges.rangeOf(test.method());
            int start = range.start().line();
            if (start > 0 && lines.line(start - 1).isBlank()) {
                start--;
            } else if (start > 0 && !lines.line(start - 1).strip().endsWith("}")
                    && !lines.line(start - 1).strip().endsWith("{")) {
                return List.of(); // a comment or an annotation above it would be left behind
            }
            edits.add(new TextEdit(new Range(new Position(start, 0), new Position(range.end().line() + 1, 0)), ""));
        }
        return edits;
    }

    /** Whether a method stands on lines of its own, so deleting its lines deletes nothing else. */
    private static boolean ownLines(TextLines lines, MethodNode method) {
        Range range = AstRanges.rangeOf(method);
        if (range == null || range.end().line() + 1 >= lines.lineCount()) {
            return false;
        }
        String before = lines.line(range.start().line());
        String after = lines.line(range.end().line());
        return before.substring(0, Math.min(range.start().column(), before.length())).isBlank()
                && (range.end().column() >= after.length() || after.substring(range.end().column()).isBlank());
    }

    /** The first feature with the varying literals replaced by columns and a {@code where:} table below. */
    private static String merged(TextLines lines, TestMethods methods, ClassNode owner, Test first,
            List<Integer> varying, List<List<String>> texts) {
        Range range = AstRanges.rangeOf(first.method());
        int base = lines.offsetOf(range.start());
        String text = lines.slice(range);
        List<String> columns = columns(owner, first, varying);
        StringBuilder body = new StringBuilder(text);
        for (int k = varying.size() - 1; k >= 0; k--) {
            int[] at = methods.span(first.literals().get(varying.get(k)));
            body.replace(at[0] - base, at[1] - base, columns.get(k));
        }
        int close = body.lastIndexOf("}");
        String head = body.substring(0, close).stripTrailing();
        String outer = indentOf(lines.line(range.start().line()));
        List<?> statements = ((BlockStatement) first.method().getCode()).getStatements();
        int firstLine = ((org.codehaus.groovy.ast.stmt.Statement) statements.get(0)).getLineNumber() - 1;
        String inner = indentOf(lines.line(firstLine));
        inner = inner.length() > outer.length() ? inner : outer + "    ";
        Set<List<String>> rows = new LinkedHashSet<>();
        for (List<String> row : texts) {
            List<String> cells = new ArrayList<>();
            for (int index : varying) {
                cells.add(row.get(index));
            }
            rows.add(cells);
        }
        StringBuilder out = new StringBuilder(head).append("\n\n").append(inner).append("where:\n");
        if (columns.size() == 1) {
            List<String> values = new ArrayList<>();
            for (List<String> row : rows) {
                values.add(row.get(0));
            }
            out.append(inner).append(columns.get(0)).append(" << [").append(String.join(", ", values)).append("]\n");
        } else {
            int[] widths = new int[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                widths[c] = columns.get(c).length();
                for (List<String> row : rows) {
                    widths[c] = Math.max(widths[c], row.get(c).length());
                }
            }
            out.append(row(inner, columns, widths));
            for (List<String> row : rows) {
                out.append(row(inner, row, widths));
            }
        }
        return out.append(outer).append("}").toString();
    }

    private static String row(String indent, List<String> cells, int[] widths) {
        StringBuilder line = new StringBuilder(indent);
        for (int c = 0; c < cells.size(); c++) {
            line.append(c == 0 ? "" : " | ").append(c == cells.size() - 1 ? cells.get(c)
                    : String.format("%-" + widths[c] + "s", cells.get(c)));
        }
        return line.toString().stripTrailing() + "\n";
    }

    private static String indentOf(String line) {
        int at = 0;
        while (at < line.length() && Character.isWhitespace(line.charAt(at))) {
            at++;
        }
        return line.substring(0, at);
    }

    /** A column name per varying literal: where it is used, unique, never a name the feature already uses. */
    private static List<String> columns(ClassNode owner, Test first, List<Integer> varying) {
        Naming naming = new Naming(owner);
        first.method().getCode().visit(naming);
        Set<String> taken = new HashSet<>(naming.used);
        for (FieldNode field : owner.getFields()) {
            taken.add(field.getName());
        }
        List<String> columns = new ArrayList<>();
        char letter = 'a';
        for (int index : varying) {
            String wanted = naming.names.get(first.literals().get(index));
            String name = null;
            if (wanted != null && wanted.matches("[A-Za-z][A-Za-z0-9_]*")) {
                String base = Character.toLowerCase(wanted.charAt(0)) + wanted.substring(1);
                name = base;
                for (int n = 2; RESERVED.contains(name) || taken.contains(name); n++) {
                    name = base + n;
                }
            }
            while (name == null || RESERVED.contains(name) || taken.contains(name)) {
                name = letter <= 'z' ? String.valueOf(letter++) : "column" + columns.size();
            }
            taken.add(name);
            columns.add(name);
        }
        return columns;
    }

    /** Where each literal is used, and every name the feature already uses. */
    private static final class Naming extends CodeVisitorSupport {

        final Map<ConstantExpression, String> names = new IdentityHashMap<>();
        final Set<String> used = new HashSet<>();
        private final ClassNode owner;

        Naming(ClassNode owner) {
            this.owner = owner;
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            used.add(expression.getName());
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            name(TestBloatChecks.arguments(call.getArguments()), parameters(call));
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            List<Expression> arguments = TestBloatChecks.arguments(call.getArguments());
            List<String> parameters = new ArrayList<>();
            List<ConstructorNode> matching = call.getType().getDeclaredConstructors().stream()
                    .filter(constructor -> constructor.getParameters().length == arguments.size()).toList();
            if (matching.size() == 1) {
                for (Parameter parameter : matching.get(0).getParameters()) {
                    parameters.add(parameter.getName());
                }
            }
            name(arguments, parameters);
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression expression) {
            if (expression.getKeyExpression() instanceof ConstantExpression key && key.getValue() instanceof String name
                    && expression.getValueExpression() instanceof ConstantExpression value) {
                names.putIfAbsent(value, name);
            }
            super.visitMapEntryExpression(expression);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            Expression left = expression.getLeftExpression();
            Expression right = expression.getRightExpression();
            int type = expression.getOperation().getType();
            if (type == Types.COMPARE_EQUAL || type == Types.COMPARE_NOT_EQUAL) {
                if (right instanceof ConstantExpression literal && !(left instanceof ConstantExpression)) {
                    names.putIfAbsent(literal, "expected");
                } else if (left instanceof ConstantExpression literal && !(right instanceof ConstantExpression)) {
                    names.putIfAbsent(literal, "expected");
                }
            } else if (type == Types.ASSIGN && right instanceof ConstantExpression literal) {
                String target = left instanceof VariableExpression variable ? variable.getName()
                        : left instanceof PropertyExpression property ? property.getPropertyAsString() : null;
                if (target != null) {
                    names.putIfAbsent(literal, target);
                }
            }
            super.visitBinaryExpression(expression);
        }

        private void name(List<Expression> arguments, List<String> parameters) {
            for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
                if (arguments.get(i) instanceof ConstantExpression literal) {
                    names.putIfAbsent(literal, parameters.get(i));
                }
            }
        }

        /** The parameter names of the one method a call can reach, or none. */
        private List<String> parameters(MethodCallExpression call) {
            String name = call.getMethodAsString();
            ClassNode target = call.isImplicitThis() ? owner
                    : call.getObjectExpression() instanceof ClassExpression type ? type.getType()
                    : PracticeFinder.typeOf(call.getObjectExpression());
            List<String> names = new ArrayList<>();
            if (name == null || target == null || ClassHelper.isObjectType(target)) {
                return names;
            }
            List<MethodNode> reached = Members.accepting(Members.methodsNamed(target, name),
                    Members.argumentCount(call.getArguments()));
            if (reached.size() == 1) {
                for (Parameter parameter : reached.get(0).getParameters()) {
                    names.add(parameter.getName());
                }
            }
            return names;
        }
    }
}
