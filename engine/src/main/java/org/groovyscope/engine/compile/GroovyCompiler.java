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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.control.ClassNodeResolver;
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
import static org.groovyscope.engine.util.Closeables.closeQuietly;

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
public class GroovyCompiler {

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
        return compile(sources, classpath, trust, Map.of());
    }

    private final Set<GroovyClassLoader> openLoaders = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The same, resolving the classes in {@code known} (by name) to the given trees instead of compiling them: a
     * file checked after a write is compiled alone against the rest of the workspace as the last full compile left
     * it. Names declared by {@code sources} themselves still resolve to their new trees.
     */
    public CompilationResult compile(Map<String, String> sources, List<Path> classpath, boolean trust,
            Map<String, ClassNode> known) {
        Map<String, String> remaining = new LinkedHashMap<>(sources);
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
        }
        Map<String, ModuleNode> modules = new HashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        // The first attempt that got past parsing: its trees are the ones kept when later rounds only cascade.
        // Rounds before it failed on syntax, and their drops are genuine; rounds after it drop the users of what
        // it could not resolve.
        // Every attempt's loader is released on every exit (a cancel in the parse-only pass included), except the
        // one whose trees the result keeps (E-16).
        List<Attempt> made = new ArrayList<>();
        Attempt transferred = null;
        try {
            Attempt resolvedOnce = null;
            Set<String> droppedSinceResolved = new LinkedHashSet<>();
            Attempt previous = null;
            Attempt kept = null;
            int phase = 0;
            int attempts = 0;
            RetryRounds rounds = new RetryRounds(MAX_RETRIES);
            boolean overBudget = false;
            for (int attempt = 0; rounds.allows(attempt) && !remaining.isEmpty(); attempt++) {
                if (rounds.overBudget()) {
                    overBudget = true;
                    break;
                }
                // A cancelled request stops here, between rounds; the loaders of the rounds so far are released below
                // through the normal exit when the caller discards the result, or at once when nothing was kept yet.
                if (org.groovyscope.engine.concurrent.Cancellation.requested()) {
                    release(previous == resolvedOnce ? null : previous);
                    release(resolvedOnce);
                    throw new java.util.concurrent.CancellationException("compile cancelled after " + attempts + " round(s)");
                }
                Attempt result = compileOnce(remaining, classpath, trust, Phases.CANONICALIZATION, known);
                made.add(result);
                attempts++;
                rounds.finished(attempt);
                if (previous != null && previous != resolvedOnce) {
                    release(previous);
                }
                previous = result;
                phase = Math.max(phase, result.phaseReached);
                boolean resolved = !result.failed || result.phaseReached >= Phases.CANONICALIZATION;
                if (resolvedOnce == null) {
                    // Up to and including the first resolved round every error is real; later rounds only report
                    // the classes that earlier drops removed.
                    mergeDiagnostics(diagnostics, result.diagnostics);
                }
                if (resolvedOnce == null && result.failed && !result.crashed
                        && result.phaseReached >= Phases.SEMANTIC_ANALYSIS) {
                    resolvedOnce = result;
                }
                if (resolved || result.brokenUris.isEmpty() || rounds.isLast(attempt)) {
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
                    if (resolvedOnce != null) {
                        droppedSinceResolved.add(broken);
                    }
                }
                if (resolvedOnce != null && cascaded(resolvedOnce, droppedSinceResolved)) {
                    // Every further round would only drop the users of the files already dropped; the resolved
                    // round's trees are kept below, so the remaining compiles would be thrown away.
                    break;
                }
            }
            boolean keepResolved = resolvedOnce != null && kept == null
                    && (cascaded(resolvedOnce, droppedSinceResolved) || overBudget);
            if (overBudget) {
                org.groovyscope.engine.log.EngineLog.warn("compile stopped retrying after %d round(s): %d file(s) %s",
                        attempts, remaining.size(), keepResolved ? "kept from the resolved round" : "parsed only");
                if (!keepResolved) {
                    dropped.addAll(remaining.keySet());
                }
                remaining.clear();
            }
            if (keepResolved) {
                if (previous != resolvedOnce) {
                    release(previous);
                }
                // Only the files dropped before the resolved round (syntax errors) have no tree in it; the files
                // dropped after it are the cascade, and the resolved round's trees for them are kept.
                Set<String> syntaxDrops = new LinkedHashSet<>(dropped);
                syntaxDrops.removeAll(droppedSinceResolved);
                CompilationResult partial = keepResolvedAttempt(sources, diagnostics, resolvedOnce, syntaxDrops, classpath,
                        trust, attempts);
                transferred = resolvedOnce;
                return partial;
            }
            if (previous != kept && previous != resolvedOnce) {
                release(previous);
            }
            if (resolvedOnce != kept) {
                release(resolvedOnce);
            }
            for (String uri : dropped) {
                org.groovyscope.engine.concurrent.Cancellation.checkpoint();
                parseInto(modules, uri, sources.get(uri), classpath, trust);
            }
            // Java files are compiled only so Groovy code can resolve them; Groovy's view of Java code is not
            // something to report on (javac and the IDE own that), so their diagnostics are dropped.
            diagnostics.keySet().removeIf(GroovyCompiler::isJava);
            LoaderHandle loader = handle(kept);
            transferred = kept;
            return new CompilationResult(modules, sources, diagnostics, phase, dropped, loader, attempts);
        } finally {
            for (Attempt attempt : made) {
                if (attempt != transferred) {
                    release(attempt);
                }
            }
        }
    }

    /** Parses one file alone to CONVERSION (its loader is released at once) and adds its tree when it has one. */
    private void parseInto(Map<String, ModuleNode> modules, String uri, String text, List<Path> classpath,
            boolean trust) {
        Attempt parsed = compileOnce(Map.of(uri, text), classpath, trust, Phases.CONVERSION);
        ModuleNode module = parsed.modules.get(uri);
        if (module != null) {
            modules.put(uri, module);
        }
        release(parsed); // a CONVERSION tree never decompiles classpath types
    }

    /**
     * The syntax tree of one file on its own, resolved against nothing: what a file that stopped parsing keeps
     * from its last good text, in the current generation rather than a tree pinned to a previous compile.
     */
    public Optional<ModuleNode> parseOnly(String uri, String text) {
        Attempt parsed = compileOnce(Map.of(uri, text), List.of(), false, Phases.CONVERSION);
        ModuleNode module = parsed.modules.get(uri);
        release(parsed);
        return Optional.ofNullable(module);
    }

    /** True when retrying dropped files that the first attempt did not report: the drops cascaded. */
    static boolean cascaded(Attempt first, Set<String> dropped) {
        return first.failed && !first.crashed && first.phaseReached >= Phases.SEMANTIC_ANALYSIS
                && !first.brokenUris.containsAll(dropped);
    }

    private CompilationResult keepResolvedAttempt(Map<String, String> sources, Map<String, List<Diagnostic>> genuine,
            Attempt resolved, Set<String> dropped, List<Path> classpath, boolean trust, int attempts) {
        Map<String, ModuleNode> modules = new HashMap<>(resolved.modules);
        // Groovy stopped the resolved round at the first failing file, so the other files never reported their own
        // unresolved classes; finishing semantic analysis here does, and those errors are as real as the first
        // round's (every class is in this round). Only names declared in files dropped for syntax are not.
        Map<String, List<Diagnostic>> finished = new HashMap<>();
        ErrorCollector collector = resolved.unit == null ? null : resolved.unit.getErrorCollector();
        Set<String> uris = sources.keySet();
        for (Map.Entry<String, SourceUnit> unit : resolved.units.entrySet()) {
            ModuleNode module = modules.get(unit.getKey());
            if (module != null) {
                int before = errorCount(collector);
                finishSemanticAnalysis(resolved.unit, unit.getValue(), module);
                collectSince(collector, before, uris, finished);
            }
        }
        Set<String> droppedNames = declaredNames(dropped, sources);
        finished.values().forEach(list -> list.removeIf(d -> namesDroppedClass(d, droppedNames)));
        // Files dropped for syntax errors before the resolved round have no tree in it: parse them alone.
        for (String uri : dropped) {
            if (!modules.containsKey(uri) && sources.containsKey(uri)) {
                parseInto(modules, uri, sources.get(uri), classpath, trust);
            }
        }
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (String uri : sources.keySet()) {
            diagnostics.put(uri, new ArrayList<>());
        }
        mergeDiagnostics(diagnostics, genuine);
        mergeDiagnostics(diagnostics, finished);
        diagnostics.keySet().removeIf(GroovyCompiler::isJava);
        Set<String> partial = new LinkedHashSet<>(dropped);
        partial.addAll(resolved.brokenUris);
        LoaderHandle loader = handle(resolved);
        return new CompilationResult(modules, sources, diagnostics, resolved.phaseReached, partial, loader, attempts);
    }

    /** True when {@code text} parses as Groovy (syntax only; used to vet automatic fixes). */
    public boolean parses(String uri, String text) {
        return parse(uri, text).ok();
    }

    /**
     * One text parsed on its own, as {@link #parses} does: syntax only, against an empty classpath with no
     * workspace transformation loaded. The tree is whatever Groovy built (none when the text does not parse), and
     * the errors are the error diagnostics in source order.
     */
    public Parsed parse(String uri, String text) {
        Attempt parsed = compileOnce(Map.of(uri, text), List.of(), false, Phases.CONVERSION);
        release(parsed); // a CONVERSION tree never decompiles classpath types
        List<Diagnostic> errors = new ArrayList<>();
        for (List<Diagnostic> list : parsed.diagnostics.values()) {
            for (Diagnostic diagnostic : list) {
                if (diagnostic.severity() == Severity.ERROR) {
                    errors.add(diagnostic);
                }
            }
        }
        errors.sort(java.util.Comparator.comparing((Diagnostic d) -> d.range().start()));
        boolean ok = !parsed.failed && parsed.brokenUris.isEmpty() && errors.isEmpty();
        return new Parsed(Optional.ofNullable(parsed.modules.get(uri)), ok, List.copyOf(errors));
    }

    /**
     * The outcome of {@link #parse}.
     *
     * @param module the syntax tree, when Groovy built one (a text that fails to parse usually has none)
     * @param ok whether the text parsed without errors
     * @param errors the error diagnostics, in source order
     */
    public record Parsed(Optional<ModuleNode> module, boolean ok, List<Diagnostic> errors) {
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
        return compileOnce(sources, classpath, trust, throughPhase, Map.of());
    }

    private Attempt compileOnce(Map<String, String> sources, List<Path> classpath, boolean trust, int throughPhase,
            Map<String, ClassNode> known) {
        CompilerConfiguration configuration = CompilationScaffold.configuration();
        configuration.setWarningLevel(WarningMessage.LIKELY_ERRORS);
        configuration.getOptimizationOptions().put(CompilerConfiguration.GROOVYDOC, Boolean.TRUE);
        configuration.setParameters(true);

        GroovyClassLoader resolutionLoader = CompilationScaffold.resolutionLoader(configuration, classpath);
        openLoaders.add(resolutionLoader);
        GroovyClassLoader transformLoader = CompilationScaffold.transformLoader(resolutionLoader, configuration, trust);

        CompilationUnit unit = new CompilationUnit(configuration, null, resolutionLoader, transformLoader);
        if (!known.isEmpty()) {
            unit.setClassNodeResolver(new KnownClasses(known));
        }
        Map<String, SourceUnit> units = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            units.put(source.getKey(), unit.addSource(source.getKey(), source.getValue()));
        }
        boolean failed = false;
        CompilationFailedException failure = null;
        try {
            unit.compile(throughPhase);
        } catch (CompilationFailedException e) {
            failed = true;
            failure = e;
        } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
            // A compiler bug on exotic input (Groovy reports its own as GroovyBugError, an AssertionError), a class that fails to link, or a stack overflow on deeply nested code
            // must not take the server down. OutOfMemoryError is left to propagate.
            Map<String, List<Diagnostic>> crash = new HashMap<>();
            String firstUri = sources.keySet().iterator().next();
            crash.put(firstUri, List.of(new Diagnostic(firstUri, Range.of(0, 0, 0, 1), Severity.ERROR, SOURCE,
                    "compiler-crash", "the Groovy compiler failed: " + e)));
            closeLoader(resolutionLoader);
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
        if (failure != null && diagnostics.values().stream().flatMap(List::stream)
                .noneMatch(d -> d.severity() == Severity.ERROR)) {
            // Groovy wraps some failures (a parser stack overflow on deeply nested code) without recording an
            // error; report it rather than leaving the file silently unresolved.
            unreported(failure, sources.keySet(), diagnostics, broken);
        }
        return new Attempt(modules, units, diagnostics, broken, unit.getPhase(), failed, false, resolutionLoader, unit);
    }

    /** Records a compile failure that came without any error message, on the file it names when it names one. */
    static void unreported(CompilationFailedException failure, Set<String> uris, Map<String, List<Diagnostic>> out,
            Set<String> broken) {
        String uri = failure.getUnit() instanceof SourceUnit source && uris.contains(source.getName())
                ? source.getName()
                : uris.iterator().next();
        Throwable cause = failure.getCause();
        String detail = cause == null ? String.valueOf(failure.getMessage())
                : failure.getMessage() + " (" + cause.getClass().getSimpleName() + ")";
        if (add(out, new Diagnostic(uri, Range.of(0, 0, 0, 1), Severity.ERROR, SOURCE, "compiler-crash",
                "the Groovy compiler failed: " + detail))) {
            broken.add(uri);
        }
    }

    /** Closes the class loader of an attempt whose trees are discarded (once, however often it is asked). */
    private void release(Attempt attempt) {
        if (attempt != null && attempt.loader != null) {
            closeLoader(attempt.loader);
        }
    }

    /** The handle a result owns for the attempt's loader; closing it releases the loader here too. */
    private LoaderHandle handle(Attempt attempt) {
        if (attempt == null || attempt.loader == null) {
            return null;
        }
        GroovyClassLoader loader = attempt.loader;
        return new LoaderHandle(() -> closeLoader(loader));
    }

    private void closeLoader(GroovyClassLoader loader) {
        if (openLoaders.remove(loader)) {
            closeQuietly(loader);
        }
    }

    /** Resolution loaders created and not yet closed (the kept compile's, normally one; for specs). */
    public int openLoaderCount() {
        return openLoaders.size();
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
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
                try {
                    new VariableScopeVisitor(source).visitClass(classNode);
                } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError ignored) {
                    // Scoping is best effort on a file that did not fully compile.
                }
            }
        }
    }

    private static int errorCount(ErrorCollector collector) {
        List<? extends Message> errors = collector == null ? null : collector.getErrors();
        return errors == null ? 0 : errors.size();
    }

    /** The errors {@code collector} gained after its first {@code from}, as diagnostics per document. */
    private static void collectSince(ErrorCollector collector, int from, Set<String> uris,
            Map<String, List<Diagnostic>> out) {
        List<? extends Message> errors = collector == null ? null : collector.getErrors();
        if (errors == null) {
            return;
        }
        for (Message message : errors.subList(Math.min(from, errors.size()), errors.size())) {
            Diagnostic diagnostic = toDiagnostic(message, uris, Severity.ERROR);
            if (diagnostic != null) {
                add(out, diagnostic);
            }
        }
    }

    private static final java.util.regex.Pattern DECLARED_TYPE = java.util.regex.Pattern.compile(
            "\\b(?:class|interface|trait|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    /** The simple names of the types the given documents declare, read from their text (they may not parse). */
    static Set<String> declaredNames(Set<String> uris, Map<String, String> sources) {
        Set<String> names = new LinkedHashSet<>();
        for (String uri : uris) {
            String text = sources.get(uri);
            if (text != null) {
                java.util.regex.Matcher matcher = DECLARED_TYPE.matcher(text);
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    /** True for an unresolved-class error about a type that only a dropped file declares. */
    static boolean namesDroppedClass(Diagnostic diagnostic, Set<String> droppedNames) {
        String message = diagnostic.message() == null ? "" : diagnostic.message().strip();
        String prefix = "unable to resolve class ";
        if (!message.startsWith(prefix) || droppedNames.isEmpty()) {
            return false;
        }
        String name = message.substring(prefix.length()).split("\\s")[0];
        String simple = name.substring(name.lastIndexOf('.') + 1);
        int generic = simple.indexOf('<');
        return droppedNames.contains(generic < 0 ? simple : simple.substring(0, generic));
    }

    /** Links variable usages to their declarations without resolving classes; errors are ignored. */
    private static void scopeVariables(SourceUnit source, ModuleNode module) {
        for (ClassNode classNode : module.getClasses()) {
            try {
                new VariableScopeVisitor(source).visitClass(classNode);
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
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


    record Attempt(Map<String, ModuleNode> modules, Map<String, SourceUnit> units,
            Map<String, List<Diagnostic>> diagnostics, Set<String> brokenUris, int phaseReached, boolean failed,
            boolean crashed, GroovyClassLoader loader, CompilationUnit unit) {
    }

    /** Resolves workspace classes to the trees of the last full compile; everything else as usual. */
    static final class KnownClasses extends ClassNodeResolver {
        private final Map<String, ClassNode> known;

        KnownClasses(Map<String, ClassNode> known) {
            this.known = known;
        }

        @Override
        public LookupResult resolveName(String name, CompilationUnit compilationUnit) {
            ClassNode found = known.get(name);
            return found != null ? new LookupResult(null, found) : super.resolveName(name, compilationUnit);
        }
    }
}
