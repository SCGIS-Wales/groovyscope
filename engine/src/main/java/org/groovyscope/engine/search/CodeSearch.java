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
package org.groovyscope.engine.search;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.SynchronizedStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.semantics.Resolver;
import org.groovyscope.engine.semantics.TypeNames;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * Structural code search over the compiled AST: catch clauses, throws, calls, constructor calls, {@code return null},
 * string literals, annotations, dynamically typed declarations, empty blocks, fields and methods, each with filters.
 * Only nodes with a source position are reported; Java documents are never searched.
 */
public final class CodeSearch {

    /** The searchable kinds, in the order they are documented. */
    public static final List<String> KINDS = List.of("catch", "throw", "call", "new", "return-null", "string-literal",
            "annotation", "def", "empty-block", "field", "method");

    /** Modifier names accepted by the {@code modifiers} filter, with their bits. */
    static final Map<String, Integer> MODIFIERS = modifiers();

    static final int SNIPPET_LIMIT = 160;

    /**
     * What to look for. {@code kind} is one of {@link #KINDS}; the other filters apply to the kinds that document
     * them and are ignored by the rest. {@code null} means "no filter".
     */
    public record Query(String kind, String name, String type, String pattern, List<String> modifiers, Boolean emptyBody,
            Boolean rethrows, Integer minLines, Integer minParameters) {

        public Query {
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }

        /** A query with only a kind. */
        public static Query of(String kind) {
            return new Query(kind, null, null, null, List.of(), null, null, null, null);
        }
    }

    /**
     * One match; lines and characters are zero-based. {@code facts} carries what the kind knows beyond its detail
     * (a catch clause's {@code handling}, {@code variable}, {@code catches}, {@code code} and {@code implicitType}),
     * empty for kinds without any.
     */
    public record Match(String uri, int line, int character, int endLine, String kind, String detail, String snippet,
            String enclosing, Map<String, Object> facts) {

        public Match {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }

    /** How a catch block treats the exception: the value of its {@code handling} fact. */
    public enum Handling {
        /** The block is empty: the failure disappears. */
        SWALLOWED("swallowed"),
        /** A throw inside the block passes the failure on. */
        RETHROWS("rethrows"),
        /** The block calls a logger or prints the exception, and keeps going. */
        LOGGED("logged"),
        /** The block returns a value in place of the failed computation. */
        RETURNS_DEFAULT("returns-default"),
        /** Something else: state is changed, a flag set, a callback run. */
        OTHER("other");

        private final String label;

        Handling(String label) {
            this.label = label;
        }

        /** The value reported in JSON. */
        public String label() {
            return label;
        }
    }

    /** CodeNarc rule codes for catch clauses of overly broad types, so {@code explain} applies to a match. */
    static final Map<String, String> CATCH_CODES = Map.of(
            "Exception", "CatchException",
            "Throwable", "CatchThrowable",
            "RuntimeException", "CatchRuntimeException",
            "Error", "CatchError",
            "NullPointerException", "CatchNullPointerException",
            "ArrayIndexOutOfBoundsException", "CatchArrayIndexOutOfBoundsException",
            "IndexOutOfBoundsException", "CatchIndexOutOfBoundsException",
            "IllegalMonitorStateException", "CatchIllegalMonitorStateException");

    /** The rule code of an empty catch block, which outranks the type-based codes. */
    static final String EMPTY_CATCH_CODE = "EmptyCatchBlock";

    private static final Set<String> LOG_METHODS = org.groovyscope.engine.semantics.Logging.union(
            org.groovyscope.engine.semantics.Logging.ALL_LEVELS, Set.of("log"));
    private static final Set<String> PRINT_METHODS = Set.of("println", "print", "printf", "printStackTrace");
    private static final Set<String> LOG_RECEIVERS = Set.of("log", "logger", "LOG", "LOGGER", "System.err",
            "System.out");
    private static final Pattern IMPLICIT_CATCH = Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?([\\w$]+)\\s*\\)");

