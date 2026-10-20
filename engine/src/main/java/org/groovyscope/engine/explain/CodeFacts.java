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
package org.groovyscope.engine.explain;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.CaseStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * What a class or method does, as facts read from its tree and the call graph: members and their types, the
 * calls it makes and receives, the fields it reads and writes, the exceptions it throws and catches, the
 * external world it touches (HTTP, files, SQL, processes, the clock, the environment) and how branchy it is.
 * Nothing is inferred from names; an agent turns these facts into prose, and the naming checks judge names
 * against them.
 */
public final class CodeFacts {

    /** Most calls, callers and reads listed per method. */
    static final int MAX_LISTED = 12;
    /** Most responsibilities (verb groups) listed per class. */
    static final int MAX_RESPONSIBILITIES = 8;
    private static final Set<String> IGNORED_SUPERS = Set.of("java.lang.Object", "groovy.lang.Script",
            "groovy.lang.GroovyObject", "groovy.lang.GroovyObjectSupport");
    private static final Set<String> LOGGER_NAMES = Set.of("log", "logger", "LOG", "LOGGER");
    private static final Set<String> LOG_METHODS = org.groovyscope.engine.semantics.Logging.ALL_LEVELS;
    private static final Set<String> FILE_TYPES = Set.of("java.io.File", "java.nio.file.Files", "java.nio.file.Paths",
            "java.io.FileReader", "java.io.FileWriter", "java.io.FileInputStream", "java.io.FileOutputStream",
            "java.io.RandomAccessFile");
    private static final Set<String> HTTP_TYPES = Set.of("java.net.URL", "java.net.HttpURLConnection",
            "java.net.URLConnection", "java.net.Socket");
    private static final Set<String> RANDOM_TYPES = Set.of("java.util.Random", "java.security.SecureRandom",
            "java.util.concurrent.ThreadLocalRandom");
    private static final Set<String> CLOCK_METHODS = Set.of("now", "currentTimeMillis", "nanoTime");
    private static final Set<String> ENVIRONMENT_METHODS = Set.of("getenv", "getProperty", "getProperties");
    /** Calls on a field's value that change it (collections, builders, counters). */
    private static final Set<String> MUTATORS = Set.of("add", "addAll", "put", "putAll", "putAt", "remove",
            "removeAll", "removeIf", "retainAll", "clear", "set", "push", "pop", "leftShift", "offer", "poll",
            "append", "replaceAll", "sort", "incrementAndGet", "decrementAndGet", "getAndIncrement", "addAndGet",
            "compareAndSet", "lazySet", "merge", "compute", "computeIfAbsent", "computeIfPresent", "putIfAbsent");
    /** Values whose methods never change them, whatever the name ({@code String.replaceAll} returns a copy). */
    private static final Set<String> IMMUTABLE_TYPES = Set.of("java.lang.String", "groovy.lang.GString",
            "java.lang.CharSequence", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Short", "java.lang.Byte", "java.lang.Boolean", "java.lang.Character", "java.math.BigDecimal",
            "java.math.BigInteger", "java.util.regex.Pattern", "java.nio.file.Path", "java.net.URI");
    /** File operations that create, change or delete files; every other file call only reads. */
    private static final Set<String> FILE_WRITES = Set.of("write", "append", "leftShift", "newWriter",
            "newOutputStream", "newPrintWriter", "withWriter", "withWriterAppend", "withOutputStream",
            "withPrintWriter", "setText", "setBytes", "delete", "deleteDir", "deleteOnExit", "deleteIfExists", "mkdir",
            "mkdirs", "renameTo", "createNewFile", "createDirectory", "createDirectories", "createFile",
            "createTempFile", "copy", "move", "writeString", "setLastModified", "setReadable", "setWritable",
            "setExecutable");
    private static final Set<String> FILE_WRITER_TYPES = Set.of("java.io.FileWriter", "java.io.FileOutputStream",
            "java.io.RandomAccessFile", "java.io.PrintWriter");
    /** SQL calls that only query; every other SQL call may change data. */
    private static final Set<String> SQL_READS = Set.of("rows", "firstRow", "eachRow", "query", "executeQuery",
            "getConnection", "close", "next", "getString", "getInt", "getLong", "getObject", "getMetaData");
    /** HTTP calls that only fetch; every other HTTP call (POST, PUT, PATCH, DELETE, send) may change data. */
    private static final Set<String> HTTP_READS = Set.of("get", "getText", "getContent", "openStream",
            "getInputStream", "head", "toURI", "getHost", "getPath", "getQuery", "getPort", "getProtocol", "toString",
            "getResponseCode", "getResponseMessage", "getHeaderField", "getHeaderFields", "openConnection", "connect",
            "<init>", "disconnect", "setRequestProperty", "setConnectTimeout", "setReadTimeout");

