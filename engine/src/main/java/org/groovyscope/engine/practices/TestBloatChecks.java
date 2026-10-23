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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.LambdaExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.log.EngineLog;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.practices.TestMethods.Test;

/**
 * GS044 to GS048, tests kept lean, on test sources only: tests that repeat one shape with other literals (GS044, with a
 * {@code where:} table as the fix for Spock), tests that check only a constructor or an echoing getter (GS045), test
 * classes out of proportion to the class they test (GS046, in {@link TestSize}), a test repeating another's call and
 * assertions (GS047), and test names that say no behaviour (GS048). Settings are {@code tests.*}.
 */
final class TestBloatChecks {

    static final String MERGE = "merge into one data driven test (`where:` table or `@ParameterizedTest`)";

    private final PracticeContext context;
    private final EngineConfig.Tests settings;
    private final TestMethods methods;

    private TestBloatChecks(PracticeContext context, TestMethods methods) {
        this.context = context;
        this.settings = context.config().practices().tests();
        this.methods = methods;
    }

    static void find(PracticeContext context) {
        if (context.module() == null) {
            return;
        }
        TestMethods methods = null;
        for (ClassNode classNode : context.module().getClasses()) {
            if (!TestMethods.isTestClass(context, classNode)) {
                continue;
            }
            methods = methods == null ? new TestMethods(context.lines()) : methods;
            List<Test> tests = methods.of(classNode);
            if (tests.isEmpty()) {
                continue;
            }
            TestBloatChecks checks = new TestBloatChecks(context, methods);
            checks.duplicates(classNode, tests);
            for (Test test : tests) {
                checks.trivial(test);
            }
            TestSize.check(context, classNode, tests.size());
            checks.sameInputs(tests);
            checks.names(tests);
        }
    }

    /** GS044: tests of one shape, literals aside; reported at every test after the first of its group. */
    private void duplicates(ClassNode classNode, List<Test> tests) {
        Map<String, List<Test>> groups = new LinkedHashMap<>();
        for (Test test : tests) {
            if (test.conditions().isEmpty() && test.others().isEmpty()) {
                continue;
            }
            String shape = methods.shape(test);
            if (shape != null) {
                groups.computeIfAbsent(shape, key -> new ArrayList<>()).add(test);
            }
        }
        for (List<Test> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Test first = group.get(0);
            List<TextEdit> fix = DataTableFix.edits(context, methods, classNode, group);
            for (Test test : group.subList(1, group.size())) {
                Range at = methods.nameRange(test.method());
                if (at != null) {
                    context.report("GS044", at, "the same steps as " + TestMethods.display(first.method()) + " (line "
                            + first.method().getLineNumber() + "), literals aside: " + MERGE,
                            fix.isEmpty() ? null : "Merge the " + group.size() + " tests into one with a where: table",
                            fix);
                }
            }
        }
    }

    /** GS045: a test that checks only that a constructor does not throw, or that getters echo their inputs. */
    private void trivial(Test test) {
        String message = new Trivial(test).verdict();
        Range at = message == null ? null : methods.nameRange(test.method());
        if (at != null) {
            context.report("GS045", at, message + "; test behaviour the code computes from its inputs, or delete it",
                    null, List.of());
        }
    }

    /** GS047: a test that makes the same calls with the same literal arguments and asserts the same as another. */
    private void sameInputs(List<Test> tests) {
        Map<String, Test> seen = new HashMap<>();
        for (Test test : tests) {
            if (test.dataDriven() || test.conditions().isEmpty()) {
                continue;
            }
            Map<String, String> calls = literalCalls(test.method());
            if (calls.isEmpty()) {
                continue;
            }
            Set<String> asserted = new TreeSet<>();
            for (Expression condition : test.conditions()) {
                asserted.add(String.valueOf(methods.compact(condition)));
            }
            Set<String> steps = new TreeSet<>();
            for (Statement statement : test.others()) {
                steps.add(String.valueOf(methods.compact(statement)));
            }
            String key = String.join("\u0002", new TreeSet<>(calls.keySet())) + "\u0003" + String.join("\u0002", asserted)
                    + "\u0003" + String.join("\u0002", steps);
            Test first = seen.putIfAbsent(key, test);
            Range at = first == null ? null : methods.nameRange(test.method());
            if (at != null) {
                context.report("GS047", at, "calls " + calls.values().iterator().next() + " with the same arguments, "
                        + "and asserts the same, as " + TestMethods.display(first.method()) + " (line "
                        + first.method().getLineNumber() + "): delete it, or give it an input "
                        + TestMethods.display(first.method()) + " does not cover", null, List.of());
            }
        }
    }