    private CodeSearch() {
    }

    /**
     * Matches in the given documents (all compiled Groovy documents when {@code uris} is empty), sorted by document
     * and position. Call receivers are typed by {@code resolver}, the same inference navigation uses; with a type
     * filter, a call whose receiver type is unknown still matches when its name is given, with the fact
     * {@code receiverKnown: false}.
     *
     * @param modules the compiled trees by document URI
     * @param lines the text of a document, for snippets and positions
     * @param uris the documents to search; empty for every compiled Groovy document
     * @param query what to look for
     * @param resolver types call receivers
     * @return the matches, sorted by document and position
     * @throws IllegalArgumentException for an unknown kind or modifier, or a pattern that does not compile
     */
    public static List<Match> find(Map<String, ModuleNode> modules, Function<String, TextLines> lines,
            Collection<String> uris, Query query, Resolver resolver) {
        if (query == null || query.kind() == null || !KINDS.contains(query.kind())) {
            throw new IllegalArgumentException("unknown kind " + (query == null ? null : query.kind())
                    + "; expected one of " + String.join(", ", KINDS));
        }
        int wanted = 0;
        for (String modifier : query.modifiers()) {
            Integer bit = MODIFIERS.get(modifier.toLowerCase());
            if (bit == null) {
                throw new IllegalArgumentException("unknown modifier " + modifier + "; expected some of "
                        + String.join(", ", MODIFIERS.keySet()));
            }
            wanted |= bit;
        }
        Pattern pattern = query.pattern() == null || query.pattern().isEmpty() ? null : Pattern.compile(query.pattern());
        List<Match> matches = new ArrayList<>();
        for (String uri : targets(modules, uris)) {
            Finder finder = new Finder(uri, lines.apply(uri), query, pattern, wanted, matches, resolver);
            for (ClassNode classNode : modules.get(uri).getClasses()) {
                if (!isGenerated(classNode)) {
                    finder.visitClass(classNode);
                }
            }
        }
        matches.sort(Comparator.comparing(Match::uri).thenComparingInt(Match::line).thenComparingInt(Match::character));
        return matches;
    }

    /** Compiled Groovy documents among {@code uris} (all of them when empty), in a stable order. */
    static List<String> targets(Map<String, ModuleNode> modules, Collection<String> uris) {
        Collection<String> source = uris == null || uris.isEmpty() ? modules.keySet() : new LinkedHashSet<>(uris);
        List<String> targets = new ArrayList<>();
        for (String uri : source) {
            if (modules.containsKey(uri) && !WorkspaceModel.isJavaUri(uri)) {
                targets.add(uri);
            }
        }
        Collections.sort(targets);
        return targets;
    }

    /** Generated trait helper classes carry the trait's positions but are not user code. */
    static boolean isGenerated(ClassNode classNode) {
        return classNode.getName().contains("$Trait$");
    }

    /** True when {@code type} is {@code wanted} by simple or qualified name (nested classes with dots or dollars). */
    static boolean typeMatches(ClassNode type, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        if (type == null) {
            return false;
        }
        String target = wanted.trim().replace('$', '.');
        String name = type.getName().replace('$', '.');
        return name.equals(target) || name.endsWith("." + target);
    }

    /** True for an empty block (or no block at all); the parser wraps some blocks, such as {@code finally}, in another. */
    static boolean isEmpty(Statement statement) {
        if (statement instanceof BlockStatement block) {
            for (Statement inner : block.getStatements()) {
                if (!isEmpty(inner)) {
                    return false;
                }
            }
            return true;
        }
        return statement == null || statement instanceof org.codehaus.groovy.ast.stmt.EmptyStatement;
    }

