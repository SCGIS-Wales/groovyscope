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
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;

/**
 * The test methods of a document and what each one does, for the test bloat checks ({@link TestBloatChecks}): Spock
 * feature methods (a method of a specification with a block label), JUnit 4 and 5 tests ({@code @Test} and its data
 * driven and repeated forms) and JUnit 3 tests ({@code test*} methods of a {@code TestCase}), each split into its
 * conditions (what it asserts) and its other statements, with the literals of its body and a compact text of it
 * (comments and whitespace left out) to compare tests by.
 */
final class TestMethods {

    /** Annotations that make a method a JUnit 4 or 5 test. */
    static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
            "TestTemplate");
    /** Spock's exception conditions and the JUnit calls that assert or verify. */
    static final Set<String> CONDITION_CALLS = Set.of("thrown", "notThrown", "noExceptionThrown", "fail",
            "shouldFail", "verify", "verifyAll", "assertThat", "assertDoesNotThrow", "assertThrows", "assertAll");
    /** Calls that build doubles, wrap conditions or assert: never the code under test. */
    static final Set<String> NOT_UNDER_TEST = Set.of("Mock", "Stub", "Spy", "GroovyMock", "GroovyStub", "GroovySpy",
            "with", "verifyAll", "thrown", "notThrown", "noExceptionThrown", "interaction", "old", "println",
            "assertThat", "is", "equalTo", "fail", "shouldFail");

    /**
     * One test method.
     *
     * @param method the method
     * @param spock whether it is a Spock feature (else a JUnit test)
     * @param dataDriven whether it already takes data: a {@code where:} block or parameters
     * @param conditions what it asserts: Spock conditions of {@code expect:} and {@code then:}, assert statements,
     *     and calls such as {@code assertEquals} or {@code noExceptionThrown()}
     * @param others its other statements, Spock block descriptions left out
     * @param literals the literals of its body by position, block descriptions left out (method and property names,
     *     map keys and the text of GStrings are code, not literals)
     * @param descriptions its Spock block descriptions ({@code given: 'a store'}), documentation rather than steps
     */
    record Test(MethodNode method, boolean spock, boolean dataDriven, List<Expression> conditions,
            List<Statement> others, List<ConstantExpression> literals, List<ConstantExpression> descriptions) {
    }

    private final TextLines lines;
    private final BitSet comments = new BitSet();

    TestMethods(TextLines lines) {
        this.lines = lines;
        for (SourceComments.Comment comment : SourceComments.of(lines.text()).comments()) {
            comments.set(comment.start(), comment.end());
        }
    }

    /** Whether a class holds tests: a test source, a class named like a test, or a Spock specification. */
    static boolean isTestClass(PracticeContext context, ClassNode classNode) {
        return !classNode.getName().contains("$_") && (context.isTestClass(classNode.getNameWithoutPackage())
                || PracticeContext.isSpecification(classNode));
    }

    /** The test methods of a class, in source order. */
    List<Test> of(ClassNode classNode) {
        boolean specification = PracticeContext.isSpecification(classNode);
        boolean junit3 = !specification && extendsTestCase(classNode);
        List<Test> tests = new ArrayList<>();
        for (MethodNode method : classNode.getMethods()) {
            if (method.getLineNumber() < 1 || method.isAbstract() || !(method.getCode() instanceof BlockStatement)) {
                continue;
            }
            List<SpockFinder.Block> blocks = specification ? SpockFinder.blocks(method) : List.of();
            if (!blocks.isEmpty()) {
                tests.add(spock(method, blocks));
            } else if (!specification && (annotatedTest(method) || junit3 && method.isPublic()
                    && method.getName().startsWith("test") && method.getParameters().length == 0)) {
                tests.add(junit(method));
            }
        }
        tests.sort(Comparator.comparingInt(test -> test.method().getLineNumber()));
        return tests;
    }

    private static boolean extendsTestCase(ClassNode classNode) {
        for (ClassNode parent = classNode.getUnresolvedSuperClass(false); parent != null;
                parent = parent.getUnresolvedSuperClass(false)) {
            if (parent.getName().endsWith("TestCase")) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotatedTest(MethodNode method) {
        for (AnnotationNode annotation : method.getAnnotations()) {
            if (TEST_ANNOTATIONS.contains(annotation.getClassNode().getNameWithoutPackage())) {
                return true;
            }
        }
        return false;
    }

    private Test spock(MethodNode method, List<SpockFinder.Block> blocks) {
        List<Expression> conditions = new ArrayList<>();
        List<Statement> others = new ArrayList<>();
        List<ConstantExpression> descriptions = new ArrayList<>();
        boolean where = false;
        for (SpockFinder.Block block : blocks) {
            where |= "where".equals(block.label());
            boolean asserting = "expect".equals(block.label()) || "then".equals(block.label());
            for (Statement statement : block.statements()) {
                ConstantExpression description = description(statement);
                if (description != null) {
                    descriptions.add(description);
                } else if (statement instanceof AssertStatement assertion) {
                    conditions.add(assertion.getBooleanExpression().getExpression());
                } else if (statement instanceof ExpressionStatement expression
                        && (asserting && !binding(expression.getExpression())
                                || conditionCall(expression.getExpression()))) {
                    conditions.add(expression.getExpression());
                } else if (!"where".equals(block.label())) {
                    others.add(statement);
                }
            }
        }
        List<ConstantExpression> literals = literals(method);
        literals.removeIf(literal -> descriptions.stream().anyMatch(description -> description == literal));
        return new Test(method, true, where || method.getParameters().length > 0, conditions, others, literals,
                descriptions);
    }

    private Test junit(MethodNode method) {
        List<Expression> conditions = new ArrayList<>();
        List<Statement> others = new ArrayList<>();
        for (Statement statement : ((BlockStatement) method.getCode()).getStatements()) {
            if (statement instanceof AssertStatement assertion) {
                conditions.add(assertion.getBooleanExpression().getExpression());
            } else if (statement instanceof ExpressionStatement expression && conditionCall(expression.getExpression())) {
                conditions.add(expression.getExpression());
            } else {
                others.add(statement);
            }
        }
        boolean parameterized = method.getParameters().length > 0 || method.getAnnotations().stream()
                .anyMatch(annotation -> !"Test".equals(annotation.getClassNode().getNameWithoutPackage())
                        && TEST_ANNOTATIONS.contains(annotation.getClassNode().getNameWithoutPackage()));
        return new Test(method, false, parameterized, conditions, others, literals(method), List.of());
    }

    /** A Spock block description: a string literal standing alone under a label ({@code given: 'a store'}). */
    static ConstantExpression description(Statement statement) {
        List<String> labels = statement.getStatementLabels();
        if (labels != null && !labels.isEmpty() && statement instanceof ExpressionStatement expression
                && expression.getExpression() instanceof ConstantExpression constant
                && constant.getValue() instanceof String) {
            return constant;
        }
        return null;
    }

    /** An assignment or a declaration: a step, not a condition, even in {@code then:}. */
    private static boolean binding(Expression expression) {
        if (expression instanceof DeclarationExpression declaration) {
            return !conditionCall(declaration.getRightExpression());
        }
        return expression instanceof BinaryExpression binary && binary.getOperation().getType() == Types.ASSIGN;
    }

    /** A call that asserts: {@code assert*}, {@code verify*}, Spock's exception conditions and the like. */
    static boolean conditionCall(Expression expression) {
        String name = expression instanceof MethodCallExpression call ? call.getMethodAsString()
                : expression instanceof StaticMethodCallExpression call ? call.getMethod() : null;
        return name != null && (name.startsWith("assert") || CONDITION_CALLS.contains(name));
    }

    /** The literals of a method's body, by position. */
    static List<ConstantExpression> literals(MethodNode method) {
        Literals visitor = new Literals();
        method.getCode().visit(visitor);
        visitor.found.sort(Comparator.comparingInt(ConstantExpression::getLineNumber)
                .thenComparingInt(ConstantExpression::getColumnNumber));
        return visitor.found;
    }

    /** The offset span of a node, or {@code null} when it has no position. */
    int[] span(ASTNode node) {
        Range range = AstRanges.rangeOf(node);
        return range == null ? null : new int[] {lines.offsetOf(range.start()), lines.offsetOf(range.end())};
    }

    /** The shape of a test's body: its compact text with each literal one placeholder and its descriptions left out. */
    String shape(Test test) {
        return compact(test.method().getCode(), test.literals(), true, test.descriptions());
    }

    /**
     * The text of a node without comments and whitespace, each literal in {@code literals} kept as written or, when
     * {@code mask} is set, replaced by one placeholder, and each of {@code dropped} left out: two bodies of one shape
     * with only their literals changed give the same masked text.
     */
    String compact(ASTNode node, List<ConstantExpression> literals, boolean mask, List<ConstantExpression> dropped) {
        int[] span = span(node);
        if (span == null) {
            return null;
        }
        List<int[]> kept = new ArrayList<>();
        for (ConstantExpression literal : literals) {
            int[] at = span(literal);
            if (at != null && at[0] >= span[0] && at[1] <= span[1]) {
                kept.add(new int[] {at[0], at[1], 0});
            }
        }
        for (ConstantExpression literal : dropped) {
            int[] at = span(literal);
            if (at != null && at[0] >= span[0] && at[1] <= span[1]) {
                kept.add(new int[] {at[0], at[1], 1});
            }
        }
        kept.sort(Comparator.comparingInt(at -> at[0]));
        String text = lines.text();
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (int at = span[0]; at < span[1]; at++) {
            while (next < kept.size() && kept.get(next)[1] <= at) {
                next++;
            }
            if (next < kept.size() && kept.get(next)[0] == at) {
                int[] literal = kept.get(next);
                out.append(literal[2] == 1 ? "" : mask ? "\u0001" : text.substring(literal[0], literal[1]));
                at = literal[1] - 1;
                continue;
            }
            char c = text.charAt(at);
            if (!comments.get(at) && !Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The compact text of a node with its own literals kept: what two tests compare when nothing may differ. */
    String compact(ASTNode node) {
        return compact(node, literalsOf(node), false, List.of());
    }

    private static List<ConstantExpression> literalsOf(ASTNode node) {
        Literals visitor = new Literals();
        node.visit(visitor);
        return visitor.found;
    }

    /** The source text of a literal as written. */
    String text(ConstantExpression literal) {
        int[] at = span(literal);
        return at == null ? null : lines.text().substring(at[0], at[1]);
    }

    /** The place to report a finding about a test: its name, or its first line. */
    Range nameRange(MethodNode method) {
        Range whole = AstRanges.rangeOf(method);
        if (whole == null) {
            return null;
        }
        Range name = AstRanges.nameRange(method, whole, lines);
        if (name != null && !name.equals(whole)) {
            return name;
        }
        return new Range(whole.start(), new Position(whole.start().line(), lines.line(whole.start().line()).length()));
    }

    /** A test's name as a reader writes it: quoted when it is a sentence. */
    static String display(MethodNode method) {
        String name = method.getName();
        return name.matches("[A-Za-z_$][\\w$]*") ? name : "'" + name + "'";
    }

    /** A name split into lower case words: on anything but letters and digits, on case changes, around digits. */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\\d+")
                .matcher(name);
        while (matcher.find()) {
            words.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    /**
     * Collects literals, skipping what is code although the tree holds it as a constant: method and property names,
     * map keys (a named argument's name) and the text parts of a GString.
     */
    static final class Literals extends CodeVisitorSupport {

        final List<ConstantExpression> found = new ArrayList<>();

        @Override
        public void visitConstantExpression(ConstantExpression expression) {
            if (expression.getLineNumber() > 0 && expression.getColumnNumber() > 0) {
                found.add(expression);
            }
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            call.getObjectExpression().visit(this);
            if (!(call.getMethod() instanceof ConstantExpression)) {
                call.getMethod().visit(this);
            }
            call.getArguments().visit(this);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            expression.getObjectExpression().visit(this);
            if (!(expression.getProperty() instanceof ConstantExpression)) {
                expression.getProperty().visit(this);
            }
        }

        @Override
        public void visitAttributeExpression(AttributeExpression expression) {
            visitPropertyExpression(expression);
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression expression) {
            if (!(expression.getKeyExpression() instanceof ConstantExpression)) {
                expression.getKeyExpression().visit(this);
            }
            expression.getValueExpression().visit(this);
        }

        @Override
        public void visitGStringExpression(GStringExpression expression) {
            for (Expression value : expression.getValues()) {
                value.visit(this);
            }
        }

        @Override
        public void visitMethodPointerExpression(MethodPointerExpression expression) {
            expression.getExpression().visit(this);
            if (!(expression.getMethodName() instanceof ConstantExpression)) {
                expression.getMethodName().visit(this);
            }
        }
    }
}