    /** One parameter. */
    public record Parameter(String name, String type) {
    }

    /** One field or property. */
    public record FieldFacts(String name, String type, String visibility, boolean isFinal, boolean isStatic,
            boolean isProperty, boolean initialised, int line) {
    }

    /** One resolved call target with how often the method calls it. */
    public record Call(String target, String confidence, int count) {
    }

    /** One method or constructor. */
    public record MethodFacts(String name, String signature, String visibility, boolean isStatic, boolean isAbstract,
            boolean isConstructor, String returns, boolean dynamicReturn, List<Parameter> parameters, int startLine,
            int endLine, int branches, List<Call> calls, int callerCount, List<String> callers, List<String> reads,
            List<String> writes, List<String> throwsTypes, List<String> catches, List<String> externals) {
    }

    /** Public methods grouped by their leading verb. */
    public record Responsibility(String verb, List<String> methods) {
    }

    /** One class, with its members and what it exchanges with the rest of the workspace. */
    public record ClassFacts(String name, String simpleName, String kind, String packageName, String uri, int startLine,
            int endLine, List<String> modifiers, List<String> annotations, String superClass, List<String> interfaces,
            List<FieldFacts> fields, List<MethodFacts> methods, List<Responsibility> responsibilities,
            List<String> usedBy, List<String> uses, int callerCount) {
    }

    private CodeFacts() {
    }

    /** Facts about a class. */
    public static ClassFacts ofClass(ClassNode classNode, AstIndex index, CallGraph graph) {
        Range range = index.declarationEntry(classNode).map(NodeEntry::range).orElse(AstRanges.rangeOf(classNode));
        String uri = index.declarationEntry(classNode).map(NodeEntry::uri).orElse(null);
        List<FieldFacts> fields = new ArrayList<>();
        for (PropertyNode property : classNode.getProperties()) {
            fields.add(fieldFacts(property.getField(), true));
        }
        for (FieldNode field : classNode.getFields()) {
            if (classNode.getProperty(field.getName()) == null && !field.isSynthetic()) {
                fields.add(fieldFacts(field, false));
            }
        }
        List<MethodFacts> methods = new ArrayList<>();
        for (ConstructorNode constructor : classNode.getDeclaredConstructors()) {
            if (constructor.getLineNumber() > 0) {
                methods.add(ofMethod(constructor, index, graph));
            }
        }
        for (MethodNode method : classNode.getMethods()) {
            if (!method.isSynthetic() && method.getLineNumber() > 0 && !method.isScriptBody()) {
                methods.add(ofMethod(method, index, graph));
            }
        }
        TreeSet<String> usedBy = new TreeSet<>();
        TreeSet<String> uses = new TreeSet<>();
        int callerCount = 0;
        String own = classNode.getName();
        for (MethodFacts method : methods) {
            callerCount += method.callerCount();
            for (String caller : method.callers()) {
                String owner = ownerOf(caller);
                if (!owner.equals(own)) {
                    usedBy.add(owner);
                }
            }
            for (Call call : method.calls()) {
                String owner = ownerOf(call.target());
                if (!owner.equals(own)) {
                    uses.add(owner);
                }
            }
        }
        List<String> modifiers = new ArrayList<>();
        int mods = classNode.getModifiers();
        if (Modifier.isAbstract(mods) && !classNode.isInterface()) {
            modifiers.add("abstract");
        }
        if (Modifier.isFinal(mods)) {
            modifiers.add("final");
        }
        if (Modifier.isStatic(mods)) {
            modifiers.add("static");
        }
        ClassNode superClass = classNode.getSuperClass();
        String superName = superClass == null || IGNORED_SUPERS.contains(superClass.getName()) ? null
                : superClass.getName();
        List<String> interfaces = new ArrayList<>();
        for (ClassNode iface : classNode.getInterfaces()) {
            if (!IGNORED_SUPERS.contains(iface.getName())) {
                interfaces.add(iface.getName());
            }
        }
        return new ClassFacts(own, classNode.isScript() ? classNode.getNameWithoutPackage()
                : classNode.getNameWithoutPackage().replace('$', '.'), kindOf(classNode), classNode.getPackageName(),
                uri, range == null ? 0 : range.start().line(), range == null ? 0 : range.end().line(), modifiers,
                annotations(classNode.getAnnotations()), superName, interfaces, fields, methods,
                responsibilities(methods), new ArrayList<>(usedBy), new ArrayList<>(uses), callerCount);
    }

