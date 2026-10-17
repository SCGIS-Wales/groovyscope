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
package org.groovyscope.engine.compile;

import groovy.lang.GroovyClassLoader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.ResolveVisitor;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.StaticImportVisitor;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.CSTNode;
import org.codehaus.groovy.syntax.SyntaxException;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Range;

/**
 * Compiles workspace sources to {@link Phases#CANONICALIZATION}: names resolved, no bytecode generated.
 *
 * <p>Safety: {@code groovy.grape.enable} and {@code groovy.asttest.enable} are forced off for the
 * process; workspace code sees only the JDK, the Groovy runtime and its configured classpath (see
 * {@link WorkspaceParentClassLoader}); AST transformations found on that classpath are loaded only when the
 * workspace is trusted.
 *
 * <p>Resilience: Groovy stops a unit at the end of the phase in which any file reports an error, so one broken
 * file would keep every file from reaching {@link Phases#CANONICALIZATION}. The compiler therefore retries
 * without the files that reported errors, then parses those files on their own (with variable scoping but
 * without class resolution) so they still contribute symbols and name-based navigation.
 *
 * <p>Dropping a file can break the files that use its classes, and those break theirs: in a deep hierarchy the
 * retries end up dropping most of the workspace. When the retries drop more files than the first attempt
 * reported, the first attempt's trees are kept instead: that attempt saw every class at once. Groovy stopped it
 * after the first failing step, so the rest of semantic analysis is run on its trees here, and every name
 * that can resolve does; only the genuinely broken files are partial. Diagnostics then come from the first
 * attempt only, since later attempts mostly report the classes that the drops removed.
 */
public final class GroovyCompiler {

    /** Reported as the diagnostic source. */
    public static final String SOURCE = "groovyc";
    /**
     * Each retry drops at least one file, and dropping a base class breaks its subclasses on the next round, so
     * deep hierarchies need many rounds; after the first, rounds are cheap because few files remain.
     */
    private static final int MAX_RETRIES = 64;

    static {
        System.setProperty("groovy.grape.enable", "false");
        System.setProperty("groovy.asttest.enable", "false");
    }

