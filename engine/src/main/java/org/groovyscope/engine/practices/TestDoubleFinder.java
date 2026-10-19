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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.workspace.TestSources;

/**
 * Test doubles that cannot take effect and tests that check nothing, in test sources: a Mockito or Spock stub of a
 * private method (GS013: the real method runs), a metaClass override that {@code @CompileStatic} callers never see
 * (GS014), a metaClass change on a Mockito or Spock double (GS015), and a JUnit test with no assertion or
 * verification (GS016). Spock features are left alone: their {@code then:} and {@code expect:} blocks assert.
 */
public final class TestDoubleFinder {

    /** {@code doReturn(x).when(double).method()}. */
    static final Set<String> MOCKITO_STUBBERS = Set.of("doReturn", "doThrow", "doAnswer", "doNothing",
            "doCallRealMethod");
    /** {@code when(double.method())}, {@code given(double.method())}. */
    static final Set<String> STUB_WRAPPERS = Set.of("when", "given");
    /** Class-based doubles: generated subclasses that cannot intercept a private method. */
    static final Set<String> DOUBLE_FACTORIES = Set.of("spy", "mock", "Spy", "Mock", "Stub");
    static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    static final List<String> ASSERTING_PREFIXES = List.of("assert", "verify", "expect", "should", "fail");
    /** Statically imported helpers that set a test up but check nothing. */
    static final Set<String> NOT_ASSERTING = Set.of("when", "given", "doReturn", "doThrow", "doAnswer", "doNothing",
            "doCallRealMethod", "spy", "mock", "Spy", "Mock", "Stub", "any", "anyString", "anyInt", "anyLong",
            "anyMap", "anyList", "anyBoolean", "eq", "argThat", "same", "isNull", "notNull", "println", "print",
            "printf", "sleep", "reset", "lenient");

    private TestDoubleFinder() {
    }

