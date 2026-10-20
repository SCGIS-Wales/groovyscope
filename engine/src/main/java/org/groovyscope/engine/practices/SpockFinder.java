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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.Types;

/**
 * Spock specification checks, GS040 to GS043, for what Spock rejects or quietly ignores and the analysis compile
 * (which does not run Spock's transformation) never reports: an interaction in {@code then:} without a cardinality
 * or a mock created there (GS040), a data table column that is never used or is declared twice (GS041), an
 * {@code @Unroll} without a name pattern, redundant since Spock 2 unrolls every data driven feature (GS042), and
 * an exception condition outside {@code then:} or a second one in the same {@code then:} block (GS043). Everything
 * is read from the feature's blocks (its statement labels) and the shapes Spock gives meaning to.
 */
public final class SpockFinder {

    /** The calls that create a Spock test double. */
    static final Set<String> DOUBLE_FACTORIES = Set.of("Mock", "Stub", "Spy", "GroovyMock", "GroovyStub",
            "GroovySpy");
    /** Spock's exception conditions. */
    static final Set<String> EXCEPTION_CONDITIONS = Set.of("thrown", "notThrown", "noExceptionThrown");
    /** The block labels that make a method a feature. */
    static final Set<String> BLOCKS = Set.of("given", "setup", "when", "then", "expect", "where", "cleanup");

    private SpockFinder() {
    }

    /** Adds the findings of every specification in the document to {@code context}. */
    static void find(PracticeContext context) {
        for (ClassNode classNode : context.module().getClasses()) {
            if (!PracticeContext.isSpecification(classNode)) {
                continue;
            }
            checkUnroll(context, classNode);
            for (MethodNode method : classNode.getMethods()) {
                List<Block> blocks = blocks(method);
                if (!blocks.isEmpty()) {
                    checkFeature(context, method, blocks);
                }
            }
        }
    }

    /** One block of a feature: its label ({@code and:} continues the block before) and its statements. */
    record Block(String label, List<Statement> statements) {
    }

    /** The blocks of a feature method in order; empty when the method has no Spock block label. */
    static List<Block> blocks(MethodNode method) {
        List<Block> blocks = new ArrayList<>();
        if (!(method.getCode() instanceof BlockStatement code)) {
            return blocks;
        }
        Block current = null;
        for (Statement statement : code.getStatements()) {
            List<String> labels = statement.getStatementLabels();
            String label = labels == null || labels.isEmpty() ? null : labels.get(0);
            if (label != null && BLOCKS.contains(label)) {
                current = new Block(label, new ArrayList<>());
                blocks.add(current);
            }
            if (current != null) {
                current.statements().add(statement);
            }
        }
        return blocks;
    }