    /**
     * @param sources text per URI
     * @param classpath workspace classpath entries
     * @param trust whether transformations on the classpath may load and run
     */
    public CompilationResult compile(Map<String, String> sources, List<Path> classpath, boolean trust) {
        Map<String, String> remaining = new LinkedHashMap<>(sources);
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
        }
        Map<String, ModuleNode> modules = new HashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        Attempt first = null;
        Attempt previous = null;
        Attempt kept = null;
        int phase = 0;
        for (int attempt = 0; attempt <= MAX_RETRIES && !remaining.isEmpty(); attempt++) {
            Attempt result = compileOnce(remaining, classpath, trust, Phases.CANONICALIZATION);
            if (first == null) {
                first = result;
            }
            if (previous != null && previous != first) {
                release(previous);
            }
            previous = result;
            phase = Math.max(phase, result.phaseReached);
            mergeDiagnostics(diagnostics, result.diagnostics);
            boolean resolved = !result.failed || result.phaseReached >= Phases.CANONICALIZATION;
            if (resolved || result.brokenUris.isEmpty() || attempt == MAX_RETRIES) {
                if (resolved) {
                    modules.putAll(result.modules);
                    kept = result;
                } else {
                    dropped.addAll(remaining.keySet());
                }
                remaining.clear();
                break;
            }
            for (String broken : result.brokenUris) {
                remaining.remove(broken);
                dropped.add(broken);
            }
        }
        if (first != null && cascaded(first, dropped)) {
            if (previous != first) {
                release(previous);
            }
            return keepFirstAttempt(sources, first);
        }
        if (previous != kept && previous != first) {
            release(previous);
        }
        if (first != kept) {
            release(first);
        }
        for (String uri : dropped) {
            Attempt parsed = compileOnce(Map.of(uri, sources.get(uri)), classpath, trust, Phases.CONVERSION);
            ModuleNode module = parsed.modules.get(uri);
            if (module != null) {
                modules.put(uri, module);
            }
        }
        // Java files are compiled only so Groovy code can resolve them; Groovy's view of Java code is not
        // something to report on (javac and the IDE own that), so their diagnostics are dropped.
        diagnostics.keySet().removeIf(GroovyCompiler::isJava);
        return new CompilationResult(modules, sources, diagnostics, phase, dropped);
    }

    /** True when retrying dropped files that the first attempt did not report: the drops cascaded. */
    static boolean cascaded(Attempt first, Set<String> dropped) {
        return first.failed && !first.crashed && first.phaseReached >= Phases.SEMANTIC_ANALYSIS
                && !first.brokenUris.containsAll(dropped);
    }

    private CompilationResult keepFirstAttempt(Map<String, String> sources, Attempt first) {
        Map<String, ModuleNode> modules = new HashMap<>(first.modules);
        for (Map.Entry<String, SourceUnit> unit : first.units.entrySet()) {
            ModuleNode module = modules.get(unit.getKey());
            if (module != null) {
                finishSemanticAnalysis(first.unit, unit.getValue(), module);
            }
        }
        // Only the first attempt's errors are real: later attempts report the classes their drops removed.
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
        }
        mergeDiagnostics(diagnostics, first.diagnostics);
        diagnostics.keySet().removeIf(GroovyCompiler::isJava);
        Set<String> partial = new LinkedHashSet<>(first.brokenUris);
        return new CompilationResult(modules, sources, diagnostics, first.phaseReached, partial);
    }

    /** True when {@code text} parses as Groovy (syntax only; used to vet automatic fixes). */
    public boolean parses(String uri, String text) {
        Attempt parsed = compileOnce(Map.of(uri, text), List.of(), false, Phases.CONVERSION);
        release(parsed);
        return !parsed.failed && parsed.brokenUris.isEmpty();
    }

    static boolean isJava(String uri) {
        return uri.endsWith(".java");
    }

    private static void mergeDiagnostics(Map<String, List<Diagnostic>> into, Map<String, List<Diagnostic>> from) {
        for (Map.Entry<String, List<Diagnostic>> entry : from.entrySet()) {
            List<Diagnostic> list = into.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
            for (Diagnostic diagnostic : entry.getValue()) {
                if (!list.contains(diagnostic)) {
                    list.add(diagnostic);
                }
            }
        }
    }

    private Attempt compileOnce(Map<String, String> sources, List<Path> classpath, boolean trust, int throughPhase) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setSourceEncoding("UTF-8");
        configuration.setTolerance(Integer.MAX_VALUE);
        configuration.setWarningLevel(WarningMessage.LIKELY_ERRORS);
        configuration.getOptimizationOptions().put(CompilerConfiguration.GROOVYDOC, Boolean.TRUE);
        configuration.setParameters(true);

        ClassLoader parent = new WorkspaceParentClassLoader(GroovyCompiler.class.getClassLoader());
        GroovyClassLoader resolutionLoader = new GroovyClassLoader(parent, configuration);
        for (Path entry : classpath) {
            resolutionLoader.addURL(toUrl(entry));
        }
        GroovyClassLoader transformLoader = trust
                ? resolutionLoader
                : new GroovyClassLoader(parent, configuration);

        CompilationUnit unit = new CompilationUnit(configuration, null, resolutionLoader, transformLoader);
        Map<String, SourceUnit> units = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            units.put(source.getKey(), unit.addSource(source.getKey(), source.getValue()));
        }
        boolean failed = false;
        try {
            unit.compile(throughPhase);
        } catch (CompilationFailedException e) {
            failed = true;
        } catch (RuntimeException e) {
            // A compiler bug on exotic input must not take the server down.
            Map<String, List<Diagnostic>> crash = new HashMap<>();
            String firstUri = sources.keySet().iterator().next();
            crash.put(firstUri, List.of(new Diagnostic(firstUri, Range.of(0, 0, 0, 1), Severity.ERROR, SOURCE,
                    "compiler-crash", "the Groovy compiler failed: " + e)));
            closeQuietly(resolutionLoader);
            return new Attempt(Map.of(), Map.of(), crash, Set.copyOf(sources.keySet()), unit.getPhase(), true, true,
                    null, null);
        } finally {
            // Transformations have run; their loader is done. The resolution loader stays open: classpath types in
            // the trees are decompiled lazily, so whoever keeps these trees keeps it (see release()).
            if (transformLoader != resolutionLoader) {
                closeQuietly(transformLoader);
            }
        }

        Map<String, ModuleNode> modules = new HashMap<>();
        for (Map.Entry<String, SourceUnit> entry : units.entrySet()) {
            ModuleNode module = entry.getValue().getAST();
            if (module != null) {
                if (throughPhase <= Phases.CONVERSION) {
                    scopeVariables(entry.getValue(), module);
                }
                modules.put(entry.getKey(), module);
            }
        }
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        Set<String> broken = new HashSet<>();
        collect(unit.getErrorCollector(), sources.keySet(), diagnostics, broken);
        for (SourceUnit source : units.values()) {
            collect(source.getErrorCollector(), sources.keySet(), diagnostics, broken);
        }
        return new Attempt(modules, units, diagnostics, broken, unit.getPhase(), failed, false, resolutionLoader, unit);
    }

    /** Closes the class loader of an attempt whose trees are discarded. */
    private static void release(Attempt attempt) {
        if (attempt != null && attempt.loader != null) {
            closeQuietly(attempt.loader);
        }
    }

    /**
     * Groovy checks for errors after every step of a phase, so when one file fails to resolve its class
     * headers, no file gets its method bodies resolved. This runs the rest of semantic analysis on one file of
     * that failed unit: a fresh {@link ResolveVisitor} resolves header and body (and scopes variables first),
     * then {@link StaticImportVisitor} rewrites static-import and class-qualified references. Errors are
     * ignored: the first attempt's diagnostics are the ones reported.
     */
    private static void finishSemanticAnalysis(CompilationUnit unit, SourceUnit source, ModuleNode module) {
        for (ClassNode classNode : module.getClasses()) {
            try {
                ResolveVisitor resolver = new ResolveVisitor(unit);
                resolver.setClassNodeResolver(unit.getClassNodeResolver());
                resolver.startResolving(classNode, source);
                new StaticImportVisitor(classNode, source).visitClass(classNode);
            } catch (RuntimeException | LinkageError e) {
                try {
                    new VariableScopeVisitor(source).visitClass(classNode);
                } catch (RuntimeException | LinkageError ignored) {
                    // Scoping is best effort on a file that did not fully compile.
                }
            }
        }
    }

    /** Links variable usages to their declarations without resolving classes; errors are ignored. */
    private static void scopeVariables(SourceUnit source, ModuleNode module) {
        for (ClassNode classNode : module.getClasses()) {
            try {
                new VariableScopeVisitor(source).visitClass(classNode);
            } catch (RuntimeException | LinkageError e) {
                // Scoping is best effort on a file that did not fully compile (or uses classes missing from the
                // configured classpath).
            }
        }
    }

    private static void collect(ErrorCollector collector, Set<String> uris, Map<String, List<Diagnostic>> out,
            Set<String> broken) {
        if (collector == null) {
            return;
        }
        List<? extends Message> errors = collector.getErrors();
        if (errors != null) {
            for (Message message : errors) {
                Diagnostic diagnostic = toDiagnostic(message, uris, Severity.ERROR);
                if (diagnostic != null && add(out, diagnostic) && message instanceof SyntaxErrorMessage) {
                    broken.add(diagnostic.uri());
                }
            }
        }
        List<WarningMessage> warnings = collector.getWarnings();
        if (warnings != null) {
            for (WarningMessage warning : warnings) {
                Diagnostic diagnostic = toDiagnostic(warning, uris, Severity.WARNING);
                if (diagnostic != null) {
                    add(out, diagnostic);
                }
            }
        }
    }

    private static boolean add(Map<String, List<Diagnostic>> out, Diagnostic diagnostic) {
        List<Diagnostic> list = out.computeIfAbsent(diagnostic.uri(), k -> new ArrayList<>());
        if (list.contains(diagnostic)) {
            return false;
        }
        list.add(diagnostic);
        return true;
    }

    static Diagnostic toDiagnostic(Message message, Set<String> uris, Severity severity) {
        if (message instanceof SyntaxErrorMessage syntax) {
            SyntaxException cause = syntax.getCause();
            String uri = cause.getSourceLocator();
            if (uri == null || !uris.contains(uri)) {
                uri = uris.isEmpty() ? "" : uris.iterator().next();
            }
            return new Diagnostic(uri, rangeOf(cause), severity, SOURCE, "syntax", cause.getOriginalMessage());
        }
        if (message instanceof WarningMessage warning) {
            CSTNode context = warning.getContext();
            int line = context == null ? 1 : Math.max(1, context.getStartLine());
            int column = context == null ? 1 : Math.max(1, context.getStartColumn());
            String uri = uris.isEmpty() ? "" : uris.iterator().next();
            return new Diagnostic(uri, Range.of(line - 1, column - 1, line - 1, column),
                    severity, SOURCE, "warning", warning.getMessage());
        }
        if (message instanceof ExceptionMessage exception) {
            Exception cause = exception.getCause();
            String uri = uris.isEmpty() ? "" : uris.iterator().next();
            return new Diagnostic(uri, Range.of(0, 0, 0, 1), severity, SOURCE, "exception",
                    cause == null ? String.valueOf(exception) : String.valueOf(cause.getMessage()));
        }
        if (message instanceof SimpleMessage simple) {
            String uri = uris.isEmpty() ? "" : uris.iterator().next();
            return new Diagnostic(uri, Range.of(0, 0, 0, 1), severity, SOURCE, "message", simple.getMessage());
        }
        return null;
    }

    public static Range rangeOf(SyntaxException exception) {
        int startLine = Math.max(1, exception.getStartLine());
        int startColumn = Math.max(1, exception.getStartColumn());
        int endLine = exception.getEndLine() > 0 ? exception.getEndLine() : startLine;
        int endColumn = exception.getEndColumn() > 0 ? exception.getEndColumn() : startColumn + 1;
        if (endLine < startLine || (endLine == startLine && endColumn <= startColumn)) {
            endLine = startLine;
            endColumn = startColumn + 1;
        }
        return Range.of(startLine - 1, startColumn - 1, endLine - 1, endColumn - 1);
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("bad classpath entry " + path, e);
        }
    }

    private static void closeQuietly(GroovyClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            // nothing useful to do
        }
    }

    record Attempt(Map<String, ModuleNode> modules, Map<String, SourceUnit> units,
            Map<String, List<Diagnostic>> diagnostics, Set<String> brokenUris, int phaseReached, boolean failed,
            boolean crashed, GroovyClassLoader loader, CompilationUnit unit) {
    }
}
