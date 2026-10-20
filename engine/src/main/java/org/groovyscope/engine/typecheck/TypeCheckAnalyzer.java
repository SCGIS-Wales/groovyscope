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

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.builder.CompilerCustomizationBuilder;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.groovyscope.engine.compile.RetryBudget;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.compile.WorkspaceParentClassLoader;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;

/**
 * Runs Groovy's static type checker (to {@code INSTRUCTION_SELECTION}, still no bytecode) over the
 * workspace with the configured Groovy 6 checkers added to every statically typed class, and reports its
 * errors. Dynamic classes are untouched, as the checkers only apply where {@code @CompileStatic} or
 * {@code @TypeChecked} already is.
 */
public final class TypeCheckAnalyzer {

    /** Reported as the diagnostic source. */
    public static final String SOURCE = "groovyc-typecheck";
    private static final int MAX_RETRIES = 16;
    static final String NULL_CHECKER = "groovy.typecheckers.NullChecker";
    static final String STRICT_NULL_CHECKER = NULL_CHECKER + "(strict: true)";
    static final String SQL_INJECTION_CHECKER = "groovy.typecheckers.SqlInjectionChecker";
    static final String PURITY_CHECKER = "groovy.typecheckers.PurityChecker";
    static final String MODIFIES_CHECKER = "groovy.typecheckers.ModifiesChecker";
    static final String REGEX_CHECKER = "groovy.typecheckers.RegexChecker";
    static final String FORMAT_STRING_CHECKER = "groovy.typecheckers.FormatStringChecker";
    static final String COMBINER_CHECKER = "groovy.typecheckers.CombinerChecker";

    /**
     * Whether a source can produce type-check findings at all: the checker and the Groovy 6 extensions apply only
     * where {@code @CompileStatic} or {@code @TypeChecked} is written, so a workspace, or a set of requested files,
     * without either word never needs the type-check compile. Textual on purpose: cheaper than walking the tree,
     * and a false positive only costs the compile that used to run every time.
     */
    public static boolean mayNeedTypeCheck(String source) {
        return source != null && (source.contains("CompileStatic") || source.contains("TypeChecked"));
    }