    /**
     * How a catch block treats the exception. A throw wins over everything; a logger or print call counts as
     * logged even when the block also returns; a return without logging hands back a default; an empty block
     * swallows the failure.
     */
    static Handling handlingOf(Statement body) {
        if (isEmpty(body)) {
            return Handling.SWALLOWED;
        }
        if (containsThrow(body)) {
            return Handling.RETHROWS;
        }
        boolean[] logged = {false};
        boolean[] returns = {false};
        body.visit(new CodeVisitorSupport() {
            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (isLogging(call.getMethodAsString(), call.isImplicitThis() ? "this"
                        : call.getObjectExpression().getText())) {
                    logged[0] = true;
                }
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                if (isLogging(call.getMethod(), "this")) {
                    logged[0] = true;
                }
                super.visitStaticMethodCallExpression(call);
            }

            @Override
            public void visitReturnStatement(ReturnStatement statement) {
                returns[0] = true;
                super.visitReturnStatement(statement);
            }
        });
        if (logged[0]) {
            return Handling.LOGGED;
        }
        return returns[0] ? Handling.RETURNS_DEFAULT : Handling.OTHER;
    }

    /**
     * True for a call that records the exception: a print method on anything, or a logger method on an implicit
     * receiver or on something named like a logger ({@code log}, {@code this.logger}, {@code System.err}).
     */
    static boolean isLogging(String method, String receiver) {
        if (method == null) {
            return false;
        }
        if (PRINT_METHODS.contains(method)) {
            return true;
        }
        if (!LOG_METHODS.contains(method)) {
            return false;
        }
        if (receiver == null || "this".equals(receiver)) {
            return true;
        }
        String name = receiver.substring(receiver.lastIndexOf('.') + 1);
        return LOG_RECEIVERS.contains(receiver) || LOG_RECEIVERS.contains(name);
    }

    /**
     * True when the catch header names no type, so Groovy catches {@code Exception} under a variable that reads
     * like a type ({@code catch (MissingResourceException)}): the mistake behind many "narrow" catches.
     */
    static boolean implicitType(String header) {
        return header != null && IMPLICIT_CATCH.matcher(header).find();
    }

    /** True when a statement contains a {@code throw} anywhere inside it. */
    static boolean containsThrow(Statement statement) {
        boolean[] found = {false};
        if (statement != null) {
            statement.visit(new CodeVisitorSupport() {
                @Override
                public void visitThrowStatement(ThrowStatement node) {
                    found[0] = true;
                }
            });
        }
        return found[0];
    }

    /**
     * The type the source names for a receiver the resolver cannot use (a class missing from the classpath and the
     * workspace): its declared, cast or constructed type, matched by name only; {@code null} when it names none.
     */
    static ClassNode declaredType(Expression receiver) {
        ClassNode type = null;
        if (receiver instanceof VariableExpression variable && !variable.isThisExpression()
                && !variable.isSuperExpression()) {
            Variable accessed = variable.getAccessedVariable();
            type = accessed == null || accessed.isDynamicTyped() ? null : accessed.getOriginType();
        } else if (receiver instanceof ConstructorCallExpression constructor) {
            type = constructor.getType();
        } else if (receiver instanceof CastExpression cast) {
            type = cast.getType();
        }
        return type == null || ClassHelper.isObjectType(type) || ClassHelper.isDynamicTyped(type) ? null : type;
    }

    static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= limit ? single : single.substring(0, limit - 3) + "...";
    }

    /** {@code name(Type, Type)} for a method or constructor. */
    static String signature(MethodNode method) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        for (Parameter parameter : method.getParameters()) {
            joiner.add(TypeNames.simple(parameter.getOriginType()));
        }
        String name = method instanceof ConstructorNode ? method.getDeclaringClass().getNameWithoutPackage() : method.getName();
        return name + joiner;
    }

    static String modifierText(int modifiers) {
        StringJoiner joiner = new StringJoiner(" ");
        for (Map.Entry<String, Integer> entry : MODIFIERS.entrySet()) {
            if ((modifiers & entry.getValue()) != 0) {
                joiner.add(entry.getKey());
            }
        }
        return joiner.toString();
    }

    private static Map<String, Integer> modifiers() {
        Map<String, Integer> modifiers = new LinkedHashMap<>();
        modifiers.put("public", Modifier.PUBLIC);
        modifiers.put("protected", Modifier.PROTECTED);
        modifiers.put("private", Modifier.PRIVATE);
        modifiers.put("abstract", Modifier.ABSTRACT);
        modifiers.put("static", Modifier.STATIC);
        modifiers.put("final", Modifier.FINAL);
        modifiers.put("synchronized", Modifier.SYNCHRONIZED);
        modifiers.put("transient", Modifier.TRANSIENT);
        modifiers.put("volatile", Modifier.VOLATILE);
        modifiers.put("native", Modifier.NATIVE);
        return Collections.unmodifiableMap(modifiers);
    }

    /** The visitor behind {@link #find}: one per document, reporting each node at most once. */
    private static final class Finder extends ClassCodeVisitorSupport {

        private final String uri;
        private final TextLines lines;
        private final Query query;
        private final String kind;
        private final Pattern pattern;
        private final int wantedModifiers;
        private final List<Match> out;
        private final Resolver resolver;
        private final Set<ASTNode> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        private ClassNode currentClass;
        private MethodNode currentMethod;

        Finder(String uri, TextLines lines, Query query, Pattern pattern, int wantedModifiers, List<Match> out,
                Resolver resolver) {
            this.resolver = resolver;
            this.uri = uri;
            this.lines = lines;
            this.query = query;
            this.kind = query.kind();
            this.pattern = pattern;
            this.wantedModifiers = wantedModifiers;
            this.out = out;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        private boolean is(String wanted) {
            return kind.equals(wanted);
        }

        private String enclosing() {
            String owner = currentClass == null ? "" : currentClass.getNameWithoutPackage();
            if (currentMethod == null || currentMethod.getLineNumber() < 1 || currentMethod.isScriptBody()) {
                return owner;
            }
            String method = currentMethod instanceof ConstructorNode ? "<init>" : currentMethod.getName();
            return owner.isEmpty() ? method : owner + "." + method;
        }

        /** Reports {@code node} (at {@code fallback}'s position when it has none), once. */
        private void report(ASTNode node, ASTNode fallback, String detail) {
            report(node, fallback, detail, Map.of());
        }

        /** Reports {@code node} with the facts its kind knows beyond the detail. */
        private void report(ASTNode node, ASTNode fallback, String detail, Map<String, Object> facts) {
            Range range = AstRanges.rangeOf(node);
            if (range == null && fallback != null) {
                range = AstRanges.rangeOf(fallback);
            }
            if (range == null || !reported.add(node)) {
                return;
            }
            String snippet = lines.line(range.start().line()).trim();
            if (snippet.length() > SNIPPET_LIMIT) {
                snippet = snippet.substring(0, SNIPPET_LIMIT);
            }
            out.add(new Match(uri, range.start().line(), range.start().column(), range.end().line(), kind, detail,
                    snippet, enclosing(), facts));
        }

        private boolean modifiersMatch(int modifiers) {
            return (modifiers & wantedModifiers) == wantedModifiers;
        }

        @Override
        public void visitClass(ClassNode node) {
            ClassNode previousClass = currentClass;
            MethodNode previousMethod = currentMethod;
            currentClass = node;
            currentMethod = null;
            try {
                super.visitClass(node);
            } finally {
                currentClass = previousClass;
                currentMethod = previousMethod;
            }
        }

        @Override
        public void visitAnnotations(AnnotatedNode node) {
            if (is("annotation")) {
                for (AnnotationNode annotation : node.getAnnotations()) {
                    ClassNode type = annotation.getClassNode();
                    if (type != null && (query.name() == null || typeMatches(type, query.name()))
                            && AstRanges.rangeOf(annotation) != null) {
                        report(annotation, null, "@" + type.getNameWithoutPackage() + " on " + target(node));
                    }
                }
            }
            super.visitAnnotations(node);
        }

        private static String target(AnnotatedNode node) {
            if (node instanceof ClassNode classNode) {
                return "class " + classNode.getNameWithoutPackage();
            }
            if (node instanceof ConstructorNode constructor) {
                return "constructor " + signature(constructor);
            }
            if (node instanceof MethodNode method) {
                return "method " + method.getName();
            }
            if (node instanceof FieldNode field) {
                return "field " + field.getName();
            }
            if (node instanceof PropertyNode property) {
                return "property " + property.getName();
            }
            if (node instanceof Parameter parameter) {
                return "parameter " + parameter.getName();
            }
            return "declaration";
        }

        @Override
        protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            MethodNode previous = currentMethod;
            currentMethod = node;
            try {
                inspectMethod(node, isConstructor);
                for (Parameter parameter : node.getParameters()) {
                    visitAnnotations(parameter);
                }
                super.visitConstructorOrMethod(node, isConstructor);
            } finally {
                currentMethod = previous;
            }
        }

        private void inspectMethod(MethodNode node, boolean isConstructor) {
            Range range = AstRanges.rangeOf(node);
            if (range == null || node.isSynthetic() || node.isScriptBody() || node.getName().startsWith("<clinit>")) {
                return;
            }
            String what = isConstructor ? "constructor " : "method ";
            if (is("method") && modifiersMatch(node.getModifiers())) {
                int length = range.end().line() - range.start().line() + 1;
                boolean longEnough = query.minLines() == null || length >= query.minLines();
                boolean enoughParameters = query.minParameters() == null || node.getParameters().length >= query.minParameters();
                if (longEnough && enoughParameters) {
                    report(node, null, what + signature(node) + " " + length + (length == 1 ? " line" : " lines"));
                }
            } else if (is("def")) {
                if (!isConstructor && node.isDynamicReturnType()) {
                    report(node, null, "def method " + signature(node));
                }
                for (Parameter parameter : node.getParameters()) {
                    if (parameter.isDynamicTyped() && AstRanges.rangeOf(parameter) != null) {
                        report(parameter, null, "def parameter " + parameter.getName() + " of " + node.getName());
                    }
                }
            } else if (is("empty-block") && !node.isAbstract() && node.getCode() != null && isEmpty(node.getCode())) {
                report(node.getCode(), node, "empty " + what.trim() + " body " + signature(node));
            }
        }

        @Override
        public void visitField(FieldNode node) {
            PropertyNode property = currentClass == null ? null : currentClass.getProperty(node.getName());
            if (property == null && !node.isSynthetic() && !node.isEnum() && AstRanges.rangeOf(node) != null) {
                inspectField(node, node.getModifiers(), "field ");
            }
            super.visitField(node);
        }

        @Override
        public void visitProperty(PropertyNode node) {
            if (AstRanges.rangeOf(node) != null) {
                inspectField(node, node.getModifiers(), "property ");
            }
            super.visitProperty(node);
        }

        private void inspectField(AnnotatedNode node, int modifiers, String what) {
            FieldNode field = node instanceof PropertyNode property ? property.getField() : (FieldNode) node;
            String name = field.getName();
            if (is("field") && modifiersMatch(modifiers)) {
                String text = modifierText(modifiers);
                report(node, null, what + (text.isEmpty() ? "" : text + " ") + TypeNames.simple(field.getOriginType()) + " " + name);
            } else if (is("def") && field.isDynamicTyped()) {
                report(node, null, "def " + what + name);
            }
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            if (is("def")) {
                if (expression.isMultipleAssignmentDeclaration()) {
                    TupleExpression tuple = expression.getTupleExpression();
                    List<String> names = new ArrayList<>();
                    boolean dynamic = false;
                    for (Expression element : tuple.getExpressions()) {
                        if (element instanceof VariableExpression variable) {
                            names.add(variable.getName());
                            dynamic |= variable.isDynamicTyped();
                        }
                    }
                    if (dynamic) {
                        report(expression, null, "def (" + String.join(", ", names) + ")");
                    }
                } else if (expression.getVariableExpression().isDynamicTyped()) {
                    report(expression, null, "def " + expression.getVariableExpression().getName());
                }
            }
            super.visitDeclarationExpression(expression);
        }

        @Override
        public void visitCatchStatement(CatchStatement statement) {
            if (is("catch")) {
                Parameter variable = statement.getVariable();
                ClassNode type = variable == null ? null : variable.getType();
                boolean empty = isEmpty(statement.getCode());
                boolean rethrows = containsThrow(statement.getCode());
                if (typeMatches(type, query.type())
                        && (query.emptyBody() == null || query.emptyBody() == empty)
                        && (query.rethrows() == null || query.rethrows() == rethrows)) {
                    String caught = type == null ? "" : TypeNames.simple(type);
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("handling", handlingOf(statement.getCode()).label());
                    facts.put("catches", caught);
                    facts.put("variable", variable == null ? "" : variable.getName());
                    String code = empty ? EMPTY_CATCH_CODE : CATCH_CODES.get(caught);
                    if (code != null) {
                        facts.put("code", code);
                    }
                    Range range = AstRanges.rangeOf(statement);
                    if (range != null && implicitType(lines.line(range.start().line()))) {
                        facts.put("implicitType", Boolean.TRUE);
                    }
                    report(statement, null, "catch (" + (caught.isEmpty() ? "" : caught + " ")
                            + (variable == null ? "" : variable.getName()) + ")" + (empty ? " empty" : ""), facts);
                }
            } else if (is("empty-block")) {
                emptyBlock(statement.getCode(), statement, "catch");
            }
            super.visitCatchStatement(statement);
        }

        private void emptyBlock(Statement block, ASTNode owner, String what) {
            if (block instanceof BlockStatement && isEmpty(block)) {
                report(block, owner, "empty " + what + " block");
            }
        }

        @Override
        public void visitIfElse(IfStatement statement) {
            if (is("empty-block")) {
                emptyBlock(statement.getIfBlock(), statement, "if");
                emptyBlock(statement.getElseBlock(), statement, "else");
            }
            super.visitIfElse(statement);
        }

        @Override
        public void visitForLoop(ForStatement statement) {
            if (is("empty-block")) {
                emptyBlock(statement.getLoopBlock(), statement, "for");
            }
            super.visitForLoop(statement);
        }

        @Override
        public void visitWhileLoop(WhileStatement statement) {
            if (is("empty-block")) {
                emptyBlock(statement.getLoopBlock(), statement, "while");
            }
            super.visitWhileLoop(statement);
        }

        @Override
        public void visitDoWhileLoop(DoWhileStatement statement) {
            if (is("empty-block")) {
                emptyBlock(statement.getLoopBlock(), statement, "do-while");
            }
            super.visitDoWhileLoop(statement);
        }

        @Override
        public void visitTryCatchFinally(TryCatchStatement statement) {
            if (is("empty-block")) {
                emptyBlock(statement.getTryStatement(), statement, "try");
                emptyBlock(statement.getFinallyStatement(), statement, "finally");
            }
            super.visitTryCatchFinally(statement);
        }

        @Override
        public void visitSynchronizedStatement(SynchronizedStatement statement) {
            if (is("empty-block")) {
                emptyBlock(statement.getCode(), statement, "synchronized");
            }
            super.visitSynchronizedStatement(statement);
        }

        @Override
        public void visitThrowStatement(ThrowStatement statement) {
            if (is("throw")) {
                Expression thrown = statement.getExpression();
                ClassNode type = thrown instanceof ConstructorCallExpression constructor ? constructor.getType() : null;
                if (query.type() == null || query.type().isBlank() || typeMatches(type, query.type())) {
                    report(statement, null, type != null ? "throw new " + type.getNameWithoutPackage()
                            : "throw " + shorten(thrown.getText(), 60));
                }
            }
            super.visitThrowStatement(statement);
        }

        @Override
        public void visitReturnStatement(ReturnStatement statement) {
            if (is("return-null") && statement.getExpression() instanceof ConstantExpression constant
                    && constant.isNullExpression() && constant.getLineNumber() > 0) {
                report(statement, constant, "return null");
            }
            super.visitReturnStatement(statement);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            if (is("call")) {
                String name = call.getMethodAsString();
                if (name != null && (query.name() == null || query.name().equals(name))) {
                    String receiver = call.isImplicitThis() ? "" : shorten(call.getObjectExpression().getText(), 40) + ".";
                    if (query.type() == null || query.type().isBlank()) {
                        report(call, null, "call " + receiver + name);
                    } else {
                        ClassNode type = receiverOf(call);
                        if (type != null ? typeMatches(type, query.type()) : query.name() != null) {
                            report(call, null, "call " + receiver + name, Map.of("receiverKnown", type != null));
                        }
                    }
                }
            }
            call.getObjectExpression().visit(this);
            if (!(call.getMethod() instanceof ConstantExpression)) {
                call.getMethod().visit(this);
            }
            call.getArguments().visit(this);
        }

        /** The receiver's type as the resolver infers it, else the type the source names; null when unknown. */
        private ClassNode receiverOf(MethodCallExpression call) {
            NodeEntry entry = new NodeEntry(uri, call, NodeEntry.Role.OTHER, null, null, currentClass, currentMethod);
            ClassNode type = resolver.receiverOf(call.getObjectExpression(), call.isImplicitThis(), entry).type();
            return type != null ? type : declaredType(call.getObjectExpression());
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            if (is("call") && (query.name() == null || query.name().equals(call.getMethod()))
                    && typeMatches(call.getOwnerType(), query.type())) {
                report(call, null, "call " + call.getOwnerType().getNameWithoutPackage() + "." + call.getMethod());
            }
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            if (is("new") && !call.isSpecialCall()) {
                ClassNode type = call.getType();
                if (call.isUsingAnonymousInnerClass() && type != null) {
                    ClassNode[] interfaces = type.getUnresolvedInterfaces(false);
                    type = interfaces.length > 0 ? interfaces[0] : type.getUnresolvedSuperClass(false);
                }
                if (typeMatches(type, query.type())) {
                    report(call, null, "new " + (type == null ? "?" : type.getNameWithoutPackage()));
                }
            }
            super.visitConstructorCallExpression(call);
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
        public void visitMethodPointerExpression(MethodPointerExpression expression) {
            expression.getExpression().visit(this);
            if (!(expression.getMethodName() instanceof ConstantExpression)) {
                expression.getMethodName().visit(this);
            }
        }

        @Override
        public void visitGStringExpression(GStringExpression expression) {
            if (is("string-literal") && expression.getLineNumber() > 0) {
                boolean found = pattern == null;
                for (ConstantExpression part : expression.getStrings()) {
                    found |= part.getValue() instanceof String value && pattern != null && pattern.matcher(value).find();
                }
                if (found) {
                    report(expression, null, "\"" + shorten(expression.getText(), 60) + "\"", Map.of("interpolated", true));
                }
            }
            for (Expression value : expression.getValues()) {
                value.visit(this);
            }
        }

        @Override
        public void visitConstantExpression(ConstantExpression expression) {
            if (is("string-literal") && expression.getValue() instanceof String value && expression.getLineNumber() > 0
                    && (pattern == null || pattern.matcher(value).find())) {
                report(expression, null, "\"" + shorten(value, 60) + "\"");
            }
            super.visitConstantExpression(expression);
        }
    }
}