    /** Facts about a method or constructor. */
    public static MethodFacts ofMethod(MethodNode method, AstIndex index, CallGraph graph) {
        Range range = index.declarationEntry(method).map(NodeEntry::range).orElse(AstRanges.rangeOf(method));
        Body body = new Body(method.getDeclaringClass());
        if (method.getCode() != null) {
            method.getCode().visit(body);
        }
        Map<String, int[]> targets = new TreeMap<>();
        Map<String, String> confidences = new LinkedHashMap<>();
        for (CallSite site : graph.outgoing(method)) {
            String target = qualified(site.callee());
            targets.computeIfAbsent(target, k -> new int[1])[0]++;
            String confidence = site.confidence().name();
            confidences.merge(target, confidence, (a, b) -> weaker(a, b));
        }
        List<Call> calls = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : targets.entrySet()) {
            calls.add(new Call(entry.getKey(), confidences.get(entry.getKey()), entry.getValue()[0]));
        }
        calls.sort((a, b) -> b.count() != a.count() ? Integer.compare(b.count(), a.count())
                : a.target().compareTo(b.target()));
        TreeSet<String> callers = new TreeSet<>();
        List<CallSite> incoming = graph.incoming(method);
        for (CallSite site : incoming) {
            callers.add(qualified(site.caller()));
        }
        List<Parameter> parameters = new ArrayList<>();
        for (org.codehaus.groovy.ast.Parameter parameter : method.getParameters()) {
            parameters.add(new Parameter(parameter.getName(), typeName(parameter.getType(),
                    parameter.isDynamicTyped())));
        }
        boolean constructor = method instanceof ConstructorNode;
        return new MethodFacts(constructor ? "<init>" : method.getName(), TypeNames.signature(method),
                visibility(method.getModifiers()), method.isStatic(), method.isAbstract(), constructor,
                constructor ? null : typeName(method.getReturnType(), method.isDynamicReturnType()),
                method.isDynamicReturnType(), parameters, range == null ? 0 : range.start().line(),
                range == null ? 0 : range.end().line(), body.branches, limit(calls), incoming.size(),
                limit(new ArrayList<>(callers)), limit(new ArrayList<>(body.reads)),
                limit(new ArrayList<>(body.writes)), new ArrayList<>(body.throwsTypes), new ArrayList<>(body.catches),
                new ArrayList<>(body.externals));
    }

    /** Facts about a field or property. */
    public static FieldFacts fieldFacts(FieldNode field, boolean property) {
        return new FieldFacts(field.getName(), typeName(field.getType(), field.isDynamicTyped()),
                property ? "property" : visibility(field.getModifiers()), field.isFinal(), field.isStatic(), property,
                field.hasInitialExpression(), field.getLineNumber() - 1);
    }

    static String kindOf(ClassNode classNode) {
        if (classNode.isScript()) {
            return "script";
        }
        return TypeNames.keywordOf(classNode);
    }

    static String visibility(int modifiers) {
        if (Modifier.isPublic(modifiers)) {
            return "public";
        }
        if (Modifier.isPrivate(modifiers)) {
            return "private";
        }
        if (Modifier.isProtected(modifiers)) {
            return "protected";
        }
        return "package";
    }

    static String typeName(ClassNode type, boolean dynamic) {
        if (dynamic || type == null) {
            return "def";
        }
        return TypeNames.simple(type);
    }

    static List<String> annotations(List<AnnotationNode> annotations) {
        List<String> names = new ArrayList<>();
        for (AnnotationNode annotation : annotations) {
            names.add(annotation.getClassNode().getNameWithoutPackage());
        }
        return names;
    }

    /** {@code Class.member} for a symbol, or its name alone when it has no container. */
    static String qualified(Symbol symbol) {
        if (symbol.container() == null || symbol.container().isBlank()) {
            return symbol.name() == null ? "" : symbol.name();
        }
        return symbol.container() + "." + symbol.name();
    }

    static String ownerOf(String qualifiedMember) {
        int dot = qualifiedMember.lastIndexOf('.');
        return dot > 0 ? qualifiedMember.substring(0, dot) : qualifiedMember;
    }

    static String weaker(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(String confidence) {
        return switch (confidence) {
            case "DYNAMIC_UNKNOWN" -> 2;
            case "INFERRED" -> 1;
            default -> 0;
        };
    }

    static <T> List<T> limit(List<T> items) {
        return items.size() <= MAX_LISTED ? items : new ArrayList<>(items.subList(0, MAX_LISTED));
    }

    /** Public methods grouped by their leading camel-case word, largest groups first. */
    static List<Responsibility> responsibilities(List<MethodFacts> methods) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (MethodFacts method : methods) {
            if (method.isConstructor() || !"public".equals(method.visibility())) {
                continue;
            }
            groups.computeIfAbsent(leadingWord(method.name()), k -> new ArrayList<>()).add(method.name());
        }
        List<Responsibility> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            out.add(new Responsibility(entry.getKey(), entry.getValue()));
        }
        out.sort((a, b) -> b.methods().size() != a.methods().size()
                ? Integer.compare(b.methods().size(), a.methods().size()) : a.verb().compareTo(b.verb()));
        return out.size() <= MAX_RESPONSIBILITIES ? out : new ArrayList<>(out.subList(0, MAX_RESPONSIBILITIES));
    }

    /** The first camel-case word of an identifier, lower-cased ({@code resolveApi} gives {@code resolve}). */
    public static String leadingWord(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }
        int end = 1;
        while (end < identifier.length() && !Character.isUpperCase(identifier.charAt(end))
                && identifier.charAt(end) != '_' && identifier.charAt(end) != '$') {
            end++;
        }
        return identifier.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a call in an external category changes the world: a process or an exit always does; a file call when
     * it creates, writes or deletes; a SQL or HTTP call unless it only queries or fetches (anything unknown counts
     * as a change, so a write is never shown as a read); the environment when a system property is set.
     */
    public static boolean writesExternally(String category, String typeName, String method) {
        if (category == null) {
            return false;
        }
        return switch (category) {
            case "process", "exit" -> true;
            case "file" -> contains(FILE_WRITES, method)
                    || "<init>".equals(method) && contains(FILE_WRITER_TYPES, typeName);
            case "sql" -> !contains(SQL_READS, method);
            case "http" -> !contains(HTTP_READS, method);
            default -> false;
        };
    }

    /**
     * How a method changes state by itself, ignoring the methods it calls: the fields and properties it assigns or
     * mutates ({@code field name}) and the external writes it makes ({@code file}, {@code http}, {@code sql},
     * {@code process}, {@code exit}, {@code environment}). Empty when it only reads.
     */
    public static Set<String> stateChanges(MethodNode method) {
        Body body = new Body(method.getDeclaringClass());
        if (method.getCode() != null) {
            method.getCode().visit(body);
        }
        return new TreeSet<>(body.stateChanges);
    }

    /** Whether a call by this name changes the object it is called on (a collection, builder or counter). */
    public static boolean mutates(String method) {
        return contains(MUTATORS, method);
    }

    /** {@code Set.of} sets throw on a {@code null} lookup; an unknown type or a dynamic method name is no match. */
    private static boolean contains(Set<String> set, String value) {
        return value != null && set.contains(value);
    }

    private static boolean setsSystemProperty(String typeName, String method) {
        return "java.lang.System".equals(typeName)
                && ("setProperty".equals(method) || "clearProperty".equals(method) || "setProperties".equals(method));
    }

    /** The external category of a call on a type, or {@code null} when it is plain computation. */
    public static String externalOf(String typeName, String method, String receiverName) {
        if (typeName != null) {
            if (typeName.startsWith("java.net.http") || HTTP_TYPES.contains(typeName) || typeName.contains("HttpClient")
                    || typeName.contains("HttpBuilder") || typeName.contains("RESTClient")) {
                return "http";
            }
            if (FILE_TYPES.contains(typeName)) {
                return "file";
            }
            if ("groovy.sql.Sql".equals(typeName) || typeName.startsWith("java.sql") || typeName.startsWith("javax.sql")) {
                return "sql";
            }
            if ("java.lang.ProcessBuilder".equals(typeName) || "java.lang.Process".equals(typeName)
                    || ("java.lang.Runtime".equals(typeName) && "exec".equals(method))) {
                return "process";
            }
            if ("java.lang.System".equals(typeName)) {
                if (contains(ENVIRONMENT_METHODS, method)) {
                    return "environment";
                }
                if (contains(CLOCK_METHODS, method)) {
                    return "clock";
                }
                if ("exit".equals(method)) {
                    return "exit";
                }
            }
            if (typeName.startsWith("java.time") && "now".equals(method)) {
                return "clock";
            }
            if (RANDOM_TYPES.contains(typeName) || ("java.util.UUID".equals(typeName) && "randomUUID".equals(method))) {
                return "random";
            }
            if (typeName.startsWith("java.util.concurrent") || "java.lang.Thread".equals(typeName)) {
                return "concurrency";
            }
            if (typeName.startsWith("groovy.json") || typeName.startsWith("groovy.xml") || typeName.startsWith("groovy.yaml")
                    || typeName.startsWith("org.yaml")) {
                return "parsing";
            }
            if (typeName.endsWith("Logger") || typeName.endsWith(".Log")) {
                return "logging";
            }
            if (("java.lang.String".equals(typeName) || "groovy.lang.GString".equals(typeName)
                    || "java.util.List".equals(typeName) || "java.util.ArrayList".equals(typeName))
                    && "execute".equals(method)) {
                return "process";
            }
        }
        if (receiverName != null && LOGGER_NAMES.contains(receiverName) && contains(LOG_METHODS, method)) {
            return "logging";
        }
        return null;
    }

    /** Walks a method body for branches, field reads and writes, throws, catches and external calls. */
    private static final class Body extends CodeVisitorSupport {
        private final ClassNode owner;
        private final TreeSet<String> reads = new TreeSet<>();
        private final TreeSet<String> writes = new TreeSet<>();
        private final TreeSet<String> throwsTypes = new TreeSet<>();
        private final TreeSet<String> catches = new TreeSet<>();
        private final TreeSet<String> externals = new TreeSet<>();
        private final TreeSet<String> stateChanges = new TreeSet<>();
        private int branches;

        Body(ClassNode owner) {
            this.owner = owner;
        }

        @Override
        public void visitIfElse(IfStatement statement) {
            branches++;
            super.visitIfElse(statement);
        }

        @Override
        public void visitForLoop(ForStatement statement) {
            branches++;
            super.visitForLoop(statement);
        }

        @Override
        public void visitWhileLoop(WhileStatement statement) {
            branches++;
            super.visitWhileLoop(statement);
        }

        @Override
        public void visitDoWhileLoop(DoWhileStatement statement) {
            branches++;
            super.visitDoWhileLoop(statement);
        }

        @Override
        public void visitCaseStatement(CaseStatement statement) {
            branches++;
            super.visitCaseStatement(statement);
        }

        @Override
        public void visitCatchStatement(CatchStatement statement) {
            branches++;
            if (statement.getVariable() != null) {
                catches.add(TypeNames.simple(statement.getVariable().getType()));
            }
            super.visitCatchStatement(statement);
        }

        @Override
        public void visitTernaryExpression(TernaryExpression expression) {
            branches++;
            super.visitTernaryExpression(expression);
        }

        @Override
        public void visitThrowStatement(ThrowStatement statement) {
            Expression thrown = statement.getExpression();
            if (thrown instanceof ConstructorCallExpression constructor) {
                throwsTypes.add(TypeNames.simple(constructor.getType()));
            } else if (thrown instanceof VariableExpression variable) {
                throwsTypes.add(variable.isDynamicTyped() ? variable.getName() : TypeNames.simple(variable.getType()));
            }
            super.visitThrowStatement(statement);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            int type = expression.getOperation().getType();
            if (type == Types.LOGICAL_AND || type == Types.LOGICAL_OR) {
                branches++;
            }
            if (Types.isAssignment(type)) {
                String field = fieldOf(expression.getLeftExpression());
                if (field != null) {
                    writes.add(field);
                    stateChanges.add("field " + field);
                    if (type != Types.ASSIGN) {
                        reads.add(field);
                    }
                    expression.getRightExpression().visit(this);
                    return;
                }
                assignedElsewhere(expression.getLeftExpression());
            }
            if (type == Types.LEFT_SHIFT) {
                String field = fieldOf(expression.getLeftExpression());
                if (field != null) {
                    stateChanges.add("field " + field);
                }
            }
            super.visitBinaryExpression(expression);
        }

        /**
         * An assignment to another object's property, or into a field's map or list ({@code cache[key] = v}). An
         * object held only in a local variable is the method's own scratch state, so setting its properties is not
         * a change anyone else sees.
         */
        private void assignedElsewhere(Expression left) {
            if (left instanceof PropertyExpression property && property.getPropertyAsString() != null) {
                if (!isLocal(property.getObjectExpression())) {
                    stateChanges.add("property " + property.getPropertyAsString());
                }
            } else if (left instanceof BinaryExpression index
                    && index.getOperation().getType() == Types.LEFT_SQUARE_BRACKET) {
                String field = fieldOf(index.getLeftExpression());
                if (field != null) {
                    stateChanges.add("field " + field);
                }
            }
        }

        @Override
        public void visitPostfixExpression(PostfixExpression expression) {
            String field = fieldOf(expression.getExpression());
            if (field != null) {
                writes.add(field);
                reads.add(field);
                stateChanges.add("field " + field);
            }
            super.visitPostfixExpression(expression);
        }

        @Override
        public void visitPrefixExpression(PrefixExpression expression) {
            String field = fieldOf(expression.getExpression());
            if (field != null) {
                writes.add(field);
                reads.add(field);
                stateChanges.add("field " + field);
            }
            super.visitPrefixExpression(expression);
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            String field = fieldOf(expression);
            if (field != null) {
                reads.add(field);
            }
            super.visitVariableExpression(expression);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            String field = fieldOf(expression);
            if (field != null) {
                reads.add(field);
                return;
            }
            super.visitPropertyExpression(expression);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String method = call.getMethodAsString();
            Expression receiver = call.getObjectExpression();
            String receiverName = receiver instanceof VariableExpression variable ? variable.getName()
                    : receiver instanceof PropertyExpression property ? property.getPropertyAsString() : null;
            String typeName = receiverTypeName(receiver, call.isImplicitThis());
            String external = externalOf(typeName, method, receiverName);
            if (external != null) {
                externals.add(external);
                if (writesExternally(external, typeName, method)) {
                    stateChanges.add(external);
                }
            }
            if (!call.isImplicitThis() && contains(MUTATORS, method) && !contains(IMMUTABLE_TYPES, typeName)) {
                String field = fieldOf(receiver);
                if (field != null) {
                    stateChanges.add("field " + field);
                } else if (receiver instanceof VariableExpression variable
                        && variable.getAccessedVariable() instanceof org.codehaus.groovy.ast.Parameter parameter) {
                    stateChanges.add("parameter " + parameter.getName());
                }
            }
            if (setsSystemProperty(typeName, method)) {
                stateChanges.add("environment");
            }
            String property = setterProperty(method, call.getArguments());
            if (property != null && (call.isImplicitThis() || receiver instanceof VariableExpression self
                    && self.isThisExpression())) {
                stateChanges.add("field " + property);
            } else if (property != null && !isLocal(receiver)) {
                stateChanges.add("property " + property);
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            String owner = call.getOwnerType() == null ? null : call.getOwnerType().getName();
            String external = externalOf(owner, call.getMethod(), null);
            if (external != null) {
                externals.add(external);
                if (writesExternally(external, owner, call.getMethod())) {
                    stateChanges.add(external);
                }
            }
            if (setsSystemProperty(owner, call.getMethod())) {
                stateChanges.add("environment");
            }
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            if (call.getType() != null) {
                String external = externalOf(call.getType().getName(), "<init>", null);
                if (external != null) {
                    externals.add(external);
                    if (writesExternally(external, call.getType().getName(), "<init>")) {
                        stateChanges.add(external);
                    }
                }
            }
            super.visitConstructorCallExpression(call);
        }

        /** The property a one-argument {@code setTitle(x)} call sets, or {@code null}. */
        private static String setterProperty(String method, Expression arguments) {
            if (method == null || method.length() < 4 || !method.startsWith("set")
                    || !Character.isUpperCase(method.charAt(3))
                    || !(arguments instanceof TupleExpression tuple) || tuple.getExpressions().size() != 1) {
                return null;
            }
            return Character.toLowerCase(method.charAt(3)) + method.substring(4);
        }

        private static boolean isLocal(Expression expression) {
            return expression instanceof VariableExpression variable && !variable.isThisExpression()
                    && variable.getAccessedVariable() instanceof VariableExpression;
        }

        /** The field of {@code this} an expression reads or writes, or {@code null}. */
        private String fieldOf(Expression expression) {
            if (expression instanceof VariableExpression variable) {
                Variable accessed = variable.getAccessedVariable();
                if (accessed instanceof FieldNode field) {
                    return field.getName();
                }
                if (accessed instanceof PropertyNode property) {
                    return property.getName();
                }
                return null;
            }
            if (expression instanceof PropertyExpression property
                    && property.getObjectExpression() instanceof VariableExpression target
                    && target.isThisExpression() && owner != null) {
                String name = property.getPropertyAsString();
                if (name != null && (owner.getDeclaredField(name) != null || owner.getProperty(name) != null)) {
                    return name;
                }
            }
            return null;
        }

        private String receiverTypeName(Expression receiver, boolean implicitThis) {
            if (implicitThis) {
                return owner == null ? null : owner.getName();
            }
            if (receiver instanceof ClassExpression classExpression) {
                return classExpression.getType().getName();
            }
            if (receiver instanceof VariableExpression variable) {
                if (variable.isThisExpression()) {
                    return owner == null ? null : owner.getName();
                }
                Variable accessed = variable.getAccessedVariable();
                if (accessed != null && !accessed.isDynamicTyped() && accessed.getOriginType() != null) {
                    return accessed.getOriginType().getName();
                }
                return null;
            }
            if (receiver instanceof ConstructorCallExpression constructor) {
                return constructor.getType().getName();
            }
            if (receiver instanceof ConstantExpression constant && constant.getValue() instanceof String) {
                return ClassHelper.STRING_TYPE.getName();
            }
            if (receiver instanceof GStringExpression) {
                return ClassHelper.GSTRING_TYPE.getName();
            }
            if (receiver instanceof ListExpression) {
                return "java.util.List";
            }
            if (receiver instanceof MethodCallExpression || receiver instanceof PropertyExpression) {
                return null;
            }
            return null;
        }
    }

    /** Whether a type is boolean. */
    public static boolean isBoolean(ClassNode type) {
        return type != null && (ClassHelper.boolean_TYPE.equals(type) || ClassHelper.Boolean_TYPE.equals(type));
    }

}