    /** {@link #mayNeedTypeCheck(String)}, or the source uses one of the workspace's static annotation collectors. */
    public static boolean mayNeedTypeCheck(String source, Set<String> collectors) {
        if (mayNeedTypeCheck(source)) {
            return true;
        }
        for (String collector : collectors) {
            if (source != null && source.contains("@" + collector)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern ANNOTATION_TYPE = Pattern.compile("@interface\\s+(\\w+)");

    /**
     * The annotation types the workspace declares with {@code @AnnotationCollector} in a file that mentions static
     * typing: {@code @Safe} on a class can mean {@code @CompileStatic} without the word appearing there.
     */
    public static Set<String> staticCollectors(Map<String, String> sources) {
        Set<String> names = new TreeSet<>();
        for (String source : sources.values()) {
            if (source != null && source.contains("AnnotationCollector") && mayNeedTypeCheck(source)) {
                Matcher matcher = ANNOTATION_TYPE.matcher(source);
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    /**
     * Whether the type checker left a class alone: neither the class nor any of its methods or constructors was
     * statically checked. Interfaces, annotation types and generated helpers are not counted.
     */
    public static boolean isDynamic(ClassNode node) {
        if (node.isInterface() || node.isAnnotationDefinition() || node.isSynthetic()
                || node.getName().endsWith("$CollectorHelper") || checked(node)) {
            return false;
        }
        for (MethodNode method : node.getMethods()) {
            if (checked(method)) {
                return false;
            }
        }
        for (MethodNode constructor : node.getDeclaredConstructors()) {
            if (checked(constructor)) {
                return false;
            }
        }
        return true;
    }

    private static boolean checked(org.codehaus.groovy.ast.ASTNode node) {
        return node.getNodeMetaData(StaticTypeCheckingVisitor.class) != null
                || node.getNodeMetaData(StaticCompilationMetadataKeys.STATIC_COMPILE_NODE) != null;
    }

    /** The names of the dynamic classes ({@link #isDynamic}) in {@code modules}, sorted. */
    public static List<String> dynamicClasses(Iterable<ModuleNode> modules) {
        Set<String> names = new TreeSet<>();
        for (ModuleNode module : modules) {
            if (module == null) {
                continue;
            }
            for (ClassNode node : module.getClasses()) {
                if (isDynamic(node)) {
                    names.add(node.getName());
                }
            }
        }
        return List.copyOf(names);
    }

    /** The extension class names the settings ask for, in a stable order. */
    public static List<String> extensionsFor(EngineConfig.TypeCheck settings) {
        List<String> extensions = new ArrayList<>();
        if ("strict".equalsIgnoreCase(settings.nullChecker())) {
            extensions.add(STRICT_NULL_CHECKER);
        } else if (!"off".equalsIgnoreCase(settings.nullChecker())) {
            extensions.add(NULL_CHECKER);
        }
        if (settings.sqlInjection()) {
            extensions.add(SQL_INJECTION_CHECKER);
        }
        if (settings.purity()) {
            extensions.add(PURITY_CHECKER);
            extensions.add(MODIFIES_CHECKER);
        }
        if (settings.regex()) {
            extensions.add(REGEX_CHECKER);
        }
        if (settings.formatString()) {
            extensions.add(FORMAT_STRING_CHECKER);
        }
        if (settings.combiner()) {
            extensions.add(COMBINER_CHECKER);
        }
        return extensions;
    }

    /** The Groovy 6 checkers apply only when the workspace targets Groovy 6 or later. */
    public static List<String> extensionsFor(EngineConfig.TypeCheck settings, EngineConfig.Groovy groovy) {
        return groovy.hasGroovy6Checkers() ? extensionsFor(settings) : List.of();
    }

    /** Type-check errors per URI for every document in {@code sources} (an entry per URI, possibly empty). */
    public Map<String, List<Diagnostic>> analyze(Map<String, String> sources, List<Path> classpath, boolean trust,
            EngineConfig.TypeCheck settings) {
        return analyze(sources, classpath, trust, settings, EngineConfig.Groovy.DEFAULTS, Set.of());
    }

    /** What became of one document in a type check. */
    public enum Status { CHECKED, SKIPPED, TIMED_OUT, CRASHED }

    /**
     * Type-check errors per URI, whether each document was checked at all, and the classes a completed check left
     * dynamic (empty when it did not complete).
     */
    public record Result(Map<String, List<Diagnostic>> diagnostics, Map<String, Status> status,
            List<String> dynamicClasses) {
        public Result {
            dynamicClasses = List.copyOf(dynamicClasses);
        }
    }

    /** Only Groovy's own static type checker, none of the Groovy 6 extensions: what a typing verdict needs. */
    public static final EngineConfig.TypeCheck CORE_ONLY = new EngineConfig.TypeCheck("off", false, false, false,
            false, false);

    /**
     * Type-check errors per URI. Files in {@code skip} (the ones the main compile could not resolve; it already
     * reports them) are left out, and files whose names stop resolving are dropped and the rest retried, so one
     * broken file no longer keeps static checking from running everywhere else.
     */
    public Map<String, List<Diagnostic>> analyze(Map<String, String> sources, List<Path> classpath, boolean trust,
            EngineConfig.TypeCheck settings, EngineConfig.Groovy groovy, Set<String> skip) {
        return check(sources, classpath, trust, settings, groovy, skip).diagnostics();
    }

    /**
     * {@link #analyze} with each document's {@link Status}: only a {@code CHECKED} document with no error passed. A
     * check that could not run (a checker that failed to load, a compiler crash) is a {@code typecheck-failed}
     * warning and {@code CRASHED}, never an empty, clean-looking result.
     */
    public Result check(Map<String, String> sources, List<Path> classpath, boolean trust,
            EngineConfig.TypeCheck settings, EngineConfig.Groovy groovy, Set<String> skip) {
        return check(sources, classpath, trust, extensionsFor(settings, groovy), skip, null);
    }

    /**
     * {@link #check} with the project's compiler configuration script ({@code null} for none), which runs only when
     * the workspace is trusted: it is a Groovy script from the repository.
     */
    public Result check(Map<String, String> sources, List<Path> classpath, boolean trust,
            EngineConfig.TypeCheck settings, EngineConfig.Groovy groovy, Set<String> skip, Path configScript) {
        return check(sources, classpath, trust, extensionsFor(settings, groovy), skip, trust ? configScript : null);
    }

    /** {@link #check} with the checker extensions named directly. */
    Result check(Map<String, String> sources, List<Path> classpath, boolean trust, List<String> extensions,
            Set<String> skip, Path configScript) {
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        Map<String, Status> status = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
            status.put(uri, Status.SKIPPED);
        }
        Map<String, String> remaining = new LinkedHashMap<>(sources);
        remaining.keySet().removeAll(skip);
        long started = System.nanoTime();
        long deadline = Long.MAX_VALUE;
        for (int attempt = 0; attempt <= MAX_RETRIES && !remaining.isEmpty(); attempt++) {
            if (System.nanoTime() > deadline) {
                // Each retry is a whole-workspace compile to bytecode generation; past the budget the files still
                // unresolved go without a type check rather than holding every request for minutes.
                org.groovyscope.engine.log.EngineLog.warn("type check stopped after %d round(s): %d file(s) unchecked",
                        attempt, remaining.size());
                remaining.keySet().forEach(uri -> status.put(uri, Status.TIMED_OUT));
                return new Result(diagnostics, status, List.of());
            }
            org.groovyscope.engine.concurrent.Cancellation.checkpoint();
            Pass pass = compileOnce(remaining, classpath, trust, extensions, configScript);
            if (attempt == 0) {
                deadline = started + RetryBudget.of(System.nanoTime() - started);
            }
            if (pass.crash != null && pass.isolable && remaining.size() > 1) {
                String culprit = isolate(remaining, classpath, trust, extensions, configScript);
                if (culprit != null) {
                    // That file goes without a type check, reported on itself; the rest are checked without it.
                    diagnostics.get(culprit).add(new Diagnostic(culprit, org.groovyscope.engine.model.Range.of(0, 0,
                            0, 1), Severity.WARNING, SOURCE, "typecheck-failed", "the type checker failed on this file: "
                            + pass.crash.getMessage()));
                    status.put(culprit, Status.CRASHED);
                    remaining.remove(culprit);
                    continue;
                }
            }
            if (pass.crash != null) {
                String first = remaining.keySet().iterator().next();
                diagnostics.get(first).add(new Diagnostic(first, org.groovyscope.engine.model.Range.of(0, 0, 0, 1),
                        Severity.WARNING, SOURCE, "typecheck-failed", "the type checker failed: " + pass.crash.getMessage()));
                remaining.keySet().forEach(uri -> status.put(uri, Status.CRASHED));
                return new Result(diagnostics, status, List.of());
            }
            if (pass.checked || pass.broken.isEmpty()) {
                for (Map.Entry<String, List<Diagnostic>> entry : pass.diagnostics.entrySet()) {
                    diagnostics.get(entry.getKey()).addAll(entry.getValue());
                }
                if (pass.incomplete != null) {
                    String first = remaining.keySet().iterator().next();
                    diagnostics.get(first).add(new Diagnostic(first, org.groovyscope.engine.model.Range.of(0, 0, 0, 1),
                            Severity.WARNING, SOURCE, "typecheck-incomplete", "a type checker failed part-way (only the "
                                    + "errors found before are reported): " + pass.incomplete));
                }
                Status outcome = pass.incomplete == null ? Status.CHECKED : Status.CRASHED;
                remaining.keySet().forEach(uri -> status.put(uri, outcome));
                return new Result(diagnostics, status, pass.dynamic);
            }
            remaining.keySet().removeAll(pass.broken); // resolution errors: the compile pass reports those
        }
        return new Result(diagnostics, status, List.of());
    }

    /** @param isolable the crash was thrown while checking (one file's doing, perhaps), not a global failure */
    private record Pass(boolean checked, Set<String> broken, Map<String, List<Diagnostic>> diagnostics, Exception crash,
            String incomplete, boolean isolable, List<String> dynamic) {
        Pass(boolean checked, Set<String> broken, Map<String, List<Diagnostic>> diagnostics, Exception crash,
                String incomplete, boolean isolable) {
            this(checked, broken, diagnostics, crash, incomplete, isolable, List.of());
        }
    }

    /**
     * The one file that crashes the checker on its own, found by halving the suspects (log2 n compiles); null when
     * the crash needs several files together.
     */
    private String isolate(Map<String, String> sources, List<Path> classpath, boolean trust, List<String> extensions,
            Path configScript) {
        List<String> suspects = new ArrayList<>(sources.keySet());
        while (suspects.size() > 1) {
            org.groovyscope.engine.concurrent.Cancellation.checkpoint();
            List<String> half = suspects.subList(0, suspects.size() / 2);
            Pass probe = compileOnce(subset(sources, half), classpath, trust, extensions, configScript);
            suspects = new ArrayList<>(probe.crash != null ? half : suspects.subList(half.size(), suspects.size()));
        }
        String suspect = suspects.get(0);
        Pass alone = compileOnce(subset(sources, suspects), classpath, trust, extensions, configScript);
        return alone.crash != null ? suspect : null;
    }

    private static Map<String, String> subset(Map<String, String> sources, List<String> uris) {
        Map<String, String> chosen = new LinkedHashMap<>();
        for (String uri : uris) {
            chosen.put(uri, sources.get(uri));
        }
        return chosen;
    }

    private Pass compileOnce(Map<String, String> sources, List<Path> classpath, boolean trust, List<String> extensions,
            Path configScript) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setSourceEncoding("UTF-8");
        configuration.setTolerance(Integer.MAX_VALUE);
        configuration.addCompilationCustomizers(new TypeCheckExtensionCustomizer(extensions));

        ClassLoader parent = new WorkspaceParentClassLoader(GroovyCompiler.class.getClassLoader());
        GroovyClassLoader loader = new GroovyClassLoader(parent, configuration);
        for (Path entry : classpath) {
            try {
                loader.addURL(entry.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad classpath entry " + entry, e);
            }
        }
        if (configScript != null) {
            try {
                applyConfigScript(configuration, configScript, loader);
            } catch (IOException | RuntimeException | LinkageError e) {
                closeQuietly(loader);
                // the same for every file: nothing to isolate
                return new Pass(false, Set.of(), Map.of(), new IllegalStateException("typecheck.configScript "
                        + configScript + " failed: " + e, e), null, false);
            }
        }
        GroovyClassLoader transformLoader = trust ? loader : new GroovyClassLoader(parent, configuration);
        CompilationUnit unit = new CompilationUnit(configuration, null, loader, transformLoader);
        Map<String, SourceUnit> units = new HashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            units.put(source.getKey(), unit.addSource(source.getKey(), source.getValue()));
        }
        try {
            unit.compile(Phases.INSTRUCTION_SELECTION);
        } catch (CompilationFailedException e) {
            // Errors are read from the collectors below.
        } catch (Throwable e) {
            if (!org.groovyscope.engine.concurrent.Containment.recoverable(e)) {
                throw e;
            }
            // thrown while checking some file: worth isolating (the other files can still be checked)
            return new Pass(false, Set.of(), Map.of(), new IllegalStateException(e.toString(), e), null, true);
        } finally {
            closeQuietly(loader);
            if (transformLoader != loader) {
                closeQuietly(transformLoader);
            }
        }
        Map<String, List<Diagnostic>> found = new HashMap<>();
        for (String uri : sources.keySet()) {
            found.put(uri, new ArrayList<>());
        }
        // A checker extension that could not load means static checking did not run as asked: a failed check, not a
        // clean one. An exception while checking (a checker's own bug) leaves the errors found so far, marked
        // incomplete.
        String unloaded = unloadedExtension(unit.getErrorCollector(), extensions);
        if (unloaded != null) {
            return new Pass(false, Set.of(), Map.of(), new IllegalStateException(unloaded), null, false);
        }
        String incomplete = exceptionWhileChecking(unit.getErrorCollector());
        collect(unit.getErrorCollector(), sources, found);
        for (SourceUnit source : units.values()) {
            collect(source.getErrorCollector(), sources, found);
        }
        // Static checking runs in INSTRUCTION_SELECTION; a unit stopped earlier failed on resolution.
        boolean checked = unit.getPhase() >= Phases.INSTRUCTION_SELECTION;
        Set<String> broken = new HashSet<>();
        if (!checked) {
            for (Map.Entry<String, List<Diagnostic>> entry : found.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    broken.add(entry.getKey());
                }
            }
        }
        if (!checked && broken.isEmpty() && incomplete == null) {
            // stopped before static checking with nothing to show for it: a failed check, never a clean one
            String other = otherError(unit.getErrorCollector());
            return new Pass(false, Set.of(), Map.of(), new IllegalStateException(other != null ? other
                    : "the compile stopped before static checking"), null, true);
        }
        List<String> dynamic = checked ? dynamicClasses(modulesOf(units, sources.keySet())) : List.of();
        return new Pass(checked, broken, found, null, incomplete, false, dynamic);
    }

    private static List<ModuleNode> modulesOf(Map<String, SourceUnit> units, Set<String> uris) {
        List<ModuleNode> modules = new ArrayList<>();
        for (String uri : uris) {
            SourceUnit source = units.get(uri);
            if (source != null && source.getAST() != null) {
                modules.add(source.getAST());
            }
        }
        return modules;
    }

    /**
     * Runs the project's compiler configuration script the way {@code groovyc --configscript} does: with the
     * configuration bound as {@code configuration} and the customization builder ({@code withConfig}, {@code ast},
     * {@code imports}) imported, on the workspace's class loader so its transformations resolve.
     */
    static void applyConfigScript(CompilerConfiguration configuration, Path script, ClassLoader loader)
            throws IOException {
        Binding binding = new Binding();
        binding.setVariable("configuration", configuration);
        CompilerConfiguration scriptConfiguration = new CompilerConfiguration();
        ImportCustomizer imports = new ImportCustomizer();
        imports.addStaticStars(CompilerCustomizationBuilder.class.getName());
        scriptConfiguration.addCompilationCustomizers(imports);
        new GroovyShell(loader, binding, scriptConfiguration).evaluate(script.toFile());
    }

    /** The checker that produced a static type checking message, or {@code typecheck} for the core checker. */
    static String codeFor(String message) {
        if (message.startsWith("Potential null dereference") || message.contains("may be null")
                || message.contains("@NonNull") || message.contains("@Nullable")) {
            return "NullChecker";
        }
        if (message.startsWith("Possible SQL injection")) {
            return "SqlInjectionChecker";
        }
        if (message.startsWith("Bad regex")) {
            return "RegexChecker";
        }
        if (message.startsWith("CombinerChecker")) {
            return "CombinerChecker";
        }
        if (message.matches("^(Illegal|Missing|Unknown|Duplicate|Mismatched)\\w*Format\\w*[:].*")
                || message.startsWith("FormatStringChecker")) {
            return "FormatStringChecker";
        }
        if (message.contains("@Modifies")) {
            return "ModifiesChecker";
        }
        if (message.contains("@Pure") || message.contains("PurityChecker")) {
            return "PurityChecker";
        }
        return "typecheck";
    }

    static String stripPrefix(String message) {
        String prefix = "[Static type checking] - ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    /** A checker extension the compiler could not load, as its message; null when every one loaded. */
    static String unloadedExtension(ErrorCollector collector, List<String> extensions) {
        String other = otherError(collector);
        if (other == null) {
            return null;
        }
        if (other.contains("type checking extension")) {
            return other;
        }
        for (String extension : extensions) {
            int arguments = extension.indexOf('(');
            if (other.contains(arguments < 0 ? extension : extension.substring(0, arguments))) {
                return other;
            }
        }
        return null;
    }

    /** The first error that is neither a syntax error nor an exception, as text; null when there is none. */
    static String otherError(ErrorCollector collector) {
        if (collector == null || collector.getErrors() == null) {
            return null;
        }
        for (Message message : collector.getErrors()) {
            if (message instanceof org.codehaus.groovy.control.messages.SimpleMessage simple
                    && !(message instanceof SyntaxErrorMessage)) {
                return simple.getMessage();
            }
        }
        return null;
    }

    /** An exception raised while checking (not about a line of code), as text; null when there is none. */
    static String exceptionWhileChecking(ErrorCollector collector) {
        if (collector == null || collector.getErrors() == null) {
            return null;
        }
        for (Message message : collector.getErrors()) {
            if (message instanceof org.codehaus.groovy.control.messages.ExceptionMessage exception) {
                return String.valueOf(exception.getCause());
            }
        }
        return null;
    }

    private static void collect(ErrorCollector collector, Map<String, String> sources, Map<String, List<Diagnostic>> out) {
        if (collector == null || collector.getErrors() == null) {
            return;
        }
        for (Message message : collector.getErrors()) {
            if (!(message instanceof SyntaxErrorMessage syntax)) {
                continue;
            }
            SyntaxException cause = syntax.getCause();
            String uri = cause.getSourceLocator();
            if (uri == null || !sources.containsKey(uri)) {
                continue;
            }
            String text = stripPrefix(cause.getOriginalMessage().strip());
            Diagnostic diagnostic = new Diagnostic(uri, GroovyCompiler.rangeOf(cause), Severity.ERROR, SOURCE,
                    codeFor(text), text);
            List<Diagnostic> list = out.get(uri);
            if (!list.contains(diagnostic)) {
                list.add(diagnostic);
            }
        }
    }

    private static void closeQuietly(GroovyClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            // nothing useful to do
        }
    }
}
