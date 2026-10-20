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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.model.TextEdit;

/**
 * Security: code evaluation on a value that is not a constant (GS006), {@code @Grab} in production code (GS007), a
 * command string split on whitespace (GS008), DOCTYPE switched on for an XML parser (GS009) and a quoted
 * interpolation in SQL (GS012).
 */
final class SecurityChecks {

    static final Set<String> SQL_METHODS = Set.of("rows", "eachRow", "firstRow", "execute", "executeUpdate",
            "executeInsert", "query", "call");
    static final Set<String> EVAL_METHODS = Set.of("me", "x", "xy", "xyz");
    static final Set<String> SHELL_METHODS = Set.of("evaluate", "parse", "run", "parseClass");
    static final Set<String> SHELL_TYPES = Set.of("groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader");
    static final Set<String> XML_PARSERS = Set.of("groovy.xml.XmlSlurper", "groovy.xml.XmlParser",
            "groovy.util.XmlSlurper", "groovy.util.XmlParser");
    static final Set<String> GRAB_ANNOTATIONS = Set.of("Grab", "Grapes", "GrabResolver", "GrabConfig", "GrabExclude");
    static final Pattern QUOTED_INTERPOLATION = Pattern.compile("'(\\$\\{[^}]*\\}|\\$[A-Za-z_][\\w.]*)'");
    /** A LIKE pattern built around an interpolation inside quotes: {@code '%${x}%'}. */
    static final Pattern QUOTED_WILDCARD = Pattern.compile("'(%?)(?:\\$\\{([^}]*)\\}|\\$([A-Za-z_][\\w.]*))(%?)'");

    private SecurityChecks() {
    }

    /** GS007: {@code @Grab} and its relatives outside test code. */
    static void annotation(CoreScan scan, AnnotationNode annotation, String name) {
        if (!scan.context.test() && GRAB_ANNOTATIONS.contains(name)) {
            // No automatic fix: removing the annotation leaves code that no longer compiles.
            String coordinates = grabCoordinates(annotation);
            scan.context.report("GS007", annotation, "@" + name + " resolves dependencies at compile time"
                    + (coordinates == null ? "; declare the dependency in the build instead"
                            : "; declare implementation '" + coordinates + "' in the build instead"), null, List.of());
        }
    }

    /** {@code group:module:version} of a {@code @Grab}, from its value or its group, module and version. */
    static String grabCoordinates(AnnotationNode annotation) {
        Expression value = annotation.getMember("value");
        if (value instanceof ConstantExpression constant && constant.getValue() instanceof String text) {
            return text;
        }
        Expression group = annotation.getMember("group");
        Expression module = annotation.getMember("module");
        Expression version = annotation.getMember("version");
        if (group instanceof ConstantExpression g && module instanceof ConstantExpression m) {
            return g.getText() + ":" + m.getText() + (version instanceof ConstantExpression v ? ":" + v.getText() : "");
        }
        return null;
    }

    /** GS006 through {@code Eval} or a shell on a receiver. */
    static void evaluation(CoreScan scan, MethodCallExpression call, String name) {
        Expression receiver = call.getObjectExpression();
        boolean eval = receiver instanceof ClassExpression type && type.getType().getName().equals("groovy.util.Eval")
                && EVAL_METHODS.contains(name);
        boolean shell = SHELL_METHODS.contains(name) && PracticeFinder.named(PracticeFinder.typeOf(receiver),
                SHELL_TYPES);
        if ((eval || shell) && firstArgumentIsNotConstant(call.getArguments())) {
            scan.context.report("GS006", call, name + " compiles and runs a value that is not a constant", null,
                    List.of());
        }
    }

    /** GS006 through a static {@code Eval} call. */
    static void staticEvaluation(CoreScan scan, StaticMethodCallExpression call) {
        if (call.getOwnerType().getName().equals("groovy.util.Eval") && EVAL_METHODS.contains(call.getMethod())
                && firstArgumentIsNotConstant(call.getArguments())) {
            scan.context.report("GS006", call, "Eval." + call.getMethod() + " runs its argument as code", null,
                    List.of());
        }
    }

    private static boolean firstArgumentIsNotConstant(Expression arguments) {
        return arguments instanceof TupleExpression args && !args.getExpressions().isEmpty()
                && !(args.getExpression(0) instanceof ConstantExpression);
    }

    /** GS008: {@code execute()} on a String. */
    static void execute(CoreScan scan, MethodCallExpression call, String name) {
        if (!"execute".equals(name)) {
            return;
        }
        Expression receiver = call.getObjectExpression();
        boolean literal = receiver instanceof ConstantExpression constant && constant.getValue() instanceof String;
        boolean stringTyped = receiver instanceof GStringExpression
                || PracticeFinder.named(PracticeFinder.typeOf(receiver), Set.of("java.lang.String", "groovy.lang.GString"));
        if (!literal && !stringTyped) {
            return;
        }
        List<TextEdit> fix = List.of();
        if (literal) {
            String command = (String) ((ConstantExpression) receiver).getValue();
            if (!command.isBlank() && command.matches("[\\w./:=@+,-]+(\\s+[\\w./:=@+,-]+)*")) {
                StringBuilder list = new StringBuilder("[");
                String[] words = command.trim().split("\\s+");
                for (int i = 0; i < words.length; i++) {
                    list.append(i == 0 ? "" : ", ").append('\'').append(words[i]).append('\'');
                }
                fix = List.of(scan.context.replace(receiver, list.append(']').toString()));
            }
        }
        scan.context.report("GS008", call, "the command string is split on whitespace", "Use the list form", fix);
    }