    /** The calls of a method whose arguments are all literals, by compact text, each with a readable form. */
    private Map<String, String> literalCalls(MethodNode method) {
        Map<String, String> calls = new LinkedHashMap<>();
        method.getCode().visit(new CodeVisitorSupport() {
            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                add(call, call.getMethodAsString(), call.getArguments());
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                add(call, call.getMethod(), call.getArguments());
                super.visitStaticMethodCallExpression(call);
            }

            private void add(Expression call, String name, Expression arguments) {
                List<Expression> values = arguments instanceof TupleExpression tuple ? tuple.getExpressions() : List.of();
                if (name == null || values.isEmpty() || TestMethods.NOT_UNDER_TEST.contains(name)
                        || TestMethods.conditionCall(call)) {
                    return;
                }
                List<String> shown = new ArrayList<>();
                for (Expression value : values) {
                    String text = value instanceof ConstantExpression literal ? methods.text(literal) : null;
                    if (text == null) {
                        return;
                    }
                    shown.add(text);
                }
                String key = methods.compact(call);
                if (key != null) {
                    calls.putIfAbsent(key, name + "(" + String.join(", ", shown) + ")");
                }
            }
        });
        return calls;
    }

    /** GS048: a test name that says no behaviour, or does not match {@code tests.namePattern}. */
    private void names(List<Test> tests) {
        Pattern good = namePattern(settings.namePattern());
        Set<String> vague = new HashSet<>();
        for (String word : settings.vagueNames()) {
            vague.add(CommentChecks.stem(word.toLowerCase(Locale.ROOT)));
        }
        for (Test test : tests) {
            String name = test.method().getName();
            Set<String> called = calledNames(test.method(), vague);
            List<String> meaningful = meaningful(name, vague);
            meaningful.removeIf(called::contains);
            String message = null;
            if (meaningful.isEmpty() || called.contains(String.join("", meaningful))) {
                message = "the name " + TestMethods.display(test.method()) + " says no behaviour (only vague words, "
                        + "numbers or the name of what it calls): name what is expected, such as 'returns an empty "
                        + "list for a blank query'";
            } else if (!good.matcher(name).matches()) {
                message = "the name " + TestMethods.display(test.method()) + " does not match tests.namePattern"
                        + (EngineConfig.Tests.DEFAULT_NAME_PATTERN.equals(good.pattern())
                                ? " (a sentence, or an identifier of two words or more)" : "")
                        + ": name the behaviour and what is expected";
            }
            Range at = message == null ? null : methods.nameRange(test.method());
            if (at != null) {
                context.report("GS048", at, message, null, List.of());
            }
        }
    }

    private static Pattern namePattern(String configured) {
        try {
            return Pattern.compile(configured);
        } catch (PatternSyntaxException e) {
            EngineLog.debug("tests.namePattern is not a regular expression, the default is used: %s", e.getMessage());
            return Pattern.compile(EngineConfig.Tests.DEFAULT_NAME_PATTERN);
        }
    }

    /** A name's words that may say something: vague words and numbers left out, each reduced to its stem. */
    static List<String> meaningful(String name, Set<String> vague) {
        List<String> kept = new ArrayList<>();
        for (String word : TestMethods.words(name)) {
            String stem = CommentChecks.stem(word);
            if (!Character.isDigit(word.charAt(0)) && !vague.contains(stem)) {
                kept.add(stem);
            }
        }
        return kept;
    }

    /** The methods a test calls and the classes it creates, each as its meaningful words run together. */
    private static Set<String> calledNames(MethodNode method, Set<String> vague) {
        Set<String> names = new HashSet<>();
        method.getCode().visit(new CodeVisitorSupport() {
            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                add(call.getMethodAsString());
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                add(call.getMethod());
                super.visitStaticMethodCallExpression(call);
            }

            @Override
            public void visitConstructorCallExpression(ConstructorCallExpression call) {
                add(call.getType().getNameWithoutPackage());
                super.visitConstructorCallExpression(call);
            }

            @Override
            public void visitPropertyExpression(PropertyExpression expression) {
                add(expression.getPropertyAsString());
                super.visitPropertyExpression(expression);
            }

            private void add(String name) {
                if (name != null) {
                    List<String> words = meaningful(name, vague);
                    if (!words.isEmpty()) {
                        names.add(String.join("", words));
                        names.addAll(words.size() == 1 ? words : List.of());
                    }
                }
            }
        });
        return names;
    }

    /** Whether two literal values are the same value (numbers compared by value, {@code 1} and {@code 1L} alike). */
    static boolean same(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            try {
                return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
            } catch (NumberFormatException e) {
                return a.equals(b);
            }
        }
        return Objects.equals(left, right);
    }

    /** GS045 for one test: what it builds and sets, and whether its conditions check anything else. */
    private static final class Trivial {

        private static final Object UNKNOWN = new Object();

        private final Test test;
        private final Map<String, Object> constants = new HashMap<>();
        private final Map<String, ConstructorCallExpression> objects = new HashMap<>();
        private final Map<String, Map<String, Object>> set = new HashMap<>();
        private final List<ConstructorCallExpression> created = new ArrayList<>();

        Trivial(Test test) {
            this.test = test;
        }

        /** The message when the test is trivial, else {@code null}. */
        String verdict() {
            if (test.dataDriven() || !steps()) {
                return null;
            }
            boolean noThrow = true;
            boolean echo = !test.conditions().isEmpty();
            String echoed = null;
            for (Expression condition : test.conditions()) {
                noThrow &= noThrow(condition);
                String property = echo ? echoed(condition) : null;
                echo &= property != null;
                echoed = echoed == null ? property : echoed;
            }
            if (created.isEmpty()) {
                return null;
            }
            ConstructorCallExpression first = created.get(0);
            String type = first.getType().getNameWithoutPackage();
            if (test.conditions().isEmpty()) {
                return "the test creates " + type + " and asserts nothing";
            }
            if (noThrow) {
                return "the test only checks that new " + type + (arguments(first.getArguments()).isEmpty() ? "()"
                        : "(...)") + " does not throw";
            }
            return echo ? "the test only checks that " + echoed + " returns what the constructor or a setter was given"
                    : null;
        }

        /** Reads the steps; false when one does more than create, set or name a literal. */
        private boolean steps() {
            for (Statement statement : test.others()) {
                if (!(statement instanceof ExpressionStatement step) || !step(step.getExpression())) {
                    return false;
                }
            }
            return true;
        }

        private boolean step(Expression expression) {
            if (expression instanceof ConstructorCallExpression call) {
                created.add(call);
                return true;
            }
            if (expression instanceof DeclarationExpression declaration && !declaration.isMultipleAssignmentDeclaration()) {
                String name = declaration.getVariableExpression().getName();
                Expression right = declaration.getRightExpression();
                if (right instanceof ConstructorCallExpression call) {
                    objects.put(name, call);
                    created.add(call);
                    return true;
                }
                Object value = value(right);
                constants.put(name, value);
                return value != UNKNOWN;
            }
            if (expression instanceof BinaryExpression binary && binary.getOperation().getType() == Types.ASSIGN
                    && binary.getLeftExpression() instanceof PropertyExpression property
                    && property.getObjectExpression() instanceof VariableExpression target
                    && objects.containsKey(target.getName()) && property.getPropertyAsString() != null) {
                Object value = value(binary.getRightExpression());
                set.computeIfAbsent(target.getName(), key -> new HashMap<>()).put(property.getPropertyAsString(), value);
                return value != UNKNOWN;
            }
            if (expression instanceof MethodCallExpression call && call.getObjectExpression() instanceof VariableExpression target
                    && objects.containsKey(target.getName()) && call.getMethodAsString() != null
                    && call.getMethodAsString().matches("set[A-Z].*") && arguments(call.getArguments()).size() == 1) {
                Object value = value(arguments(call.getArguments()).get(0));
                set.computeIfAbsent(target.getName(), key -> new HashMap<>())
                        .put(decapitalise(call.getMethodAsString().substring(3)), value);
                return value != UNKNOWN;
            }
            return false;
        }

        /** A condition that holds whenever a constructor returns: no exception, not null, Groovy truth of it. */
        private boolean noThrow(Expression condition) {
            if (condition instanceof ConstructorCallExpression call) {
                created.add(call);
                return true;
            }
            if (condition instanceof VariableExpression variable) {
                return objects.containsKey(variable.getName());
            }
            if (condition instanceof BinaryExpression binary && binary.getOperation().getType() == Types.COMPARE_NOT_EQUAL) {
                return created(binary.getLeftExpression()) && isNull(binary.getRightExpression())
                        || created(binary.getRightExpression()) && isNull(binary.getLeftExpression());
            }
            String name = condition instanceof MethodCallExpression call ? call.getMethodAsString()
                    : condition instanceof StaticMethodCallExpression call ? call.getMethod() : null;
            List<Expression> arguments = condition instanceof MethodCallExpression call ? arguments(call.getArguments())
                    : condition instanceof StaticMethodCallExpression call ? arguments(call.getArguments()) : List.of();
            if ("noExceptionThrown".equals(name) && arguments.isEmpty() || "notThrown".equals(name)) {
                return true;
            }
            if ("assertNotNull".equals(name) && !arguments.isEmpty()) {
                return created(arguments.get(arguments.size() - 1));
            }
            if ("assertDoesNotThrow".equals(name) && !arguments.isEmpty()) {
                return constructsOnly(arguments.get(0));
            }
            return false;
        }

        private boolean created(Expression expression) {
            return expression instanceof VariableExpression variable && objects.containsKey(variable.getName())
                    || expression instanceof ConstructorCallExpression;
        }

        private static boolean isNull(Expression expression) {
            return expression instanceof ConstantExpression constant && constant.isNullExpression();
        }

        /** A closure or lambda that only creates objects ({@code { new Subject() }}). */
        private boolean constructsOnly(Expression expression) {
            Statement code = expression instanceof ClosureExpression closure ? closure.getCode()
                    : expression instanceof LambdaExpression lambda ? lambda.getCode() : null;
            List<Statement> statements = code instanceof BlockStatement block ? block.getStatements()
                    : code == null ? List.of() : List.of(code);
            if (statements.isEmpty()) {
                return false;
            }
            for (Statement statement : statements) {
                Expression body = statement instanceof ExpressionStatement step ? step.getExpression()
                        : statement instanceof org.codehaus.groovy.ast.stmt.ReturnStatement result
                                ? result.getExpression() : null;
                if (!(body instanceof ConstructorCallExpression call)) {
                    return false;
                }
                created.add(call);
            }
            return true;
        }

        /** The property a condition compares with the very value it was given, or {@code null}. */
        private String echoed(Expression condition) {
            Expression left;
            Expression right;
            String name = condition instanceof MethodCallExpression call ? call.getMethodAsString()
                    : condition instanceof StaticMethodCallExpression call ? call.getMethod() : null;
            if (condition instanceof BinaryExpression binary && binary.getOperation().getType() == Types.COMPARE_EQUAL) {
                left = binary.getLeftExpression();
                right = binary.getRightExpression();
            } else if ("assertEquals".equals(name) || "assertSame".equals(name)) {
                List<Expression> arguments = condition instanceof MethodCallExpression call
                        ? arguments(call.getArguments()) : arguments(((StaticMethodCallExpression) condition).getArguments());
                if (arguments.size() < 2 || arguments.size() > 3) {
                    return null;
                }
                left = arguments.get(arguments.size() - 2);
                right = arguments.get(arguments.size() - 1);
            } else {
                return null;
            }
            String echoed = echo(left, right);
            return echoed != null ? echoed : echo(right, left);
        }

        /** The property {@code read} reads when {@code expected} is the value it was given, else {@code null}. */
        private String echo(Expression read, Expression expected) {
            Object value = value(expected);
            if (value == UNKNOWN) {
                return null;
            }
            Expression owner;
            String property;
            if (read instanceof PropertyExpression access && access.getPropertyAsString() != null) {
                owner = access.getObjectExpression();
                property = access.getPropertyAsString();
            } else if (read instanceof MethodCallExpression call && call.getMethodAsString() != null
                    && arguments(call.getArguments()).isEmpty() && call.getMethodAsString().matches("(get|is)[A-Z].*")) {
                owner = call.getObjectExpression();
                property = decapitalise(call.getMethodAsString().substring(call.getMethodAsString().startsWith("is") ? 2 : 3));
            } else {
                return null;
            }
            Map<String, Object> given = inputs(owner);
            if (!given.containsKey(property) || !same(given.get(property), value)) {
                return null;
            }
            if (owner instanceof ConstructorCallExpression call) {
                created.add(call);
            }
            return property;
        }

        /** What an object was given, by property: named or positional constructor arguments, then setters. */
        private Map<String, Object> inputs(Expression owner) {
            Map<String, Object> given = new HashMap<>();
            ConstructorCallExpression call = owner instanceof ConstructorCallExpression direct ? direct
                    : owner instanceof VariableExpression variable ? objects.get(variable.getName()) : null;
            if (call == null) {
                return given;
            }
            List<Expression> arguments = arguments(call.getArguments());
            List<MapEntryExpression> named = new ArrayList<>();
            for (Expression argument : arguments) {
                if (argument instanceof MapExpression map) {
                    named.addAll(map.getMapEntryExpressions());
                }
            }
            if (!named.isEmpty()) {
                for (MapEntryExpression entry : named) {
                    if (entry.getKeyExpression() instanceof ConstantExpression key) {
                        given.put(String.valueOf(key.getValue()), value(entry.getValueExpression()));
                    }
                }
            } else {
                List<String> parameters = constructorParameters(call.getType(), arguments.size());
                for (int i = 0; i < parameters.size(); i++) {
                    given.put(parameters.get(i), value(arguments.get(i)));
                }
            }
            if (owner instanceof VariableExpression variable) {
                given.putAll(set.getOrDefault(variable.getName(), Map.of()));
            }
            given.values().removeIf(value -> value == UNKNOWN);
            return given;
        }

        /** The parameter names of the one constructor taking {@code count} arguments; empty when not exactly one. */
        private static List<String> constructorParameters(ClassNode type, int count) {
            List<String> names = new ArrayList<>();
            try {
                List<ConstructorNode> matching = type.getDeclaredConstructors().stream()
                        .filter(constructor -> constructor.getParameters().length == count).toList();
                if (matching.size() == 1) {
                    for (Parameter parameter : matching.get(0).getParameters()) {
                        names.add(parameter.getName());
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                EngineLog.debug("constructors of %s not readable: %s", type.getName(), e);
            }
            return names;
        }

        /** A literal's value, or the literal a local was declared with; {@link #UNKNOWN} otherwise. */
        private Object value(Expression expression) {
            if (expression instanceof ConstantExpression constant) {
                return constant.getValue();
            }
            if (expression instanceof VariableExpression variable && constants.containsKey(variable.getName())) {
                return constants.get(variable.getName());
            }
            return UNKNOWN;
        }

        private static String decapitalise(String name) {
            return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
    }

    /** The argument expressions of a call. */
    static List<Expression> arguments(Expression arguments) {
        if (arguments instanceof ArgumentListExpression list) {
            return list.getExpressions();
        }
        return arguments instanceof TupleExpression tuple ? tuple.getExpressions() : List.of();
    }
}