    /** Findings for one test document; nothing for production code. */
    public static List<PracticeFinder.Practice> find(String uri, ModuleNode module, EngineConfig config) {
        List<PracticeFinder.Practice> out = new ArrayList<>();
        if (module == null || uri.endsWith(".java") || !TestSources.isTestPath(uri)) {
            return out;
        }
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() > 0 && !classNode.getName().contains("$_")) {
                Visitor visitor = new Visitor(uri, module, config, out, classNode);
                visitor.visitClass(classNode);
                if (!visitor.spock) {
                    visitor.checkAssertions();
                }
            }
        }
        return out;
    }

    /** One place a test stubs or overrides a method, by the method's name. */
    public record StubSite(String method, ASTNode node) {
    }

    /**
     * Where a test module stubs methods, in source order: Mockito ({@code doReturn(x).when(d).m()},
     * {@code when(d.m())}, {@code given(d.m())}), Spock ({@code d.m() >> x}) and metaClass overrides
     * ({@code X.metaClass.m = {}}).
     */
    public static List<StubSite> stubSites(ModuleNode module) {
        List<StubSite> sites = new ArrayList<>();
        CodeVisitorSupport collector = new CodeVisitorSupport() {
            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                String stubbed = mockitoStubbed(call);
                if (stubbed != null) {
                    sites.add(new StubSite(stubbed, call));
                }
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitBinaryExpression(BinaryExpression expression) {
                String stubbed = spockStubbed(expression);
                if (stubbed == null) {
                    stubbed = metaClassMember(expression);
                }
                if (stubbed != null) {
                    sites.add(new StubSite(stubbed, expression));
                }
                super.visitBinaryExpression(expression);
            }
        };
        for (ClassNode classNode : module.getClasses()) {
            for (MethodNode method : classNode.getMethods()) {
                if (method.getCode() != null) {
                    method.getCode().visit(collector);
                }
            }
            for (FieldNode field : classNode.getFields()) {
                if (field.getInitialExpression() != null) {
                    field.getInitialExpression().visit(collector);
                }
            }
        }
        return sites;
    }

    /** The stubbed method's call of a Mockito stubbing, or null: {@code doReturn(x).when(d).m()} or {@code when(d.m())}. */
    static MethodCallExpression mockitoCall(MethodCallExpression call) {
        String name = call.getMethodAsString();
        if (name != null && call.getObjectExpression() instanceof MethodCallExpression when
                && "when".equals(when.getMethodAsString())
                && when.getObjectExpression() instanceof MethodCallExpression stubber
                && MOCKITO_STUBBERS.contains(stubber.getMethodAsString())
                && arguments(when.getArguments()).size() == 1) {
            return call;
        }
        if (name != null && STUB_WRAPPERS.contains(name) && arguments(call.getArguments()).size() == 1
                && arguments(call.getArguments()).get(0) instanceof MethodCallExpression stubbed
                && !stubbed.isImplicitThis() && stubbed.getMethodAsString() != null) {
            return stubbed;
        }
        return null;
    }

    static String mockitoStubbed(MethodCallExpression call) {
        MethodCallExpression stubbed = mockitoCall(call);
        return stubbed == null ? null : stubbed.getMethodAsString();
    }

    /** The stubbed call of a Spock interaction ({@code d.m() >> x}, {@code 1 * d.m() >> x}), or null. */
    static MethodCallExpression spockCall(BinaryExpression expression) {
        int operation = expression.getOperation().getType();
        if (operation != Types.RIGHT_SHIFT && operation != Types.RIGHT_SHIFT_UNSIGNED) {
            return null;
        }
        Expression left = expression.getLeftExpression();
        if (left instanceof BinaryExpression counted && counted.getOperation().getType() == Types.MULTIPLY) {
            left = counted.getRightExpression();
        }
        return left instanceof MethodCallExpression stubbed && !stubbed.isImplicitThis()
                && stubbed.getMethodAsString() != null ? stubbed : null;
    }

    static String spockStubbed(BinaryExpression expression) {
        MethodCallExpression stubbed = spockCall(expression);
        return stubbed == null ? null : stubbed.getMethodAsString();
    }

    /** {@code X.metaClass.m = ...}: the member's property expression, or null. */
    static PropertyExpression metaClassAssignment(BinaryExpression expression) {
        return expression.getOperation().getType() == Types.ASSIGN
                && expression.getLeftExpression() instanceof PropertyExpression member
                && member.getObjectExpression() instanceof PropertyExpression metaClass
                && "metaClass".equals(metaClass.getPropertyAsString()) && member.getPropertyAsString() != null
                ? member : null;
    }

    static String metaClassMember(BinaryExpression expression) {
        PropertyExpression member = metaClassAssignment(expression);
        return member == null ? null : member.getPropertyAsString();
    }

    /** Methods called {@code name} on {@code type} and its superclasses (empty when the type is not known). */
    static List<MethodNode> methodsNamed(ClassNode type, String name) {
        List<MethodNode> found = new ArrayList<>();
        Set<ClassNode> seen = new HashSet<>();
        for (ClassNode current = type == null ? null : type.redirect();
                current != null && !ClassHelper.isObjectType(current) && seen.add(current);
                current = current.getSuperClass() == null ? null : current.getSuperClass().redirect()) {
            found.addAll(current.getMethods(name));
        }
        return found;
    }

    static boolean isPrivate(List<MethodNode> methods) {
        return !methods.isEmpty() && methods.stream().allMatch(MethodNode::isPrivate);
    }

    static boolean compileStatic(MethodNode method) {
        return annotated(method, "CompileStatic") || annotated(method.getDeclaringClass(), "CompileStatic");
    }

    static boolean annotated(AnnotatedNode node, String simpleName) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            if (annotation.getClassNode().getNameWithoutPackage().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    static boolean asserting(String name) {
        return name.equals("then") || ASSERTING_PREFIXES.stream().anyMatch(name::startsWith);
    }

    static List<Expression> arguments(Expression arguments) {
        return arguments instanceof TupleExpression tuple ? tuple.getExpressions() : List.of();
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final String uri;
        private final ModuleNode module;
        private final EngineConfig config;
        private final List<PracticeFinder.Practice> out;
        private final ClassNode classNode;
        private final boolean spock;
        /** What each variable or field was last given, by name: where a {@code def} double's type comes from. */
        private final Map<String, Expression> values = new HashMap<>();

        Visitor(String uri, ModuleNode module, EngineConfig config, List<PracticeFinder.Practice> out,
                ClassNode classNode) {
            this.uri = uri;
            this.module = module;
            this.config = config;
            this.out = out;
            this.classNode = classNode;
            this.spock = isSpecification(classNode);
            collectValues();
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return module.getContext();
        }

        private static boolean isSpecification(ClassNode classNode) {
            for (ClassNode parent = classNode.getUnresolvedSuperClass(false); parent != null;
                    parent = parent.getUnresolvedSuperClass(false)) {
                if (parent.getName().endsWith("Specification")) {
                    return true;
                }
            }
            return false;
        }

        private void collectValues() {
            for (FieldNode field : classNode.getFields()) {
                if (field.getInitialExpression() != null) {
                    values.put(field.getName(), field.getInitialExpression());
                }
            }
            CodeVisitorSupport collector = new CodeVisitorSupport() {
                @Override
                public void visitBinaryExpression(BinaryExpression expression) {
                    if (expression.getOperation().getType() == Types.ASSIGN
                            && expression.getLeftExpression() instanceof VariableExpression target) {
                        values.put(target.getName(), expression.getRightExpression());
                    } else if (expression instanceof DeclarationExpression declaration
                            && declaration.getLeftExpression() instanceof VariableExpression target) {
                        values.put(target.getName(), declaration.getRightExpression());
                    }
                    super.visitBinaryExpression(expression);
                }
            };
            for (MethodNode method : classNode.getMethods()) {
                if (method.getCode() != null) {
                    method.getCode().visit(collector);
                }
            }
        }

        /** The class a receiver is an instance of: its declared type, else what created it. */
        private ClassNode typeOf(Expression receiver) {
            String name = null;
            ClassNode declared = null;
            if (receiver instanceof VariableExpression variable) {
                name = variable.getName();
                Variable accessed = variable.getAccessedVariable();
                declared = accessed != null ? accessed.getOriginType() : variable.getOriginType();
            } else if (receiver instanceof FieldExpression field) {
                name = field.getFieldName();
                declared = field.getField().getOriginType();
            } else if (receiver instanceof PropertyExpression property && (property.isImplicitThis()
                    || property.getObjectExpression() instanceof VariableExpression self && self.isThisExpression())) {
                name = property.getPropertyAsString();
            } else if (receiver instanceof ClassExpression type) {
                return type.getType();
            }
            if (declared != null && !ClassHelper.isObjectType(declared) && !ClassHelper.isDynamicTyped(declared)) {
                return declared;
            }
            return name == null ? null : created(values.get(name));
        }

        /** The class {@code spy(new X())}, {@code mock(X)}, {@code Spy(X)} or {@code new X()} makes. */
        private static ClassNode created(Expression value) {
            if (value instanceof ConstructorCallExpression constructor) {
                return constructor.getType();
            }
            String factory = value instanceof MethodCallExpression call ? call.getMethodAsString()
                    : value instanceof StaticMethodCallExpression call ? call.getMethod() : null;
            Expression arguments = value instanceof MethodCallExpression call ? call.getArguments()
                    : value instanceof StaticMethodCallExpression call ? call.getArguments() : null;
            List<Expression> args = arguments(arguments);
            if (factory == null || !DOUBLE_FACTORIES.contains(factory) || args.isEmpty()) {
                return null;
            }
            Expression first = args.get(0);
            if (first instanceof ClassExpression type) {
                return type.getType();
            }
            if (first instanceof PropertyExpression property && "class".equals(property.getPropertyAsString())
                    && property.getObjectExpression() instanceof ClassExpression type) {
                return type.getType();
            }
            return first instanceof ConstructorCallExpression constructor ? constructor.getType() : null;
        }

        private boolean isDouble(Expression receiver) {
            String name = receiver instanceof VariableExpression variable ? variable.getName()
                    : receiver instanceof PropertyExpression property ? property.getPropertyAsString() : null;
            Expression value = name == null ? null : values.get(name);
            String factory = value instanceof MethodCallExpression call ? call.getMethodAsString()
                    : value instanceof StaticMethodCallExpression call ? call.getMethod() : null;
            return factory != null && DOUBLE_FACTORIES.contains(factory);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            MethodCallExpression stubbed = mockitoCall(call);
            if (stubbed == call) {
                MethodCallExpression when = (MethodCallExpression) call.getObjectExpression();
                checkStub(call, arguments(when.getArguments()).get(0), call.getMethodAsString(), "Mockito");
            } else if (stubbed != null) {
                checkStub(stubbed, stubbed.getObjectExpression(), stubbed.getMethodAsString(), "Mockito");
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            MethodCallExpression stubbed = spock ? spockCall(expression) : null;
            if (stubbed != null) {
                checkStub(stubbed, stubbed.getObjectExpression(), stubbed.getMethodAsString(), "Spock");
            }
            PropertyExpression member = metaClassAssignment(expression);
            if (member != null) {
                checkMetaClass(member, ((PropertyExpression) member.getObjectExpression()).getObjectExpression(),
                        member.getPropertyAsString());
            }
            super.visitBinaryExpression(expression);
        }

        private void checkStub(ASTNode where, Expression receiver, String method, String library) {
            ClassNode type = typeOf(receiver);
            if (type != null && isPrivate(methodsNamed(type, method))) {
                report("GS013", where, type.getNameWithoutPackage() + "." + method + " is private: a " + library
                        + " double cannot intercept it, so the real method runs"
                        + ("Mockito".equals(library) ? " and the stubbing's matchers leak into the next one" : "")
                        + "; stub a package-private or public seam, or let the real method run");
            }
        }

        private void checkMetaClass(ASTNode where, Expression owner, String method) {
            if (isDouble(owner)) {
                report("GS015", where, "metaClass on a Mockito or Spock double: the double intercepts calls the "
                        + "metaClass never sees, and the reverse; stub " + method + " through the double instead");
                return;
            }
            ClassNode type = typeOf(owner);
            List<MethodNode> methods = methodsNamed(type, method);
            if (!methods.isEmpty() && methods.stream().allMatch(TestDoubleFinder::compileStatic)) {
                report("GS014", where, type.getNameWithoutPackage() + " is @CompileStatic: its own calls to " + method
                        + " are bound at compile time and never see this override; stub a seam with Mockito, "
                        + "or pass the collaborator in");
            }
        }

        /** GS016: JUnit tests that neither assert, verify nor expect an exception, directly or through helpers. */
        void checkAssertions() {
            Map<String, Boolean> asserts = new HashMap<>();
            Set<String> local = new HashSet<>();
            for (MethodNode method : classNode.getMethods()) {
                local.add(method.getName());
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (MethodNode method : classNode.getMethods()) {
                    if (!asserts.getOrDefault(method.getName(), false) && checks(method, asserts, local)) {
                        asserts.put(method.getName(), true);
                        changed = true;
                    }
                }
            }
            for (MethodNode method : classNode.getMethods()) {
                AnnotationNode test = testAnnotation(method);
                if (test != null && test.getMember("expected") == null
                        && !asserts.getOrDefault(method.getName(), false)) {
                    report("GS016", method, method.getName() + " neither asserts, verifies nor expects an exception: "
                            + "it passes whatever the code does");
                }
            }
        }

        private static AnnotationNode testAnnotation(MethodNode method) {
            for (AnnotationNode annotation : method.getAnnotations()) {
                if (TEST_ANNOTATIONS.contains(annotation.getClassNode().getNameWithoutPackage())) {
                    return annotation;
                }
            }
            return null;
        }

        /** Whether a method asserts: an assert or throw, an asserting call, or a call to a helper that does. */
        private static boolean checks(MethodNode method, Map<String, Boolean> asserts, Set<String> local) {
            if (method.getCode() == null) {
                return false;
            }
            boolean[] found = {false};
            method.getCode().visit(new CodeVisitorSupport() {
                @Override
                public void visitAssertStatement(AssertStatement statement) {
                    found[0] = true;
                }

                @Override
                public void visitThrowStatement(ThrowStatement statement) {
                    found[0] = true;
                }

                @Override
                public void visitMethodCallExpression(MethodCallExpression call) {
                    String name = call.getMethodAsString();
                    if (name != null && (asserting(name) || call.isImplicitThis() && (local.contains(name)
                            ? asserts.getOrDefault(name, false) : !NOT_ASSERTING.contains(name)))) {
                        found[0] = true;
                    }
                    super.visitMethodCallExpression(call);
                }

                @Override
                public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                    String name = call.getMethod();
                    if (asserting(name) || (local.contains(name) && asserts.getOrDefault(name, false))) {
                        found[0] = true;
                    }
                    super.visitStaticMethodCallExpression(call);
                }
            });
            return found[0];
        }

        void report(String code, ASTNode node, String message) {
            Diagnostic.Severity severity = PracticeFinder.severity(code, config);
            Range range = AstRanges.rangeOf(node);
            if (severity == null || range == null) {
                return;
            }
            String title = PracticeCatalog.bundled().find(code).map(PracticeCatalog.Entry::title).orElse(code);
            out.add(new PracticeFinder.Practice(new Diagnostic(uri, range, severity, PracticeFinder.SOURCE, code,
                    title + ": " + message), null, List.<TextEdit>of()));
        }
    }
}