    /** GS012: a quoted interpolation in a GString passed to {@code Sql}. */
    static void sql(CoreScan scan, MethodCallExpression call, String name) {
        if (!SQL_METHODS.contains(name) || !(call.getArguments() instanceof TupleExpression args)
                || args.getExpressions().isEmpty() || !(args.getExpression(0) instanceof GStringExpression gstring)) {
            return;
        }
        Expression receiver = call.getObjectExpression();
        boolean sql = PracticeFinder.named(PracticeFinder.typeOf(receiver), Set.of("groovy.sql.Sql"))
                || receiver instanceof VariableExpression variable && PracticeFinder.typeOf(variable) == null
                && Set.of("sql", "db").contains(variable.getName());
        String text = scan.context.source(gstring);
        if (!sql || text == null) {
            return;
        }
        Matcher wildcard = QUOTED_WILDCARD.matcher(text);
        while (wildcard.find()) {
            if (!wildcard.group(1).isEmpty() || !wildcard.group(4).isEmpty()) {
                String value = (wildcard.group(2) != null ? wildcard.group(2) : wildcard.group(3)).strip();
                String bound = "${" + (wildcard.group(1).isEmpty() ? "" : "'%' + ") + value
                        + (wildcard.group(4).isEmpty() ? "" : " + '%'") + "}";
                scan.context.report("GS012", gstring, "'" + wildcard.group(0).substring(1,
                        wildcard.group(0).length() - 1) + "' is concatenated, not bound; bind the whole pattern: "
                        + bound, null, List.of());
                return;
            }
        }
        Matcher matcher = QUOTED_INTERPOLATION.matcher(text);
        if (matcher.find()) {
            scan.context.report("GS012", gstring, "'" + matcher.group(1) + "' is concatenated, not bound",
                    "Remove the quotes", List.of(scan.context.replace(gstring,
                            QUOTED_INTERPOLATION.matcher(text).replaceAll("$1"))));
        }
    }

    /** GS009: {@code setFeature(...disallow-doctype-decl..., false)}. */
    static void xmlFeature(CoreScan scan, MethodCallExpression call, String name) {
        if (!"setFeature".equals(name) || !(call.getArguments() instanceof TupleExpression args)
                || args.getExpressions().size() != 2) {
            return;
        }
        Expression feature = args.getExpression(0);
        if (feature instanceof ConstantExpression constant && String.valueOf(constant.getValue())
                .contains("disallow-doctype-decl") && PracticeFinder.isFalse(args.getExpression(1))) {
            scan.context.report("GS009", call, "disallow-doctype-decl is switched off", null, List.of());
        }
    }

    /** GS009: {@code parser.allowDocTypeDeclaration = true}. */
    static void docTypeAssignment(CoreScan scan, BinaryExpression assignment) {
        if (assignment.getLeftExpression() instanceof PropertyExpression property
                && "allowDocTypeDeclaration".equals(property.getPropertyAsString())
                && PracticeFinder.isTrue(assignment.getRightExpression())) {
            scan.context.report("GS009", assignment, "allowDocTypeDeclaration = true enables DOCTYPE", null,
                    List.of());
        }
    }

    /** GS009: an XML parser constructed with DOCTYPE allowed. */
    static void xmlParser(CoreScan scan, ConstructorCallExpression call) {
        if (PracticeFinder.named(call.getType(), XML_PARSERS) && call.getArguments() instanceof TupleExpression args) {
            List<Expression> values = args.getExpressions();
            if (values.size() >= 3 && PracticeFinder.isTrue(values.get(2))) {
                scan.context.report("GS009", call, "the third argument (allowDocTypeDeclaration) enables DOCTYPE",
                        null, List.of());
            }
            for (Expression value : values) {
                if (value instanceof MapExpression map) {
                    docTypeEntries(scan, map, call);
                }
            }
        }
    }

    private static void docTypeEntries(CoreScan scan, MapExpression map, ASTNode where) {
        for (MapEntryExpression entry : map.getMapEntryExpressions()) {
            if (entry.getKeyExpression() instanceof ConstantExpression key
                    && "allowDocTypeDeclaration".equals(key.getValue()) && PracticeFinder.isTrue(entry.getValueExpression())) {
                scan.context.report("GS009", where, "allowDocTypeDeclaration: true enables DOCTYPE", null, List.of());
            }
        }
    }
}
