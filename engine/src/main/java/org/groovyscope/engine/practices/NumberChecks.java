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

import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.model.TextEdit;

/**
 * GS001, integer division: {@code /} on two integral operands yields a BigDecimal in Groovy. A division whose result
 * is narrowed to an integral type (an int target, a cast, an int return) truncates anyway and is left alone.
 */
final class NumberChecks {

    static final Set<String> INTEGRAL = Set.of("int", "long", "short", "byte", "java.lang.Integer", "java.lang.Long",
            "java.lang.Short", "java.lang.Byte", "java.math.BigInteger");

    private NumberChecks() {
    }

    static void binary(CoreScan scan, BinaryExpression expression) {
        int op = expression.getOperation().getType();
        if (op == Types.ASSIGN && PracticeFinder.named(PracticeFinder.typeOf(expression.getLeftExpression()), INTEGRAL)) {
            scan.narrowed.add(expression.getRightExpression());
        }
        if (op == Types.DIVIDE && !scan.narrowed.contains(expression)
                && PracticeFinder.named(PracticeFinder.typeOf(expression.getLeftExpression()), INTEGRAL)
                && PracticeFinder.named(PracticeFinder.typeOf(expression.getRightExpression()), INTEGRAL)) {
            String left = scan.context.source(expression.getLeftExpression());
            String right = scan.context.source(expression.getRightExpression());
            List<TextEdit> fix = left == null || right == null ? List.of()
                    : List.of(scan.context.replace(expression, wrap(expression.getLeftExpression(), left) + ".intdiv("
                            + right + ")"));
            scan.context.report("GS001", expression, "both operands are integral, so / yields a BigDecimal (a "
                    + "fraction); intdiv truncates, which changes the value, so only where truncation is meant",
                    "Use intdiv", fix);
        }
    }

    static void declaration(CoreScan scan, DeclarationExpression expression) {
        if (!expression.isMultipleAssignmentDeclaration() && !expression.getVariableExpression().isDynamicTyped()
                && PracticeFinder.named(expression.getVariableExpression().getOriginType(), INTEGRAL)) {
            scan.narrowed.add(expression.getRightExpression());
        }
    }

    static void cast(CoreScan scan, CastExpression expression) {
        if (PracticeFinder.named(expression.getType(), INTEGRAL)) {
            scan.narrowed.add(expression.getExpression());
        }
    }

    static void returned(CoreScan scan, ReturnStatement statement) {
        if (scan.currentMethod != null && PracticeFinder.named(scan.currentMethod.getReturnType(), INTEGRAL)) {
            scan.narrowed.add(statement.getExpression());
        }
    }

    /** {@code text} as a receiver: bracketed unless it is simple or a call. */
    static String wrap(Expression expression, String text) {
        return PracticeFinder.simple(expression) || expression instanceof MethodCallExpression ? text : "(" + text + ")";
    }
}