    private static void checkUnroll(PracticeContext context, AnnotatedNode node) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            if ("Unroll".equals(annotation.getClassNode().getNameWithoutPackage()) && annotation.getMembers().isEmpty()) {
                context.report("GS042", annotation, "Spock 2 unrolls every data driven feature already; @Unroll "
                        + "without a name pattern does nothing, so remove it");
            }
        }
    }

    private static void checkFeature(PracticeContext context, MethodNode method, List<Block> blocks) {
        checkUnroll(context, method);
        for (Block block : blocks) {
            int conditions = 0;
            for (Statement statement : block.statements()) {
                if (!(statement instanceof ExpressionStatement expressionStatement)) {
                    continue;
                }
                Expression expression = expressionStatement.getExpression();
                if ("then".equals(block.label())) {
                    checkInteraction(context, expression);
                }
                MethodCallExpression condition = exceptionCondition(expression);
                if (condition != null) {
                    conditions++;
                    if (!"then".equals(block.label())) {
                        context.report("GS043", condition, condition.getMethodAsString() + "() in " + block.label()
                                + ": Spock allows exception conditions only in then:, after the when: that throws");
                    } else if (conditions > 1) {
                        context.report("GS043", condition, "a second exception condition in one then: block; Spock "
                                + "allows one, so split the feature or drop the redundant condition");
                    }
                }
            }
            if ("where".equals(block.label())) {
                checkTables(context, method, block);
            }
        }
    }

    private static void checkInteraction(PracticeContext context, Expression expression) {
        if (expression instanceof BinaryExpression binary && isResponse(binary)
                && !(binary.getLeftExpression() instanceof BinaryExpression left
                        && left.getOperation().getType() == Types.MULTIPLY)) {
            context.report("GS040", binary, "an interaction in then: without a cardinality is a stub, not a check: "
                    + "say how often it must happen (1 * mock.call()) or move the stub to given:");
            return;
        }
        Expression created = expression instanceof DeclarationExpression declaration
                ? declaration.getRightExpression() : expression;
        if (created instanceof MethodCallExpression call && call.isImplicitThis()
                && DOUBLE_FACTORIES.contains(call.getMethodAsString())) {
            context.report("GS040", call, call.getMethodAsString() + "() in then: is created after when: has run, so "
                    + "no interaction can reach it; create the double in given:");
        }
    }

    private static boolean isResponse(BinaryExpression binary) {
        int type = binary.getOperation().getType();
        return type == Types.RIGHT_SHIFT || type == Types.RIGHT_SHIFT_UNSIGNED;
    }

    private static MethodCallExpression exceptionCondition(Expression expression) {
        Expression call = expression instanceof DeclarationExpression declaration
                ? declaration.getRightExpression() : expression;
        return call instanceof MethodCallExpression method && method.isImplicitThis()
                && EXCEPTION_CONDITIONS.contains(method.getMethodAsString()) ? method : null;
    }

    /** GS041: data table columns declared twice, or never read anywhere in the feature or its name. */
    private static void checkTables(PracticeContext context, MethodNode method, Block where) {
        List<ExpressionStatement> headers = new ArrayList<>();
        boolean inTable = false;
        for (Statement statement : where.statements()) {
            List<Expression> cells = statement instanceof ExpressionStatement row ? cells(row.getExpression()) : null;
            if (cells == null) {
                inTable = false;
                continue;
            }
            if (!inTable && cells.stream().allMatch(VariableExpression.class::isInstance)) {
                headers.add((ExpressionStatement) statement);
            }
            inTable = true;
        }
        if (headers.isEmpty()) {
            return;
        }
        Map<String, Integer> references = references(method);
        for (ExpressionStatement header : headers) {
            Set<String> seen = new LinkedHashSet<>();
            for (Expression cell : cells(header.getExpression())) {
                String name = ((VariableExpression) cell).getName();
                if ("_".equals(name)) {
                    continue;
                }
                references.merge(name, -1, Integer::sum);
                if (!seen.add(name)) {
                    context.report("GS041", header, "the data table declares '" + name + "' twice; Spock rejects "
                            + "the feature, so rename or remove one column");
                }
            }
            for (String name : seen) {
                if (references.getOrDefault(name, 0) <= 0 && !named(method, name)) {
                    context.report("GS041", header, "data table column '" + name + "' is never used by the feature: "
                            + "its values test nothing, so use it or remove the column");
                }
            }
        }
    }

    /** The cells of a data table row ({@code a | b || c}), or {@code null} when the expression is no row. */
    static List<Expression> cells(Expression expression) {
        if (!(expression instanceof BinaryExpression binary) || !isColumnSeparator(binary)) {
            return null;
        }
        List<Expression> cells = new ArrayList<>();
        collect(binary, cells);
        return cells;
    }

    private static void collect(Expression expression, List<Expression> cells) {
        if (expression instanceof BinaryExpression binary && isColumnSeparator(binary)) {
            collect(binary.getLeftExpression(), cells);
            collect(binary.getRightExpression(), cells);
        } else {
            cells.add(expression);
        }
    }

    private static boolean isColumnSeparator(BinaryExpression binary) {
        int type = binary.getOperation().getType();
        return type == Types.BITWISE_OR || type == Types.LOGICAL_OR;
    }

    /** How often each variable name is read or written in the method's code. */
    private static Map<String, Integer> references(MethodNode method) {
        Map<String, Integer> counts = new HashMap<>();
        method.getCode().visit(new CodeVisitorSupport() {
            @Override
            public void visitVariableExpression(VariableExpression expression) {
                counts.merge(expression.getName(), 1, Integer::sum);
                super.visitVariableExpression(expression);
            }
        });
        return counts;
    }

    /** Whether the feature's name, or its {@code @Unroll} pattern, shows the column ({@code #name}). */
    private static boolean named(MethodNode method, String name) {
        List<String> patterns = new ArrayList<>(List.of(method.getName()));
        for (AnnotationNode annotation : method.getAnnotations()) {
            if (annotation.getMember("value") instanceof ConstantExpression value && value.getValue() != null) {
                patterns.add(value.getValue().toString());
            }
        }
        for (String pattern : patterns) {
            int at = pattern.indexOf("#" + name);
            while (at >= 0) {
                int end = at + 1 + name.length();
                if (end == pattern.length() || !Character.isJavaIdentifierPart(pattern.charAt(end))) {
                    return true;
                }
                at = pattern.indexOf("#" + name, end);
            }
        }
        return false;
    }
}
