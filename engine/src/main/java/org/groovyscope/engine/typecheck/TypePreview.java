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
package org.groovyscope.engine.typecheck;

import groovy.lang.GroovyClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.trait.Traits;
import org.groovyscope.engine.compile.CompilationScaffold;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.compile.RetryRounds;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.semantics.DynamicCodeFinder;
import static org.groovyscope.engine.util.Closeables.closeQuietly;

/**
 * The type preview (GS077): Groovy's static type checker run speculatively over the classes of candidate texts that
 * carry neither {@code @CompileStatic} nor {@code @TypeChecked}, as the {@code @CompileStatic} verdict of the fix
 * does, but without changing the text: the compile adds {@code @TypeChecked} to those classes in the tree, checks
 * the texts alone against the classes of the last full compile, and reports what the checker says, in its own words.
 * The classes already static are checked as well, so their errors come out of the same compile.
 */
public final class TypePreview {

    /** The practice code of a speculative error. */
    public static final String CODE = "GS077";
    /** What a GS077 message starts with; the checker's own message follows. */
    public static final String PREFIX = "would not type check: ";
    static final String STC_PREFIX = "[Static type checking] - ";
    static final Set<String> STATIC_ANNOTATIONS = Set.of("groovy.transform.CompileStatic",
            "groovy.transform.TypeChecked");
    static final Set<String> TYPING_ANNOTATIONS = Set.of("groovy.transform.CompileStatic",
            "groovy.transform.TypeChecked", "groovy.transform.CompileDynamic");
    /** Rounds after the first that drop the texts that do not resolve, as the type check does. */
    static final int MAX_RETRIES = 8;

    private TypePreview() {
    }

    /**
     * One error of the checker.
     *
     * @param range where it is
     * @param message the checker's message, its {@code [Static type checking]} prefix removed
     * @param speculative whether it lies in a class the preview made static (a GS077 finding), rather than in one
     *     the code itself declares static (a type error)
     */
    public record Error(Range range, String message, boolean speculative) {
    }

    /**
     * What a preview compile found.
     *
     * @param checked whether the checker ran (a text that does not resolve stops the compile before it)
     * @param errors the checker's errors per document, in source order
     */
    public record Outcome(boolean checked, Map<String, List<Error>> errors) {
    }

