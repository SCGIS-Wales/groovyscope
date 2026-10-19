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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.Members;
import org.groovyscope.engine.workspace.TestSources;

/**
 * groovyscope's own practice diagnostics, GS001 to GS012 (docs/practices.md, section 16); GS013 to GS016 come from
 * {@link TestDoubleFinder} and GS020 to GS027 from {@link NamingFinder}. Each one reports only
 * when the tree says so for certain (a statically known type, a literal); untyped code is left alone rather than
 * guessed about. Where a mechanical fix is safe, the finding carries it as text edits.
 */
public final class PracticeFinder {

    /** Reported as the diagnostic source. */
    public static final String SOURCE = "groovyscope";

    /** Another way to fix a finding, offered as a code action next to the first (never applied in bulk). */
    public record Alternative(String title, List<TextEdit> edits) {
        public Alternative {
            edits = List.copyOf(edits);
        }
    }

    /** A finding with an optional fix, and other fixes when the intent is not clear from the code. */
    public record Practice(Diagnostic diagnostic, String fixTitle, List<TextEdit> fix, List<Alternative> alternatives) {
        public Practice {
            fix = List.copyOf(fix);
            alternatives = List.copyOf(alternatives);
        }

        public Practice(Diagnostic diagnostic, String fixTitle, List<TextEdit> fix) {
            this(diagnostic, fixTitle, fix, List.of());
        }

        public boolean fixable() {
            return !fix.isEmpty();
        }

        /** Whether the fix keeps behaviour, so the bulk fix may apply it (see {@link PracticeCatalog#safety}). */
        public boolean preserving() {
            return PracticeCatalog.PRESERVING.equals(PracticeCatalog.bundled().safety(diagnostic.code()));
        }
    }

