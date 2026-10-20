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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.model.Diagnostic.Severity;

/**
 * Jenkins pipeline checks, GS030 to GS033, on code that runs on Jenkins' CPS runtime (a Jenkinsfile, a shared
 * library's {@code vars/} and {@code src/}), and mutable static state, GS038, everywhere outside tests (an error in
 * Jenkins code, where one controller JVM shares it between builds). Without the Jenkins classpath, pipeline steps are
 * recognised by name ({@link #STEPS}); a call the class declares itself is never taken for a step.
 */
public final class JenkinsFinder {

    /** Pipeline steps recognised by name when the Jenkins classpath is not available. */
    static final Set<String> STEPS = Set.of("sh", "bat", "powershell", "pwsh", "echo", "node", "stage", "sleep",
            "input", "readFile", "writeFile", "withCredentials", "checkout", "build", "parallel", "timeout", "retry",
            "dir", "withEnv", "error", "archiveArtifacts", "stash", "unstash", "git", "junit", "fileExists",
            "deleteDir", "waitUntil", "milestone", "lock", "catchError", "unarchive", "readJSON", "readYaml",
            "httpRequest", "emailext", "mail", "cleanWs", "script");
    /** Steps that hand their text to a shell. */
    static final Set<String> SHELL_STEPS = Set.of("sh", "bat", "powershell", "pwsh");
    /** Names that look like secrets: a guess, so only ever information. */
    static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|apikey|api_key|credential|private_?key|passphrase).*");
    /** Types a CPS pipeline cannot serialise when it pauses at a step. */
    static final Map<String, String> NON_SERIALISABLE = Map.of(
            "java.util.regex.Matcher", "a Matcher",
            "java.util.Iterator", "an Iterator",
            "java.util.ListIterator", "an Iterator",
            "groovy.json.internal.LazyMap", "a LazyMap from JsonSlurper",
            "java.util.Scanner", "a Scanner");
    /** Mutable types a static final field can still be (its contents change). */
    static final Set<String> MUTABLE_TYPES = Set.of("java.util.ArrayList", "java.util.LinkedList",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.ArrayDeque", "java.util.Stack",
            "java.util.Vector", "java.util.Hashtable", "java.util.Properties", "java.util.Date",
            "java.lang.StringBuilder", "java.lang.StringBuffer", "java.text.SimpleDateFormat",
            "java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.ConcurrentLinkedQueue",
            "java.util.concurrent.CopyOnWriteArrayList", "java.util.concurrent.CopyOnWriteArraySet",
            "java.util.concurrent.ConcurrentSkipListMap", "java.util.concurrent.ConcurrentSkipListSet",
            "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong",
            "java.util.concurrent.atomic.AtomicBoolean", "java.util.concurrent.atomic.AtomicReference");
    /** Of {@link #MUTABLE_TYPES}, those that are safe across threads: still shared state, so only information. */
    static final Set<String> THREAD_SAFE = Set.of("java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentLinkedQueue", "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.CopyOnWriteArraySet", "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.concurrent.ConcurrentSkipListSet", "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong", "java.util.concurrent.atomic.AtomicBoolean",
            "java.util.concurrent.atomic.AtomicReference");

    private JenkinsFinder() {
    }

    /** Adds the Jenkins and static state findings of one document to {@code context.out()}. */
    public static void find(PracticeContext context) {
        if (context.test()) {
            return;
        }
        for (ClassNode classNode : context.module().getClasses()) {
            staticState(context, classNode);
            if (context.jenkins()) {
                new Visitor(context).visitClass(classNode);
                for (MethodNode method : classNode.getMethods()) {
                    if (!isNonCps(method) && method.getCode() != null) {
                        liveAcrossSteps(context, classNode, method);
                    }
                }
            }
        }
    }

    /** GS038: static fields that are not final, or final ones holding a mutable value built in place. */
    static void staticState(PracticeContext context, ClassNode classNode) {
        for (FieldNode field : classNode.getFields()) {
            // A property's backing field is synthetic, so generated fields are told apart by position and name.
            if (!field.isStatic() || field.isEnum() || field.getLineNumber() < 1
                    || field.getName().startsWith("$") || field.getName().startsWith("__")) {
                continue;
            }
            String where = context.jenkins() ? "; in Jenkins code one controller JVM shares it between every build "
                    + "and every branch, and it lasts until the library is reloaded" : "";
            if (!field.isFinal()) {
                report038(context, field, Severity.WARNING, "static field '" + field.getName() + "' is not final: "
                        + "every caller shares it, and every change to it is a side effect" + where);
                continue;
            }
            String mutable = mutableValue(field.getInitialValueExpression());
            if (mutable != null) {
                Severity ceiling = THREAD_SAFE.contains(mutable) ? Severity.INFORMATION : Severity.WARNING;
                report038(context, field, ceiling, "static final field '" + field.getName() + "' holds "
                        + simpleName(mutable) + ": the reference is final, the contents are not" + where);
            }
        }
    }

    private static void report038(PracticeContext context, FieldNode field, Severity ceiling, String message) {
        if (context.jenkins()) {
            context.reportAtLeast(Severity.ERROR, "GS038", field, message);
        } else {
            context.reportAtMost(ceiling, "GS038", field, message);
        }
    }

    /** The mutable type a static initialiser builds in place, or {@code null}. */
    static String mutableValue(Expression value) {
        Expression inner = value instanceof CastExpression cast ? cast.getExpression() : value;
        if (inner instanceof ListExpression) {
            return value instanceof CastExpression cast ? cast.getType().getName() : "java.util.ArrayList";
        }
        if (inner instanceof MapExpression) {
            return value instanceof CastExpression cast ? cast.getType().getName() : "java.util.LinkedHashMap";
        }
        if (inner instanceof ArrayExpression) {
            return "an array";
        }
        if (inner instanceof ConstructorCallExpression constructor
                && MUTABLE_TYPES.contains(constructor.getType().getName())) {
            return constructor.getType().getName();
        }
        return null;
    }

    private static String simpleName(String type) {
        if (!type.contains(".")) {
            return type;
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return (simple.matches("^[AEIOU].*") ? "an " : "a ") + simple;
    }

    static boolean isNonCps(MethodNode method) {
        for (AnnotationNode annotation : method.getAnnotations()) {
            if ("NonCPS".equals(annotation.getClassNode().getNameWithoutPackage())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a call is a pipeline step: a step name the class does not declare itself, on itself or on steps. */
    static boolean isStep(MethodCallExpression call, ClassNode owner) {
        String name = call.getMethodAsString();
        if (name == null || !STEPS.contains(name) || !owner.getMethods(name).isEmpty()) {
            return false;
        }
        if (call.isImplicitThis()) {
            return true;
        }
        Expression receiver = call.getObjectExpression();
        return receiver instanceof VariableExpression variable
                && ("steps".equals(variable.getName()) || "script".equals(variable.getName()));
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final PracticeContext context;
        /** Variables bound by the enclosing withCredentials blocks, innermost last. */
        private final Deque<Set<String>> credentials = new ArrayDeque<>();
        private ClassNode currentClass;
        private boolean nonCps;

        Visitor(PracticeContext context) {
            this.context = context;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return context.module().getContext();
        }

        @Override
        public void visitClass(ClassNode node) {
            currentClass = node;
            super.visitClass(node);
        }

        @Override
        protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            nonCps = isNonCps(node);
            super.visitConstructorOrMethod(node, isConstructor);
            nonCps = false;
        }

        /** GS033: {@code @CompileStatic} on CPS code. */
        @Override
        public void visitAnnotations(AnnotatedNode node) {
            for (AnnotationNode annotation : node.getAnnotations()) {
                if ("CompileStatic".equals(annotation.getClassNode().getNameWithoutPackage())) {
                    context.report("GS033", annotation, "@CompileStatic code is not transformed for CPS: pipeline "
                            + "steps called from it fail, and it cannot be paused or resumed; leave pipeline code "
                            + "dynamic and move pure computation into @NonCPS methods");
                }
            }
            super.visitAnnotations(node);
        }

        /** GS033: {@code new File(...)} in pipeline code. */
        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            if ("java.io.File".equals(call.getType().getName())) {
                context.report("GS033", call, "new File reads the Jenkins controller's disk, not the agent's "
                        + "workspace; use readFile, writeFile, fileExists or findFiles");
            }
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            if (nonCps && isStep(call, currentClass)) {
                context.report("GS031", call, "@NonCPS method calls the pipeline step '" + name + "': steps "
                        + "need the CPS runtime, so it fails or its result is lost; call the step from CPS code "
                        + "and pass the result in");
            }
            if (SHELL_STEPS.contains(name) && (call.isImplicitThis() || isStep(call, currentClass)
                    || call.getObjectExpression() instanceof VariableExpression)) {
                GStringExpression script = scriptOf(call.getArguments());
                if (script != null) {
                    interpolatedSecrets(name, script);
                }
            }
            if ("withCredentials".equals(name)) {
                credentials.push(boundVariables(call.getArguments()));
                super.visitMethodCallExpression(call);
                credentials.pop();
                return;
            }
            super.visitMethodCallExpression(call);
        }

        /** GS030: the secrets a shell step's GString interpolates. */
        private void interpolatedSecrets(String step, GStringExpression script) {
            Set<String> bound = new HashSet<>();
            credentials.forEach(bound::addAll);
            for (Expression value : script.getValues()) {
                String variable = secretName(value);
                if (variable == null) {
                    continue;
                }
                String advice = " into the " + step + " script: Groovy builds the text, so the secret reaches the "
                        + "command line, the process list and possibly the log; use single quotes and let the "
                        + "shell read it from the environment ('$" + variable + "')";
                if (bound.contains(variable)) {
                    context.report("GS030", value, "'" + variable + "' from withCredentials is interpolated"
                            + advice);
                } else if (SECRET_NAME.matcher(variable).matches()) {
                    context.reportAtMost(Severity.INFORMATION, "GS030", value, "'" + variable + "' looks like a "
                            + "secret and is interpolated" + advice);
                }
            }
        }

        /** The variable a GString value names: {@code ${X}}, {@code ${env.X}} or {@code ${params.X}}. */
        private static String secretName(Expression value) {
            if (value instanceof VariableExpression variable) {
                return variable.getName();
            }
            if (value instanceof PropertyExpression property
                    && property.getObjectExpression() instanceof VariableExpression owner
                    && ("env".equals(owner.getName()) || "params".equals(owner.getName()))) {
                return property.getPropertyAsString();
            }
            return null;
        }

        /** The GString a shell step runs: its first argument, or its {@code script:} named argument. */
        private static GStringExpression scriptOf(Expression arguments) {
            if (!(arguments instanceof TupleExpression tuple) || tuple.getExpressions().isEmpty()) {
                return null;
            }
            Expression first = tuple.getExpression(0);
            if (first instanceof GStringExpression gstring) {
                return gstring;
            }
            if (first instanceof MapExpression named) {
                for (MapEntryExpression entry : named.getMapEntryExpressions()) {
                    if (entry.getKeyExpression() instanceof ConstantExpression key && "script".equals(key.getValue())
                            && entry.getValueExpression() instanceof GStringExpression gstring) {
                        return gstring;
                    }
                }
            }
            return null;
        }

        /**
         * The variables a withCredentials call binds: every {@code variable} and {@code ...Variable} argument of its
         * bindings except {@code usernameVariable} (a user name is not the secret).
         */
        private static Set<String> boundVariables(Expression arguments) {
            Set<String> names = new HashSet<>();
            if (!(arguments instanceof ArgumentListExpression list) || list.getExpressions().isEmpty()
                    || !(list.getExpression(0) instanceof ListExpression bindings)) {
                return names;
            }
            for (Expression binding : bindings.getExpressions()) {
                if (!(binding instanceof MethodCallExpression call)
                        || !(call.getArguments() instanceof TupleExpression tuple)) {
                    continue;
                }
                for (Expression argument : tuple.getExpressions()) {
                    if (!(argument instanceof MapExpression named)) {
                        continue;
                    }
                    for (MapEntryExpression entry : named.getMapEntryExpressions()) {
                        if (entry.getKeyExpression() instanceof ConstantExpression key
                                && key.getValue() instanceof String keyName
                                && (keyName.equals("variable") || keyName.endsWith("Variable"))
                                && !keyName.equals("usernameVariable")
                                && entry.getValueExpression() instanceof ConstantExpression value
                                && value.getValue() instanceof String bound) {
                            names.add(bound);
                        }
                    }
                }
            }
            return names;
        }
    }

    /**
     * GS032: a local holding a value CPS cannot serialise, still in use after a pipeline step (read after it, or
     * read in a loop that repeats the step). The pipeline is saved at every step, so it fails there with
     * NotSerializableException.
     */
    static void liveAcrossSteps(PracticeContext context, ClassNode owner, MethodNode method) {
        Map<VariableExpression, String> locals = new LinkedHashMap<>();
        List<MethodCallExpression> steps = new ArrayList<>();
        List<VariableExpression> reads = new ArrayList<>();
        List<Statement> loops = new ArrayList<>();
        method.getCode().visit(new CodeVisitorSupport() {
            private final Set<VariableExpression> written = new HashSet<>();

            @Override
            public void visitDeclarationExpression(DeclarationExpression expression) {
                if (!expression.isMultipleAssignmentDeclaration()) {
                    VariableExpression variable = expression.getVariableExpression();
                    String kind = nonSerialisable(variable, expression.getRightExpression());
                    if (kind != null) {
                        locals.put(variable, kind);
                    }
                    written.add(variable);
                }
                super.visitDeclarationExpression(expression);
            }

            @Override
            public void visitBinaryExpression(BinaryExpression expression) {
                if (expression.getOperation().getType() == Types.ASSIGN
                        && expression.getLeftExpression() instanceof VariableExpression target) {
                    written.add(target);
                }
                super.visitBinaryExpression(expression);
            }

            @Override
            public void visitVariableExpression(VariableExpression expression) {
                if (!written.contains(expression)) {
                    reads.add(expression);
                }
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (isStep(call, owner)) {
                    steps.add(call);
                }
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitWhileLoop(WhileStatement loop) {
                loops.add(loop);
                super.visitWhileLoop(loop);
            }

            @Override
            public void visitDoWhileLoop(DoWhileStatement loop) {
                loops.add(loop);
                super.visitDoWhileLoop(loop);
            }

            @Override
            public void visitForLoop(ForStatement loop) {
                loops.add(loop);
                super.visitForLoop(loop);
            }
        });
        for (Map.Entry<VariableExpression, String> local : locals.entrySet()) {
            VariableExpression variable = local.getKey();
            for (MethodCallExpression step : steps) {
                if (!after(step, variable) || !usedAcross(variable, step, reads, loops)) {
                    continue;
                }
                context.report("GS032", variable, "'" + variable.getName() + "' holds " + local.getValue()
                        + ", which CPS cannot serialise, and is still in use after the " + step.getMethodAsString()
                        + " step on line " + step.getLineNumber() + ": the pipeline is saved at every step and fails "
                        + "there; finish with it before the step, set it to null, or move the work into an @NonCPS "
                        + "method");
                break;
            }
        }
    }

    private static boolean usedAcross(VariableExpression variable, MethodCallExpression step,
            List<VariableExpression> reads, List<Statement> loops) {
        for (VariableExpression read : reads) {
            if (!read.getName().equals(variable.getName()) || read == variable) {
                continue;
            }
            if (endsBefore(step, read)) {
                return true;
            }
            for (Statement loop : loops) {
                if (contains(loop, step) && contains(loop, read) && !contains(loop, variable)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The label of a non-serialisable local, from its declared type or the expression it is built from. */
    static String nonSerialisable(VariableExpression variable, Expression value) {
        if (!variable.isDynamicTyped()) {
            String declared = NON_SERIALISABLE.get(variable.getOriginType().getName());
            if (declared != null) {
                return declared;
            }
        }
        if (value instanceof BinaryExpression binary && binary.getOperation().getType() == Types.FIND_REGEX) {
            return NON_SERIALISABLE.get("java.util.regex.Matcher");
        }
        if (value instanceof MethodCallExpression call) {
            String name = call.getMethodAsString();
            if ("iterator".equals(name)) {
                return NON_SERIALISABLE.get("java.util.Iterator");
            }
            if ("matcher".equals(name)) {
                return NON_SERIALISABLE.get("java.util.regex.Matcher");
            }
            if (("parseText".equals(name) || "parse".equals(name))
                    && call.getObjectExpression() instanceof ConstructorCallExpression slurper
                    && "groovy.json.JsonSlurper".equals(slurper.getType().getName())) {
                return NON_SERIALISABLE.get("groovy.json.internal.LazyMap");
            }
        }
        return null;
    }

    private static long position(int line, int column) {
        return (long) line * 100_000L + column;
    }

    /** Whether {@code later} starts after {@code earlier} starts. */
    private static boolean after(ASTNode later, ASTNode earlier) {
        return position(later.getLineNumber(), later.getColumnNumber())
                > position(earlier.getLineNumber(), earlier.getColumnNumber());
    }

    /** Whether {@code first} ends before {@code second} starts. */
    private static boolean endsBefore(ASTNode first, ASTNode second) {
        return position(first.getLastLineNumber(), first.getLastColumnNumber())
                <= position(second.getLineNumber(), second.getColumnNumber());
    }

    private static boolean contains(ASTNode outer, ASTNode inner) {
        return position(outer.getLineNumber(), outer.getColumnNumber())
                <= position(inner.getLineNumber(), inner.getColumnNumber())
                && position(inner.getLastLineNumber(), inner.getLastColumnNumber())
                <= position(outer.getLastLineNumber(), outer.getLastColumnNumber());
    }
}
