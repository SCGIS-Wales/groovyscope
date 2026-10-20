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
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.TextEdit;

/**
 * Groovy truth, Elvis and safe navigation: Elvis on a value where 0 or empty is valid (GS002), a safe-navigation
 * result stored in or returned as a primitive (GS003), and a File or Path tested for truth (GS004).
 */
final class TruthChecks {

    static final Set<String> ELVIS_SENSITIVE = Set.of("int", "long", "short", "byte", "double", "float",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
            "java.lang.Float", "java.math.BigDecimal", "java.math.BigInteger", "java.lang.Number", "java.lang.String",
            "java.lang.CharSequence", "groovy.lang.GString", "java.util.List", "java.util.Collection", "java.util.Map",
            "java.util.Set", "java.util.ArrayList", "java.util.LinkedHashMap", "java.util.HashMap");
    /**
     * Parsers that reject an empty input ({@code readTree('')} throws): an Elvis passed straight to one defaults
     * empty and null alike on purpose, so GS002 leaves it alone.
     */
    static final Set<String> EMPTY_REJECTING_PARSERS = Set.of("readTree", "readValue", "parseText", "parse",
            "parseInt", "parseLong", "parseDouble", "parseFloat", "parseShort", "parseByte", "fromJson");
    static final Set<String> PRIMITIVE_DEFAULTS = Set.of("int", "long", "short", "byte", "double", "float", "char");
    static final Set<String> FILE_TYPES = Set.of("java.io.File", "java.nio.file.Path");

    private TruthChecks() {
    }

    /** Remembers the Elvis expressions passed directly as arguments to an empty-rejecting parser. */
    static void parserArguments(CoreScan scan, Expression arguments) {
        if (arguments instanceof TupleExpression tuple) {
            for (Expression argument : tuple.getExpressions()) {
                if (argument instanceof ElvisOperatorExpression elvis) {
                    scan.parsed.add(elvis);
                }
            }
        }
    }

    static void primitiveSafeNavigation(CoreScan scan, Expression target, Expression value) {
        ClassNode type = target instanceof VariableExpression variable && !variable.isDynamicTyped()
                ? variable.getOriginType() : PracticeFinder.typeOf(target);
        safeIntoPrimitive(scan, type, value);
    }

    /**
     * GS003: a safe-navigation result stored in, or returned as, a primitive. A {@code boolean} is left out:
     * Groovy turns null into false there, which is what the code means.
     */
    static void safeIntoPrimitive(CoreScan scan, ClassNode type, Expression value) {
        boolean safe = value instanceof MethodCallExpression call && call.isSafe()
                || value instanceof PropertyExpression property && property.isSafe();
        if (type != null && PRIMITIVE_DEFAULTS.contains(type.getName()) && safe) {
            String text = scan.context.source(value);
            String fallback = "0";
            List<TextEdit> fix = text == null ? List.of()
                    : List.of(scan.context.replace(value, text + " ?: " + fallback));
            scan.context.report("GS003", value, "null when the receiver is null, which a " + type.getName()
                    + " cannot hold", "Default with ?: " + fallback, fix);
        }
    }

    static void returned(CoreScan scan, ReturnStatement statement) {
        if (scan.currentMethod != null && !scan.currentMethod.isDynamicReturnType()) {
            safeIntoPrimitive(scan, scan.currentMethod.getReturnType(), statement.getExpression());
        }
    }

    /** GS003 on a method's implicit return (its last expression statement). */
    static void implicitReturn(CoreScan scan, MethodNode node) {
        if (!node.isDynamicReturnType() && lastStatement(node.getCode()) instanceof ExpressionStatement implicit) {
            safeIntoPrimitive(scan, node.getReturnType(), implicit.getExpression());
        }
    }

    private static Statement lastStatement(Statement code) {
        if (code instanceof BlockStatement block) {
            List<Statement> statements = block.getStatements();
            return statements.isEmpty() ? null : lastStatement(statements.get(statements.size() - 1));
        }
        return code;
    }

    static void ternary(CoreScan scan, TernaryExpression expression) {
        if (expression instanceof ElvisOperatorExpression elvis) {
            Expression tested = elvis.getTrueExpression();
            ClassNode type = PracticeFinder.typeOf(tested);
            if (PracticeFinder.named(type, FILE_TYPES)) {
                scan.context.report("GS004", tested, "a File or Path is truthy only if it exists (Groovy 5)", null,
                        List.of());
            } else if (PracticeFinder.named(type, ELVIS_SENSITIVE)
                    && !PracticeFinder.sameEmpty(type, elvis.getFalseExpression()) && !scan.parsed.contains(elvis)) {
                String testedText = scan.context.source(tested);
                String fallback = scan.context.source(elvis.getFalseExpression());
                boolean fixable = testedText != null && fallback != null && tested instanceof VariableExpression
                        && !ClassHelper.isPrimitiveType(type);
                List<TextEdit> fix = fixable
                        ? List.of(scan.context.replace(elvis, testedText + " != null ? " + testedText + " : "
                                + fallback))
                        : List.of();
                // An empty text or collection is more often absent in all but name than a 0 is: advice there.
                scan.context.reportAtMost(emptyValue(type).equals("0") ? Severity.ERROR : Severity.INFORMATION,
                        "GS002", elvis, "?: also replaces " + emptyValue(type) + ", which is a valid "
                                + type.getNameWithoutPackage() + "; a defect only where that empty value is a real "
                                + "input, distinct from absent", "Compare with null", fix);
            }
        } else {
            truthiness(scan, expression.getBooleanExpression());
        }
    }

    private static String emptyValue(ClassNode type) {
        String name = type.getName();
        if (name.contains("String") || name.contains("CharSequence")) {
            return "an empty string";
        }
        if (name.contains("List") || name.contains("Collection") || name.contains("Map") || name.contains("Set")) {
            return "an empty collection";
        }
        return "0";
    }

    /** GS004: a File or Path used as a condition. */
    static void truthiness(CoreScan scan, BooleanExpression condition) {
        Expression tested = condition.getExpression();
        boolean negated = false;
        if (tested instanceof NotExpression not) {
            tested = not.getExpression();
            negated = true;
        }
        if (PracticeFinder.named(PracticeFinder.typeOf(tested), FILE_TYPES)) {
            String text = scan.context.source(tested);
            Expression whole = condition.getExpression();
            List<TextEdit> fix = text == null ? List.of()
                    : List.of(scan.context.replace(whole, text + (negated ? " == null" : " != null")));
            boolean path = "java.nio.file.Path".equals(PracticeFinder.typeOf(tested).getName());
            String exists = path ? "java.nio.file.Files.exists(" + text + ")"
                    : NumberChecks.wrap(tested, text) + ".exists()";
            List<PracticeFinder.Alternative> alternatives = text == null ? List.of()
                    : List.of(new PracticeFinder.Alternative(negated ? "Test that it does not exist"
                            : "Test that it exists", List.of(scan.context.replace(whole, (negated ? "!" : "")
                                    + exists))));
            scan.context.report("GS004", whole, "a File or Path is truthy only if it exists (Groovy 5); say whether "
                    + "you mean present (!= null) or existing (exists())",
                    negated ? "Compare with == null" : "Compare with != null", fix, alternatives);
        }
    }
}