    /**
     * The classes of a module the preview checks speculatively, by name: every class without a typing annotation
     * on itself or a class around it, leaving out scripts, interfaces, traits, anonymous classes (checked with the
     * class that holds them), Spock specifications and the classes {@link DynamicCodeFinder#dynamicFeature} names.
     */
    public static Set<String> candidates(ModuleNode module) {
        Set<String> names = new LinkedHashSet<>();
        if (module == null) {
            return names;
        }
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.isScript() || classNode.isInterface() || classNode.isAnnotationDefinition()
                    || classNode.isSynthetic() || classNode.getLineNumber() < 1 || Traits.isTrait(classNode)
                    || classNode instanceof InnerClassNode inner && inner.isAnonymous() || typed(classNode)
                    || specification(classNode) || DynamicCodeFinder.dynamicFeature(classNode) != null) {
                continue;
            }
            names.add(classNode.getName());
        }
        return names;
    }

    /** Whether a class or one around it chose its typing ({@code @CompileStatic}, {@code @TypeChecked}, dynamic). */
    static boolean typed(ClassNode classNode) {
        for (ClassNode holder = classNode; holder != null; holder = holder.getOuterClass()) {
            if (annotated(holder, TYPING_ANNOTATIONS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean specification(ClassNode classNode) {
        for (ClassNode parent = classNode.getUnresolvedSuperClass(false); parent != null;
                parent = parent.getUnresolvedSuperClass(false)) {
            if (parent.getName().endsWith("Specification")) {
                return true;
            }
        }
        return false;
    }

    static boolean annotated(AnnotatedNode node, Set<String> names) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            if (names.contains(annotation.getClassNode().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles {@code texts} alone, through instruction selection, against {@code known} (the classes of the last
     * full compile by name, the texts' own documents left out), with {@code @TypeChecked} added in the tree to the
     * classes named in {@code speculative}. Nothing is written or loaded. {@code reader}, when given, sees the
     * checked trees (with the checker's inferred types on their nodes) before the compile's loaders are released.
     * A text that does not resolve stops the whole compile before the checker runs, so the texts with errors are
     * dropped and the rest compiled again, within the {@link RetryRounds} budget; a dropped text gets no entry.
     */
    public static Outcome check(Map<String, String> texts, List<Path> classpath, boolean trust,
            Map<String, ClassNode> known, Set<String> speculative, Consumer<Map<String, ModuleNode>> reader) {
        Map<String, String> remaining = new LinkedHashMap<>(texts);
        RetryRounds rounds = new RetryRounds(MAX_RETRIES);
        for (int round = 0; rounds.allows(round) && !remaining.isEmpty() && !rounds.overBudget(); round++) {
            org.groovyscope.engine.concurrent.Cancellation.checkpoint();
            Set<String> broken = new LinkedHashSet<>();
            Outcome outcome = checkOnce(remaining, classpath, trust, known, speculative, reader, broken);
            rounds.finished(round);
            if (outcome.checked() || broken.isEmpty()) {
                return outcome;
            }
            remaining.keySet().removeAll(broken);
        }
        return new Outcome(false, Map.of());
    }

    private static Outcome checkOnce(Map<String, String> texts, List<Path> classpath, boolean trust,
            Map<String, ClassNode> known, Set<String> speculative, Consumer<Map<String, ModuleNode>> reader,
            Set<String> broken) {
        CompilerConfiguration configuration = CompilationScaffold.configuration();
        configuration.addCompilationCustomizers(new Speculation(speculative));
        GroovyClassLoader loader = CompilationScaffold.resolutionLoader(configuration, classpath);
        GroovyClassLoader transformLoader = CompilationScaffold.transformLoader(loader, configuration, trust);
        try {
            CompilationUnit unit = new CompilationUnit(configuration, null, loader, transformLoader);
            if (!known.isEmpty()) {
                unit.setClassNodeResolver(GroovyCompiler.knownClasses(known));
            }
            Map<String, SourceUnit> units = new LinkedHashMap<>();
            for (Map.Entry<String, String> text : texts.entrySet()) {
                units.put(text.getKey(), unit.addSource(text.getKey(), text.getValue()));
            }
            try {
                unit.compile(Phases.INSTRUCTION_SELECTION);
            } catch (CompilationFailedException e) {
                // the errors are read from the collectors below
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
                // a compiler bug or a class that fails to link: no preview, never a failed request
                org.groovyscope.engine.log.EngineLog.debug("type preview skipped: %s", e);
                return new Outcome(false, Map.of());
            }
            if (unit.getPhase() < Phases.INSTRUCTION_SELECTION) {
                broken.addAll(withErrors(unit.getErrorCollector(), texts.keySet()));
                for (SourceUnit source : units.values()) {
                    broken.addAll(withErrors(source.getErrorCollector(), texts.keySet()));
                }
                return new Outcome(false, Map.of());
            }
            Map<String, ModuleNode> modules = new HashMap<>();
            for (Map.Entry<String, SourceUnit> source : units.entrySet()) {
                if (source.getValue().getAST() != null) {
                    modules.put(source.getKey(), source.getValue().getAST());
                }
            }
            Map<String, List<Error>> errors = new LinkedHashMap<>();
            for (String uri : texts.keySet()) {
                errors.put(uri, new ArrayList<>());
            }
            Unknowns unknowns = new Unknowns(unit, modules);
            collect(unit.getErrorCollector(), modules, speculative, unknowns, errors);
            for (SourceUnit source : units.values()) {
                collect(source.getErrorCollector(), modules, speculative, unknowns, errors);
            }
            for (List<Error> list : errors.values()) {
                list.sort((a, b) -> a.range().start().compareTo(b.range().start()));
            }
            if (reader != null) {
                reader.accept(modules);
            }
            return new Outcome(true, errors);
        } finally {
            closeQuietly(loader);
            if (transformLoader != loader) {
                closeQuietly(transformLoader);
            }
        }
    }

    /** The texts an error collector reports an error in. */
    private static Set<String> withErrors(ErrorCollector collector, Set<String> uris) {
        Set<String> found = new LinkedHashSet<>();
        if (collector == null || collector.getErrors() == null) {
            return found;
        }
        for (Message message : collector.getErrors()) {
            if (message instanceof SyntaxErrorMessage syntax && uris.contains(syntax.getCause().getSourceLocator())) {
                found.add(syntax.getCause().getSourceLocator());
            }
        }
        return found;
    }

    private static void collect(ErrorCollector collector, Map<String, ModuleNode> modules, Set<String> speculative,
            Unknowns unknowns, Map<String, List<Error>> out) {
        if (collector == null || collector.getErrors() == null) {
            return;
        }
        for (Message message : collector.getErrors()) {
            if (!(message instanceof SyntaxErrorMessage syntax)) {
                continue;
            }
            SyntaxException cause = syntax.getCause();
            String uri = cause.getSourceLocator();
            String text = cause.getOriginalMessage() == null ? "" : cause.getOriginalMessage().strip();
            if (uri == null || !out.containsKey(uri) || !text.startsWith(STC_PREFIX)) {
                continue;
            }
            String stripped = TypeCheckAnalyzer.stripPrefix(text);
            boolean guess = speculative(modules.get(uri), cause.getLine(), speculative);
            if (guess && unknowns.dynamic(uri, cause.getLine(), stripped)) {
                continue;
            }
            Error error = new Error(GroovyCompiler.rangeOf(cause), stripped, guess);
            if (!out.get(uri).contains(error)) {
                out.get(uri).add(error);
            }
        }
    }

    /**
     * Tells apart the errors that say only that dynamic code is dynamic: the checker types what it cannot know as
     * {@code Object}, cannot see a variable a script binding or a closure delegate supplies, cannot follow a
     * computed property name, and cannot know what a closure's delegate (a builder, a DSL) answers, or what a type
     * that answers any property or method ({@code GPathResult}, a {@code Closure}, a class with {@code methodMissing})
     * holds. The preview reports none of those: nothing is known there, so nothing is mixed.
     */
    static final class Unknowns {

        static final java.util.regex.Pattern RECEIVER = java.util.regex.Pattern.compile(
                "for class: ([\\w.$\\[\\]]+)|(?:method|call|constructor) (?:<[^>]*> )?#?([\\w.$\\[\\]]+)#");
        /** A bare type parameter ({@code T}) among the types a message names: a type the checker does not know. */
        static final java.util.regex.Pattern TYPE_PARAMETER =
                java.util.regex.Pattern.compile("[(\\[,]\\s*[A-Z]\\s*[),\\]]");
        static final Set<String> DYNAMIC_HOOKS = Set.of("getProperty", "propertyMissing", "methodMissing",
                "invokeMethod");
        static final Set<String> STATIC_BASES = Set.of("groovy.lang.GroovyObject", "groovy.lang.GroovyObjectSupport",
                "java.lang.Object");

        private final CompilationUnit unit;
        private final Map<String, ModuleNode> modules;
        private final Map<String, List<int[]>> closures = new HashMap<>();
        private final Map<String, Boolean> answersAnything = new HashMap<>();

        Unknowns(CompilationUnit unit, Map<String, ModuleNode> modules) {
            this.unit = unit;
            this.modules = modules;
        }

        boolean dynamic(String uri, int line, String message) {
            if (message.contains("java.lang.Object") || message.contains("is undeclared")
                    || message.startsWith("No such property: null ") || TYPE_PARAMETER.matcher(message).find()) {
                return true;
            }
            java.util.regex.Matcher matcher = RECEIVER.matcher(message);
            if (!matcher.find()) {
                return false;
            }
            String receiver = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (inClosure(uri, line) && declaredHere(receiver)) {
                return true; // a closure's delegate may answer what its owner does not declare
            }
            return answersAnything.computeIfAbsent(receiver, this::answersAnything);
        }

        private boolean declaredHere(String name) {
            for (ModuleNode module : modules.values()) {
                for (ClassNode classNode : module.getClasses()) {
                    if (classNode.getName().equals(name)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean inClosure(String uri, int line) {
            for (int[] span : closures.computeIfAbsent(uri, this::closureSpans)) {
                if (span[0] <= line && line <= span[1]) {
                    return true;
                }
            }
            return false;
        }

        private List<int[]> closureSpans(String uri) {
            List<int[]> spans = new ArrayList<>();
            ModuleNode module = modules.get(uri);
            if (module == null) {
                return spans;
            }
            org.codehaus.groovy.ast.ClassCodeVisitorSupport visitor =
                    new org.codehaus.groovy.ast.ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return null;
                }

                @Override
                public void visitClosureExpression(org.codehaus.groovy.ast.expr.ClosureExpression expression) {
                    if (expression.getLineNumber() > 0) {
                        spans.add(new int[] {expression.getLineNumber(), expression.getLastLineNumber()});
                    }
                    super.visitClosureExpression(expression);
                }
            };
            for (ClassNode classNode : module.getClasses()) {
                visitor.visitClass(classNode);
            }
            return spans;
        }

        /**
         * Whether a type answers properties or methods it does not declare: it overrides a dispatch hook, or has a
         * {@code get(String)} that Groovy calls for a property it lacks. A type that cannot be found at all (a type
         * parameter) is unknown, and so answers anything as far as the preview can tell.
         */
        private boolean answersAnything(String name) {
            if (name.endsWith("[]")) {
                return false;
            }
            ClassNode type = null;
            for (ModuleNode module : modules.values()) {
                for (ClassNode classNode : module.getClasses()) {
                    if (classNode.getName().equals(name)) {
                        type = classNode;
                    }
                }
            }
            if (type == null) {
                try {
                    org.codehaus.groovy.control.ClassNodeResolver.LookupResult found = unit.getClassNodeResolver()
                            .resolveName(name, unit);
                    type = found == null ? null : found.getClassNode();
                } catch (RuntimeException | LinkageError e) {
                    return false;
                }
            }
            if (type == null || !org.groovyscope.engine.semantics.Members.isKnown(type)) {
                return true;
            }
            for (ClassNode holder : org.groovyscope.engine.semantics.Members.hierarchy(type)) {
                if (STATIC_BASES.contains(holder.getName())) {
                    continue;
                }
                for (String hook : DYNAMIC_HOOKS) {
                    if (!org.groovyscope.engine.semantics.Members.readable(() -> holder.getDeclaredMethods(hook))
                            .isEmpty()) {
                        return true;
                    }
                }
                for (MethodNode getter : org.groovyscope.engine.semantics.Members.readable(
                        () -> holder.getDeclaredMethods("get"))) {
                    if (getter.getParameters().length == 1 && getter.getParameters()[0].getType().getName()
                            .equals("java.lang.String")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Whether an error on {@code line} lies in a class the preview made static: the innermost class around it, or
     * the nearest class around that one that the preview annotated, unless a method or class in between declares
     * its own static typing (then the error is the code's own).
     */
    static boolean speculative(ModuleNode module, int line, Set<String> speculative) {
        if (module == null) {
            return false;
        }
        ClassNode innermost = null;
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() <= line && line <= classNode.getLastLineNumber()
                    && (innermost == null || classNode.getLastLineNumber() - classNode.getLineNumber()
                    < innermost.getLastLineNumber() - innermost.getLineNumber())) {
                innermost = classNode;
            }
        }
        for (ClassNode holder = innermost; holder != null; holder = holder.getOuterClass()) {
            for (MethodNode method : holder.getMethods()) {
                if (method.getLineNumber() <= line && line <= method.getLastLineNumber()
                        && annotated(method, STATIC_ANNOTATIONS)) {
                    return false;
                }
            }
            if (speculative.contains(holder.getName())) {
                return true;
            }
            if (annotated(holder, STATIC_ANNOTATIONS)) {
                return false;
            }
        }
        return false;
    }

    /** Adds {@code @TypeChecked} to the named classes, in the tree, before the transformations are collected. */
    static final class Speculation extends CompilationCustomizer {

        private final Set<String> names;

        Speculation(Set<String> names) {
            super(CompilePhase.CONVERSION);
            this.names = Set.copyOf(names);
        }

        @Override
        public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            if (names.contains(classNode.getName())) {
                classNode.addAnnotation(new AnnotationNode(ClassHelper.make(groovy.transform.TypeChecked.class)));
            }
        }
    }
}