    static final Set<String> INTEGRAL = Set.of("int", "long", "short", "byte", "java.lang.Integer", "java.lang.Long",
            "java.lang.Short", "java.lang.Byte", "java.math.BigInteger");
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
    static final Set<String> PRIMITIVE_DEFAULTS = Set.of("int", "long", "short", "byte", "double", "float", "boolean",
            "char");
    static final Set<String> FILE_TYPES = Set.of("java.io.File", "java.nio.file.Path");
    static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "error");
    static final Set<String> LOGGER_TYPES = Set.of("org.slf4j.Logger", "java.util.logging.Logger",
            "org.apache.logging.log4j.Logger", "org.apache.commons.logging.Log");
    static final Set<String> LOGGER_NAMES = Set.of("log", "logger", "LOG", "LOGGER");
    static final Set<String> SQL_METHODS = Set.of("rows", "eachRow", "firstRow", "execute", "executeUpdate",
            "executeInsert", "query", "call");
    static final Set<String> EVAL_METHODS = Set.of("me", "x", "xy", "xyz");
    static final Set<String> SHELL_METHODS = Set.of("evaluate", "parse", "run", "parseClass");
    static final Set<String> SHELL_TYPES = Set.of("groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader");
    static final Set<String> XML_PARSERS = Set.of("groovy.xml.XmlSlurper", "groovy.xml.XmlParser",
            "groovy.util.XmlSlurper", "groovy.util.XmlParser");
    static final Pattern QUOTED_INTERPOLATION = Pattern.compile("'(\\$\\{[^}]*\\}|\\$[A-Za-z_][\\w.]*)'");

    private PracticeFinder() {
    }

    /** Practice findings for one Groovy document. */
    public static List<Practice> find(String uri, ModuleNode module, String text, EngineConfig config) {
        List<Practice> out = new ArrayList<>();
        if (module == null || text == null || !config.practices().enabled() || uri.endsWith(".java")) {
            return out;
        }
        Visitor visitor = new Visitor(uri, module, new TextLines(text), config, out);
        visitor.visitModule();
        out.addAll(NamingFinder.find(uri, module, config));
        out.addAll(TestDoubleFinder.find(uri, module, config));
        return out;
    }

    /** Severity for a code: the configured override, else the catalogue default; {@code null} when switched off. */
    public static Severity severity(String code, EngineConfig config) {
        String configured = config.practices().severityOf(code);
        String value = configured != null ? configured
                : PracticeCatalog.bundled().find(code).map(PracticeCatalog.Entry::severity).orElse("warning");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "off" -> null;
            case "error" -> Severity.ERROR;
            case "info", "information" -> Severity.INFORMATION;
            default -> Severity.WARNING;
        };
    }

    /** The statically known type of an expression, or {@code null} when it is dynamic or unknown. */
    public static ClassNode typeOf(Expression expression) {
        if (expression instanceof ConstantExpression constant) {
            return constant.getValue() == null ? null : constant.getType();
        }
        if (expression instanceof GStringExpression) {
            return ClassHelper.GSTRING_TYPE;
        }
        if (expression instanceof CastExpression cast) {
            return cast.getType();
        }
        if (expression instanceof ConstructorCallExpression constructor) {
            return constructor.getType();
        }
        if (expression instanceof FieldExpression field) {
            return field.getField().isDynamicTyped() ? null : field.getField().getOriginType();
        }
        if (expression instanceof VariableExpression variable) {
            if (variable.isThisExpression() || variable.isSuperExpression()) {
                return null;
            }
            Variable accessed = variable.getAccessedVariable();
            if (accessed == null || accessed.isDynamicTyped()) {
                return null;
            }
            return accessed.getOriginType();
        }
        if (expression instanceof PropertyExpression property && property.getPropertyAsString() != null) {
            ClassNode owner = ownerType(property.getObjectExpression());
            return owner == null ? null : memberType(Members.membersNamed(owner, property.getPropertyAsString()));
        }
        if (expression instanceof MethodCallExpression call && call.getMethodAsString() != null) {
            ClassNode owner = ownerType(call.getObjectExpression());
            return owner == null ? null : returnType(Members.accepting(Members.methodsNamed(owner,
                    call.getMethodAsString()), Members.argumentCount(call.getArguments())));
        }
        if (expression instanceof StaticMethodCallExpression call) {
            return returnType(Members.accepting(Members.methodsNamed(call.getOwnerType(), call.getMethod()),
                    Members.argumentCount(call.getArguments())));
        }
        return null;
    }

    private static ClassNode ownerType(Expression receiver) {
        if (receiver instanceof ClassExpression classExpression) {
            return classExpression.getType();
        }
        ClassNode type = typeOf(receiver);
        return type == null || ClassHelper.isObjectType(type) ? null : type;
    }

    private static ClassNode memberType(List<ASTNode> members) {
        if (members.size() != 1) {
            return null;
        }
        ASTNode member = members.get(0);
        if (member instanceof PropertyNode property) {
            return property.getField().isDynamicTyped() ? null : property.getOriginType();
        }
        if (member instanceof FieldNode field) {
            return field.isDynamicTyped() ? null : field.getOriginType();
        }
        if (member instanceof MethodNode method) {
            return method.isDynamicReturnType() ? null : method.getReturnType();
        }
        return null;
    }

    private static ClassNode returnType(List<MethodNode> methods) {
        ClassNode type = null;
        for (MethodNode method : methods) {
            if (method.isDynamicReturnType()) {
                return null;
            }
            if (type == null) {
                type = method.getReturnType();
            } else if (!type.getName().equals(method.getReturnType().getName())) {
                return null;
            }
        }
        return type;
    }

    static boolean named(ClassNode type, Set<String> names) {
        return type != null && names.contains(type.getName());
    }

    static boolean isTrue(Expression expression) {
        return expression instanceof ConstantExpression constant && Boolean.TRUE.equals(constant.getValue());
    }

    static boolean isFalse(Expression expression) {
        return expression instanceof ConstantExpression constant && Boolean.FALSE.equals(constant.getValue());
    }

    static boolean simple(Expression expression) {
        return expression instanceof VariableExpression || expression instanceof ConstantExpression
                || expression instanceof PropertyExpression || expression instanceof FieldExpression;
    }

    /**
     * A default that is the very empty value ?: would replace ({@code name ?: ''}, {@code count ?: 0},
     * {@code items ?: []}, {@code options ?: [:]}): an empty value comes back unchanged, so only null is
     * defaulted and nothing valid is lost.
     */
    static boolean sameEmpty(ClassNode type, Expression fallback) {
        String name = type.getName();
        boolean map = name.contains("Map");
        boolean collection = map || name.contains("List") || name.contains("Collection") || name.contains("Set");
        if (fallback instanceof ConstantExpression constant) {
            Object value = constant.getValue();
            if (name.contains("String") || name.contains("CharSequence")) {
                return "".equals(value);
            }
            return !collection && value instanceof Number number && number.doubleValue() == 0;
        }
        if (fallback instanceof ListExpression list) {
            return collection && !map && list.getExpressions().isEmpty();
        }
        return fallback instanceof MapExpression entries && map && entries.getMapEntryExpressions().isEmpty();
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final String uri;
        private final ModuleNode module;
        private final TextLines lines;
        private final EngineConfig config;
        private final List<Practice> out;
        private final boolean production;
        /** Elvis expressions passed straight to an empty-rejecting parser (identity, not equality). */
        private final Set<Expression> parsed = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Divisions whose result is narrowed to an integral type (truncated anyway): not GS001 findings. */
        private final Set<Expression> narrowed = Collections.newSetFromMap(new IdentityHashMap<>());
        private MethodNode currentMethod;
        private boolean inSpock;
        private String block = "";

        Visitor(String uri, ModuleNode module, TextLines lines, EngineConfig config, List<Practice> out) {
            this.uri = uri;
            this.module = module;
            this.lines = lines;
            this.config = config;
            this.out = out;
            this.production = !uri.contains("/src/test/") && !uri.contains("/test/groovy/")
                    && !TestSources.isTestPath(uri);
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return module.getContext();
        }

        void visitModule() {
            for (ImportNode importNode : module.getImports()) {
                visitImport(importNode);
            }
            for (ImportNode importNode : module.getStarImports()) {
                visitImport(importNode);
            }
            for (ClassNode classNode : module.getClasses()) {
                inSpock = isSpecification(classNode);
                visitClass(classNode);
            }
        }

        private void visitImport(ImportNode importNode) {
            visitAnnotations(importNode);
            String name = importNode.getClassName() != null ? importNode.getClassName() : importNode.getPackageName();
            if (name != null && name.startsWith("groovy.typecheckers") && config.groovy().mustCompileOnGroovy5()) {
                report("GS010", importNode, "groovy.typecheckers is Groovy 6 only, but groovy.compat includes 5.x",
                        null, List.of());
            }
        }

        private static boolean isSpecification(ClassNode classNode) {
            ClassNode parent = classNode.getUnresolvedSuperClass(false);
            while (parent != null) {
                if (parent.getName().endsWith("Specification")) {
                    return true;
                }
                parent = parent.getUnresolvedSuperClass(false);
            }
            return false;
        }

        void report(String code, ASTNode node, String message, String fixTitle, List<TextEdit> fix) {
            report(code, node, message, fixTitle, fix, List.of());
        }

        void report(String code, ASTNode node, String message, String fixTitle, List<TextEdit> fix,
                List<Alternative> alternatives) {
            Severity severity = severity(code, config);
            Range range = AstRanges.rangeOf(node);
            if (severity == null || range == null) {
                return;
            }
            String title = PracticeCatalog.bundled().find(code).map(PracticeCatalog.Entry::title).orElse(code);
            out.add(new Practice(new Diagnostic(uri, range, severity, SOURCE, code, title + ": " + message),
                    fixTitle, fix, alternatives));
        }

        String source(ASTNode node) {
            Range range = AstRanges.rangeOf(node);
            return range == null ? null : lines.slice(range);
        }

        TextEdit replace(ASTNode node, String text) {
            return new TextEdit(AstRanges.rangeOf(node), text);
        }

        @Override
        public void visitAnnotations(AnnotatedNode node) {
            for (AnnotationNode annotation : node.getAnnotations()) {
                String name = annotation.getClassNode().getNameWithoutPackage();
                if (production && Set.of("Grab", "Grapes", "GrabResolver", "GrabConfig", "GrabExclude").contains(name)) {
                    // No automatic fix: removing the annotation leaves code that no longer compiles.
                    String coordinates = grabCoordinates(annotation);
                    report("GS007", annotation, "@" + name + " resolves dependencies at compile time"
                            + (coordinates == null ? "; declare the dependency in the build instead"
                                    : "; declare implementation '" + coordinates + "' in the build instead"), null,
                            List.of());
                }
                if (name.equals("SafeRegex") && config.groovy().mustCompileOnGroovy5()) {
                    report("GS010", annotation, "@SafeRegex is Groovy 6 only, but groovy.compat includes 5.x", null,
                            List.of());
                }
                Expression extensions = annotation.getMember("extensions");
                if (extensions != null && extensions.getText().contains("groovy.typecheckers")
                        && config.groovy().mustCompileOnGroovy5()) {
                    report("GS010", annotation, "groovy.typecheckers extensions are Groovy 6 only", null, List.of());
                }
            }
            super.visitAnnotations(node);
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

        @Override
        public void visitBlockStatement(BlockStatement block) {
            String saved = this.block;
            for (Statement statement : block.getStatements()) {
                List<String> labels = statement.getStatementLabels();
                if (inSpock && labels != null && !labels.isEmpty() && !labels.contains("and")) {
                    this.block = labels.get(0);
                }
                statement.visit(this);
            }
            this.block = saved;
        }

        @Override
        public void visitExpressionStatement(ExpressionStatement statement) {
            Expression expression = statement.getExpression();
            if (inSpock && ("then".equals(block) || "expect".equals(block))
                    && expression instanceof MethodCallExpression call && "with".equals(call.getMethodAsString())
                    && !call.isImplicitThis() && call.getArguments() instanceof TupleExpression args
                    && args.getExpressions().size() == 1 && args.getExpression(0) instanceof ClosureExpression closure) {
                String receiver = source(call.getObjectExpression());
                String closureText = source(closure);
                List<TextEdit> fix = receiver == null || closureText == null ? List.of()
                        : List.of(replace(call, "with(" + receiver + ") " + closureText));
                report("GS011", call, "the assertions inside .with {} are not checked since Spock 2.4",
                        "Use with(" + (receiver == null ? "obj" : receiver) + ") {}", fix);
            }
            super.visitExpressionStatement(statement);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            int op = expression.getOperation().getType();
            if (op == Types.ASSIGN && named(typeOf(expression.getLeftExpression()), INTEGRAL)) {
                narrowed.add(expression.getRightExpression());
            }
            if (op == Types.DIVIDE && !narrowed.contains(expression)
                    && named(typeOf(expression.getLeftExpression()), INTEGRAL)
                    && named(typeOf(expression.getRightExpression()), INTEGRAL)) {
                String left = source(expression.getLeftExpression());
                String right = source(expression.getRightExpression());
                List<TextEdit> fix = left == null || right == null ? List.of()
                        : List.of(replace(expression, wrap(expression.getLeftExpression(), left) + ".intdiv(" + right + ")"));
                report("GS001", expression, "both operands are integral, so / yields a BigDecimal (a fraction); intdiv "
                        + "truncates, which changes the value, so only where truncation is meant", "Use intdiv", fix);
            }
            if (op == Types.ASSIGN && !(expression instanceof DeclarationExpression)) {
                checkPrimitiveSafeNavigation(expression.getLeftExpression(), expression.getRightExpression());
                checkDocType(expression);
            }
            super.visitBinaryExpression(expression);
        }

        private static String wrap(Expression expression, String text) {
            return simple(expression) || expression instanceof MethodCallExpression ? text : "(" + text + ")";
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            if (!expression.isMultipleAssignmentDeclaration() && !expression.getVariableExpression().isDynamicTyped()
                    && named(expression.getVariableExpression().getOriginType(), INTEGRAL)) {
                narrowed.add(expression.getRightExpression());
            }
            if (!expression.isMultipleAssignmentDeclaration()) {
                checkPrimitiveSafeNavigation(expression.getVariableExpression(), expression.getRightExpression());
                String text = source(expression);
                if (text != null && text.stripLeading().startsWith("val ") && config.groovy().mustCompileOnGroovy5()) {
                    report("GS010", expression, "val is Groovy 6 only, but groovy.compat includes 5.x", null, List.of());
                }
            }
            super.visitDeclarationExpression(expression);
        }

        private void checkPrimitiveSafeNavigation(Expression target, Expression value) {
            ClassNode type = target instanceof VariableExpression variable && !variable.isDynamicTyped()
                    ? variable.getOriginType() : typeOf(target);
            boolean safe = value instanceof MethodCallExpression call && call.isSafe()
                    || value instanceof PropertyExpression property && property.isSafe();
            if (type != null && PRIMITIVE_DEFAULTS.contains(type.getName()) && safe) {
                String text = source(value);
                String fallback = "boolean".equals(type.getName()) ? "false" : "0";
                List<TextEdit> fix = text == null ? List.of() : List.of(replace(value, text + " ?: " + fallback));
                report("GS003", value, "null when the receiver is null, which a " + type.getName() + " cannot hold",
                        "Default with ?: " + fallback, fix);
            }
        }

        @Override
        public void visitCastExpression(CastExpression expression) {
            if (named(expression.getType(), INTEGRAL)) {
                narrowed.add(expression.getExpression());
            }
            super.visitCastExpression(expression);
        }

        @Override
        public void visitReturnStatement(ReturnStatement statement) {
            if (currentMethod != null && named(currentMethod.getReturnType(), INTEGRAL)) {
                narrowed.add(statement.getExpression());
            }
            super.visitReturnStatement(statement);
        }

        @Override
        protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            MethodNode saved = currentMethod;
            currentMethod = node;
            super.visitConstructorOrMethod(node, isConstructor);
            currentMethod = saved;
        }

        @Override
        public void visitTernaryExpression(TernaryExpression expression) {
            if (expression instanceof ElvisOperatorExpression elvis) {
                Expression tested = elvis.getTrueExpression();
                ClassNode type = typeOf(tested);
                if (named(type, FILE_TYPES)) {
                    report("GS004", tested, "a File or Path is truthy only if it exists (Groovy 5)", null, List.of());
                } else if (named(type, ELVIS_SENSITIVE) && !sameEmpty(type, elvis.getFalseExpression())
                        && !parsed.contains(elvis)) {
                    String testedText = source(tested);
                    String fallback = source(elvis.getFalseExpression());
                    boolean fixable = testedText != null && fallback != null && tested instanceof VariableExpression
                            && !ClassHelper.isPrimitiveType(type);
                    List<TextEdit> fix = fixable
                            ? List.of(replace(elvis, testedText + " != null ? " + testedText + " : " + fallback))
                            : List.of();
                    report("GS002", elvis, "?: also replaces " + emptyValue(type) + ", which is a valid "
                            + type.getNameWithoutPackage() + "; a defect only where that empty value is a real input, "
                            + "distinct from absent", "Compare with null", fix);
                }
            } else {
                checkTruthiness(expression.getBooleanExpression());
            }
            super.visitTernaryExpression(expression);
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

        @Override
        public void visitIfElse(IfStatement statement) {
            checkTruthiness(statement.getBooleanExpression());
            super.visitIfElse(statement);
        }

        @Override
        public void visitWhileLoop(WhileStatement statement) {
            checkTruthiness(statement.getBooleanExpression());
            super.visitWhileLoop(statement);
        }

        private void checkTruthiness(BooleanExpression condition) {
            Expression tested = condition.getExpression();
            boolean negated = false;
            if (tested instanceof NotExpression not) {
                tested = not.getExpression();
                negated = true;
            }
            if (named(typeOf(tested), FILE_TYPES)) {
                String text = source(tested);
                Expression whole = condition.getExpression();
                List<TextEdit> fix = text == null ? List.of()
                        : List.of(replace(whole, text + (negated ? " == null" : " != null")));
                boolean path = "java.nio.file.Path".equals(typeOf(tested).getName());
                String exists = path ? "java.nio.file.Files.exists(" + text + ")" : wrap(tested, text) + ".exists()";
                List<Alternative> alternatives = text == null ? List.of()
                        : List.of(new Alternative(negated ? "Test that it does not exist" : "Test that it exists",
                                List.of(replace(whole, (negated ? "!" : "") + exists))));
                report("GS004", whole, "a File or Path is truthy only if it exists (Groovy 5); say whether you mean "
                        + "present (!= null) or existing (exists())",
                        negated ? "Compare with == null" : "Compare with != null", fix, alternatives);
            }
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            if (name != null && EMPTY_REJECTING_PARSERS.contains(name)) {
                parserArguments(call.getArguments());
            }
            if (name != null) {
                checkLogging(call, name);
                checkEvaluation(call, name);
                checkExecute(call, name);
                checkSql(call, name);
                checkXmlFeature(call, name);
                checkCopyWith(call, name);
            }
            super.visitMethodCallExpression(call);
        }

        /** Remembers the Elvis expressions passed directly as arguments to an empty-rejecting parser. */
        private void parserArguments(Expression arguments) {
            if (arguments instanceof TupleExpression tuple) {
                for (Expression argument : tuple.getExpressions()) {
                    if (argument instanceof ElvisOperatorExpression elvis) {
                        parsed.add(elvis);
                    }
                }
            }
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            if (EMPTY_REJECTING_PARSERS.contains(call.getMethod())) {
                parserArguments(call.getArguments());
            }
            if (call.getOwnerType().getName().equals("groovy.util.Eval") && EVAL_METHODS.contains(call.getMethod())
                    && firstArgumentIsNotConstant(call.getArguments())) {
                report("GS006", call, "Eval." + call.getMethod() + " runs its argument as code", null, List.of());
            }
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            if (named(call.getType(), XML_PARSERS) && call.getArguments() instanceof TupleExpression args) {
                List<Expression> values = args.getExpressions();
                if (values.size() >= 3 && isTrue(values.get(2))) {
                    report("GS009", call, "the third argument (allowDocTypeDeclaration) enables DOCTYPE", null,
                            List.of());
                }
                for (Expression value : values) {
                    if (value instanceof MapExpression map) {
                        checkDocTypeEntries(map, call);
                    }
                }
            }
            super.visitConstructorCallExpression(call);
        }

        private void checkLogging(MethodCallExpression call, String name) {
            if (!LOG_METHODS.contains(name) || !(call.getArguments() instanceof TupleExpression args)
                    || args.getExpressions().isEmpty() || !(args.getExpression(0) instanceof GStringExpression gstring)) {
                return;
            }
            Expression receiver = call.getObjectExpression();
            boolean logger = named(typeOf(receiver), LOGGER_TYPES)
                    || receiver instanceof VariableExpression variable && LOGGER_NAMES.contains(variable.getName());
            if (!logger) {
                return;
            }
            StringBuilder message = new StringBuilder("'");
            List<String> arguments = new ArrayList<>();
            boolean fixable = args.getExpressions().size() == 1;
            for (int i = 0; i < gstring.getStrings().size(); i++) {
                Object part = gstring.getStrings().get(i).getValue();
                String literal = part == null ? "" : part.toString();
                if (literal.contains("'") || literal.contains("\\") || literal.contains("{}") || literal.contains("\n")) {
                    fixable = false;
                }
                message.append(literal);
                if (i < gstring.getValues().size()) {
                    String value = source(gstring.getValue(i));
                    if (value == null) {
                        fixable = false;
                    }
                    arguments.add(value);
                    message.append("{}");
                }
            }
            message.append("'");
            List<TextEdit> fix = fixable
                    ? List.of(replace(gstring, message + (arguments.isEmpty() ? "" : ", " + String.join(", ", arguments))))
                    : List.of();
            report("GS005", gstring, "the GString is built even when " + name + " is disabled",
                    "Use {} placeholders", fix);
        }

        private void checkEvaluation(MethodCallExpression call, String name) {
            Expression receiver = call.getObjectExpression();
            boolean eval = receiver instanceof ClassExpression type && type.getType().getName().equals("groovy.util.Eval")
                    && EVAL_METHODS.contains(name);
            boolean shell = SHELL_METHODS.contains(name) && named(typeOf(receiver), SHELL_TYPES);
            if ((eval || shell) && firstArgumentIsNotConstant(call.getArguments())) {
                report("GS006", call, name + " compiles and runs a value that is not a constant", null, List.of());
            }
        }

        private static boolean firstArgumentIsNotConstant(Expression arguments) {
            return arguments instanceof TupleExpression args && !args.getExpressions().isEmpty()
                    && !(args.getExpression(0) instanceof ConstantExpression);
        }

        private void checkExecute(MethodCallExpression call, String name) {
            if (!"execute".equals(name)) {
                return;
            }
            Expression receiver = call.getObjectExpression();
            boolean literal = receiver instanceof ConstantExpression constant && constant.getValue() instanceof String;
            boolean stringTyped = receiver instanceof GStringExpression
                    || named(typeOf(receiver), Set.of("java.lang.String", "groovy.lang.GString"));
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
                    fix = List.of(replace(receiver, list.append(']').toString()));
                }
            }
            report("GS008", call, "the command string is split on whitespace", "Use the list form", fix);
        }

        private void checkSql(MethodCallExpression call, String name) {
            if (!SQL_METHODS.contains(name) || !(call.getArguments() instanceof TupleExpression args)
                    || args.getExpressions().isEmpty() || !(args.getExpression(0) instanceof GStringExpression gstring)) {
                return;
            }
            Expression receiver = call.getObjectExpression();
            boolean sql = named(typeOf(receiver), Set.of("groovy.sql.Sql"))
                    || receiver instanceof VariableExpression variable && typeOf(variable) == null
                    && Set.of("sql", "db").contains(variable.getName());
            String text = source(gstring);
            if (!sql || text == null) {
                return;
            }
            Matcher matcher = QUOTED_INTERPOLATION.matcher(text);
            if (matcher.find()) {
                report("GS012", gstring, "'" + matcher.group(1) + "' is concatenated, not bound", "Remove the quotes",
                        List.of(replace(gstring, QUOTED_INTERPOLATION.matcher(text).replaceAll("$1"))));
            }
        }

        private void checkXmlFeature(MethodCallExpression call, String name) {
            if (!"setFeature".equals(name) || !(call.getArguments() instanceof TupleExpression args)
                    || args.getExpressions().size() != 2) {
                return;
            }
            Expression feature = args.getExpression(0);
            if (feature instanceof ConstantExpression constant && String.valueOf(constant.getValue())
                    .contains("disallow-doctype-decl") && isFalse(args.getExpression(1))) {
                report("GS009", call, "disallow-doctype-decl is switched off", null, List.of());
            }
        }

        private void checkDocType(BinaryExpression assignment) {
            if (assignment.getLeftExpression() instanceof PropertyExpression property
                    && "allowDocTypeDeclaration".equals(property.getPropertyAsString())
                    && isTrue(assignment.getRightExpression())) {
                report("GS009", assignment, "allowDocTypeDeclaration = true enables DOCTYPE", null, List.of());
            }
        }

        private void checkDocTypeEntries(MapExpression map, ASTNode where) {
            for (MapEntryExpression entry : map.getMapEntryExpressions()) {
                if (entry.getKeyExpression() instanceof ConstantExpression key
                        && "allowDocTypeDeclaration".equals(key.getValue()) && isTrue(entry.getValueExpression())) {
                    report("GS009", where, "allowDocTypeDeclaration: true enables DOCTYPE", null, List.of());
                }
            }
        }

        private void checkCopyWith(MethodCallExpression call, String name) {
            if (!"copyWith".equals(name) || !config.groovy().mustCompileOnGroovy5()
                    || !(call.getArguments() instanceof TupleExpression args)) {
                return;
            }
            for (Expression argument : args.getExpressions()) {
                if (argument instanceof MapExpression map) {
                    for (MapEntryExpression entry : map.getMapEntryExpressions()) {
                        if (entry.getKeyExpression() instanceof ConstantExpression key
                                && String.valueOf(key.getValue()).contains(".")) {
                            report("GS010", call, "nested copyWith paths are Groovy 6 only", null, List.of());
                            return;
                        }
                    }
                }
            }
        }



    }
}
