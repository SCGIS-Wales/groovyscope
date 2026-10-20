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
package org.groovyscope.engine.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.codehaus.groovy.ast.ModuleNode;
import org.codenarc.ruleset.RuleSet;
import org.groovyscope.engine.compile.CompilationResult;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.lint.CodeNarcAnalyzer;
import org.groovyscope.engine.model.CheckResult;
import org.groovyscope.engine.model.CodeAction;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.practices.GradleFinder;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.profiles.Contracts;
import org.groovyscope.engine.profiles.Profile;
import org.groovyscope.engine.profiles.ProfileArtefacts;
import org.groovyscope.engine.profiles.ProfileFinder;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * Lint, type-check, practices, format and fixes over a workspace. This facade runs {@code check} and the published
 * diagnostics and holds the practice findings per snapshot; the work itself is done by {@link LintService},
 * {@link TypeCheckService}, {@link FixService} and {@link ProfileService}.
 */
public final class QualityServices {

    /** Profile names accepted by {@code check}. */
    public static final String PROFILE_DEFAULT = "default";
    public static final String PROFILE_JENKINS = "jenkins";
    public static final String PROFILE_GRADLE = "gradle";
    /** Includes accepted by {@code check}. */
    public static final String INCLUDE_LINT = "lint";
    public static final String INCLUDE_COMPILE = "compile";
    public static final String INCLUDE_TYPECHECK = "typecheck";
    public static final String INCLUDE_PRACTICES = "practices";
    public static final Set<String> ALL_INCLUDES = Set.of(INCLUDE_LINT, INCLUDE_COMPILE, INCLUDE_TYPECHECK,
            INCLUDE_PRACTICES);
    /** Most emulation results kept; each holds the findings of one profile's governed sources. */
    static final int EMULATION_CACHE_LIMIT = ProfileService.EMULATION_CACHE_LIMIT;

    /**
     * CodeNarc runs here while the compile and the practices run on the request's thread: it parses the text on its
     * own, so the two overlap and a check after a write costs the slower of them, not their sum. Daemon threads, shared
     * by every workspace, at most one per processor.
     */
    private static final ExecutorService LINT = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()), runnable -> {
                Thread thread = new Thread(runnable, "groovyscope-lint");
                thread.setDaemon(true);
                return thread;
            });

    private final WorkspaceModel workspace;
    private final LanguageServices navigation;
    private final GroovyCompiler compiler = new GroovyCompiler();
    private final ProfileService profiles;
    private final LintService lint;
    private final TypeCheckService typeCheck;
    private final FixService fixes;
    private final Map<String, PracticeEntry> practiceCache = new ConcurrentHashMap<>();

    private record PracticeEntry(long version, List<PracticeFinder.Practice> practices) {
    }

    /**
     * What {@link #fix} changed per document: the edits, the codes whose fixes went in (every edit of the fix
     * applied) with how many, the findings of fixable codes left without a safe fix (a type that cannot be inferred,
     * a fix that clashed with another), and the documents refused whole.
     */
    public record FixResult(Map<String, List<TextEdit>> edits, Map<String, List<String>> applied,
            Map<String, Map<String, Integer>> appliedCounts, Map<String, Map<String, Integer>> left,
            Map<String, String> rejected, Map<String, Map<String, Integer>> needsReview) {
        public FixResult(Map<String, List<TextEdit>> edits, Map<String, List<String>> applied,
                Map<String, String> rejected) {
            this(edits, applied, Map.of(), Map.of(), rejected, Map.of());
        }
    }

    public QualityServices(WorkspaceModel workspace, LanguageServices navigation) {
        this.workspace = workspace;
        this.navigation = navigation;
        this.profiles = new ProfileService(workspace, navigation);
        this.lint = new LintService(workspace, navigation, profiles);
        this.typeCheck = new TypeCheckService(workspace, navigation, profiles);
        this.fixes = new FixService(workspace, navigation, lint, profiles, typeCheck, compiler, this::practices);
    }

    ClassLoader lintLoader() {
        return lint.lintLoader();
    }

    /** Releases the lint class loaders; called when the workspace closes. */
    void close() {
        lint.close();
    }

    /** How many whole-workspace type checks this instance has run (for specs). */
    int typeChecksRun() {
        return typeCheck.typeChecksRun();
    }

    /**
     * The project's compiler configuration script ({@code typecheck.configScript}, relative to the workspace root),
     * or {@code null} when there is no such file.
     */
    java.nio.file.Path configScript() {
        return typeCheck.configScript();
    }

    /**
     * The profile of a file in this workspace: {@link #profileByName}, and {@code jenkins} as well for the {@code src}
     * classes of a Jenkins shared library (a folder holding {@code vars/} beside {@code src/} or {@code resources/}),
     * which run on the same CPS runtime as its {@code vars} scripts.
     */
    public String profileFor(String uri) {
        return lint.profileFor(uri);
    }

    /**
     * {@code jenkins} for Jenkinsfiles and shared-library {@code vars} scripts, {@code gradle} for Gradle build
     * scripts ({@code *.gradle}), {@code default} otherwise.
     */
    public static String profileByName(String uri) {
        String path = uri.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.startsWith("Jenkinsfile") || path.contains("/vars/")) {
            return PROFILE_JENKINS;
        }
        return name.endsWith(".gradle") ? PROFILE_GRADLE : PROFILE_DEFAULT;
    }

    /** The rule set for a profile, from configuration or the bundled copies. */
    RuleSet ruleSet(String profile) {
        return lint.ruleSet(profile);
    }

    /** Lint one document; {@code profile} may be {@code null} to pick one from the path. */
    public List<Diagnostic> lint(String uri, String text, String profile) {
        return lint.lint(uri, text, profile);
    }

    /** Methods tests stub through instance dispatch, for the profile's test sources (cached per profile and snapshot). */
    java.util.Set<String> seams(Profile profile) {
        return profiles.seams(profile);
    }

    /** The contract items of a governed document. */
    public Map<String, String> contractItems(Profile profile, String uri) {
        return profiles.contractItems(profile, uri);
    }

    /** Contract items for every governed document, keyed by workspace-relative path (what the baseline stores). */
    public Map<String, Map<String, String>> contracts() {
        return profiles.contracts();
    }

    /** What the governing profile says about a document: target, provenance, descriptor, seams, protection. */
    public Map<String, Object> profileStatus(String uri) {
        return profiles.profileStatus(uri);
    }

    /** Descriptor and infrastructure declarations of the parameter under a position (empty when none). */
    public List<Location> parameterDeclarations(String uri, Position position) {
        return profiles.parameterDeclarations(uri, position);
    }

    /** Every reader of the parameter under a position, plus its declarations when asked. */
    public List<Location> parameterReferences(String uri, Position position, boolean includeDeclaration) {
        return profiles.parameterReferences(uri, position, includeDeclaration);
    }

    /** Hover text for the parameter under a position, if any. */
    public Optional<String> parameterHover(String uri, Position position) {
        return profiles.parameterHover(uri, position);
    }

    /** A map that keeps its {@code limit} most recently used entries (not thread-safe; callers lock it). */
    static <K, V> Map<K, V> lru(int limit) {
        return ProfileService.lru(limit);
    }

    /** Number of cached emulation results (for tests). */
    int emulationCacheSize() {
        return profiles.emulationCacheSize();
    }

    /** True when an emulation result only says the compile could not run (worth retrying, so never cached). */
    static boolean couldNotRun(Map<String, List<Diagnostic>> compiled) {
        return ProfileService.couldNotRun(compiled);
    }

    /** The runtime profile governing a document, if any. */
    public Optional<Profile> governing(String uri) {
        return profiles.governing(uri);
    }

    /**
     * The lint findings already computed for this exact text, or none: for hover, which must not run CodeNarc on the
     * light lane (the diagnostics pass fills the cache).
     */
    public List<Diagnostic> cachedLint(String uri, String text) {
        return lint.cachedLint(uri, text);
    }

    /** The practice findings already computed for the published snapshot, or none; never compiles or analyses. */
    public List<PracticeFinder.Practice> cachedPractices(String uri) {
        PracticeEntry cached = practiceCache.get(uri);
        return cached != null && cached.version() == navigation.currentSnapshot().version() ? cached.practices()
                : List.of();
    }

    /** groovyscope's practice findings (GS codes) for one Groovy document, from the current snapshot. */
    public List<PracticeFinder.Practice> practices(String uri) {
        if (WorkspaceModel.isJavaUri(uri) || !workspace.config().practices().enabled()) {
            return List.of();
        }
        Snapshot snapshot = navigation.snapshot();
        PracticeEntry cached = practiceCache.get(uri);
        if (cached != null && cached.version() == snapshot.version()) {
            return cached.practices();
        }
        ModuleNode module = snapshot.result().modules().get(uri);
        if (module == null && isBuildScript(uri)) {
            return buildScriptPractices(uri);
        }
        List<PracticeFinder.Practice> found = practicesOf(uri, module, snapshot.result().sources().get(uri));
        practiceCache.put(uri, new PracticeEntry(snapshot.version(), List.copyOf(found)));
        return found;
    }

    /** Whether a document is a Gradle build script. */
    static boolean isBuildScript(String uri) {
        return uri.endsWith(".gradle");
    }

    /**
     * The Gradle checks of a build script the compile does not read ({@link WorkspaceModel#buildScripts}), on its
     * syntax tree alone. Not cached by snapshot: a build script's edits do not start a compile.
     */
    private List<PracticeFinder.Practice> buildScriptPractices(String uri) {
        String text = workspace.text(uri);
        if (text == null) {
            return List.of();
        }
        return compiler.parseOnly(uri, text).map(module -> GradleFinder.find(uri, module, text, workspace.config()))
                .orElse(List.of());
    }

    /** The practice findings of one document's tree and text (the snapshot's, or a fresh single-file compile's). */
    private List<PracticeFinder.Practice> practicesOf(String uri, ModuleNode module, String text) {
        Optional<Profile> governing = profiles.governing(uri);
        EngineConfig config = workspace.config();
        if (governing.isPresent() && !governing.get().target().groovy().isBlank()) {
            config = config.withGroovy(new EngineConfig.Groovy(governing.get().target().groovy(), List.of()));
        }
        List<PracticeFinder.Practice> found = new ArrayList<>();
        for (PracticeFinder.Practice practice : PracticeFinder.find(uri, module, text, config, profileFor(uri))) {
            if (governing.isPresent() && "GS010".equals(practice.diagnostic().code())) {
                Diagnostic.Severity severity = PracticeFinder.severity("RP002", workspace.config());
                if (severity != null) {
                    Diagnostic d = practice.diagnostic();
                    found.add(new PracticeFinder.Practice(new Diagnostic(uri, d.range(), severity, ProfileFinder.SOURCE,
                            "RP002", "Groovy " + governing.get().target().groovy() + " on " + governing.get().name()
                                    + ": " + d.message().substring(d.message().indexOf(":") + 1).replace(", but groovy.compat includes 5.x", "").strip()), null, List.of()));
                }
            } else {
                found.add(practice);
            }
        }
        if (governing.isPresent()) {
            found.addAll(ProfileFinder.find(governing.get(), uri, module, text, workspace.root(), workspace.config()));
            String relative = profiles.relative(uri);
            found.addAll(Contracts.drift(uri, Contracts.baseline(workspace.root(), relative),
                    profiles.contractItems(governing.get(), uri), workspace.config()));
        }
        return found;
    }

    private List<Diagnostic> practiceDiagnostics(String uri) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (PracticeFinder.Practice practice : practices(uri)) {
            diagnostics.add(practice.diagnostic());
        }
        return diagnostics;
    }

    /**
     * Compile diagnostics plus, when {@code lint.publish} is on, the lint findings with priority
     * {@code lint.publishMaxPriority} or more severe, for every document. Compile, practice and profile findings are
     * never filtered.
     */
    public Map<String, List<Diagnostic>> publishedDiagnostics() {
        return publishedDiagnostics(null);
    }

    /**
     * The same, for {@code uris} only ({@code null} means every document): the pushed diagnostics of an agent or
     * editor session cover the few documents it has open, so the practice and lint passes run for those alone
     * while the compile diagnostics of the rest stay available through {@code check}.
     */
    public Map<String, List<Diagnostic>> publishedDiagnostics(Collection<String> uris) {
        Map<String, List<Diagnostic>> merged = new TreeMap<>();
        Map<String, String> sources = workspace.sources();
        Collection<String> wanted = uris == null ? sources.keySet() : uris;
        Map<String, List<Diagnostic>> compile = navigation.diagnostics();
        for (String uri : wanted) {
            if (uris != null && !sources.containsKey(uri)) {
                continue; // a document outside the workspace (or gone): nothing to report
            }
            merged.computeIfAbsent(uri, k -> new ArrayList<>()).addAll(compile.getOrDefault(uri, List.of()));
        }
        EngineConfig.Lint settings = workspace.config().lint();
        if (settings.publish()) {
            for (String uri : merged.keySet()) {
                List<Diagnostic> kept = merged.get(uri);
                for (Diagnostic diagnostic : lint(uri, sources.get(uri), null)) {
                    if (CodeNarcAnalyzer.priorityOf(diagnostic) <= settings.publishMaxPriority()) {
                        kept.add(diagnostic);
                    }
                }
            }
        }
        for (String uri : merged.keySet()) {
            merged.get(uri).addAll(practiceDiagnostics(uri));
        }
        return merged;
    }

    /**
     * Runs the requested analyses over the requested documents (all workspace documents when empty).
     *
     * @param uris documents to check, or empty for the whole workspace
     * @param profile {@code default}, {@code jenkins}, or {@code null} to pick per file
     * @param include any of {@code lint}, {@code compile}, {@code typecheck}; empty means all
     */
    public CheckResult check(Collection<String> uris, String profile, Set<String> include) {
        Set<String> wanted = include == null || include.isEmpty() ? ALL_INCLUDES : include;
        Map<String, String> sources = workspace.sources();
        List<String> targets = uris == null || uris.isEmpty() ? new ArrayList<>(sources.keySet()) : new ArrayList<>(uris);
        // Gradle build scripts the compile does not read are linted and checked on their own (GS050 to GS052).
        Map<String, String> scripts = new HashMap<>();
        if (wanted.contains(INCLUDE_LINT) || wanted.contains(INCLUDE_PRACTICES)) {
            if (uris == null || uris.isEmpty()) {
                scripts.putAll(workspace.buildScripts());
                targets.addAll(scripts.keySet());
            } else {
                for (String uri : targets) {
                    String text = isBuildScript(uri) && !sources.containsKey(uri) ? workspace.text(uri) : null;
                    if (text != null) {
                        scripts.put(uri, text);
                    }
                }
            }
        }
        Map<String, List<Diagnostic>> result = new TreeMap<>();
        for (String uri : targets) {
            result.put(uri, new ArrayList<>());
        }
        Map<String, String> lintSources = scripts.isEmpty() ? sources : withScripts(sources, scripts);
        CompletableFuture<Map<String, List<Diagnostic>>> linted = wanted.contains(INCLUDE_LINT)
                ? CompletableFuture.supplyAsync(() -> lint.lintAll(targets, lintSources, profile), LINT)
                : CompletableFuture.completedFuture(Map.of());
        // After a write only the written files changed: compile them alone against the last full compile's classes
        // (see LanguageServices.compileOnly) instead of the whole workspace.
        Optional<CompilationResult> alone = uris != null && !uris.isEmpty()
                && (wanted.contains(INCLUDE_COMPILE) || wanted.contains(INCLUDE_PRACTICES))
                ? quickCompile(targets) : Optional.empty();
        try {
            if (wanted.contains(INCLUDE_COMPILE)) {
                Map<String, List<Diagnostic>> compile = alone.map(navigation::diagnosticsOf)
                        .orElseGet(navigation::diagnostics);
                for (String uri : targets) {
                    result.get(uri).addAll(compile.getOrDefault(uri, List.of()));
                }
            }
            if (wanted.contains(INCLUDE_PRACTICES)) {
                for (String uri : targets) {
                    result.get(uri).addAll(alone.isPresent() && alone.get().sources().containsKey(uri)
                            ? practicesOf(uri, alone.get().modules().get(uri), alone.get().sources().get(uri))
                                    .stream().map(PracticeFinder.Practice::diagnostic).toList()
                            : practiceDiagnostics(uri));
                }
            }
        } finally {
            alone.ifPresent(CompilationResult::close);
        }
        if (wanted.contains(INCLUDE_PRACTICES)) {
            for (Map.Entry<String, List<Diagnostic>> entry : profiles.emulate(targets).entrySet()) {
                result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
            if (uris == null || uris.isEmpty()) {
                for (Profile runtime : workspace.config().profiles()) {
                    for (Map.Entry<String, List<Diagnostic>> entry
                            : ProfileArtefacts.find(runtime, workspace.root(), workspace.config()).entrySet()) {
                        result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
                    }
                }
            }
        }
        if (wanted.contains(INCLUDE_TYPECHECK)) {
            typeCheck.addTo(result, targets, sources, uris == null || uris.isEmpty(), scripts.keySet());
        }
        for (Map.Entry<String, List<Diagnostic>> entry : joined(linted).entrySet()) {
            result.get(entry.getKey()).addAll(entry.getValue());
        }
        for (List<Diagnostic> list : result.values()) {
            list.sort((a, b) -> a.range().start().compareTo(b.range().start()));
        }
        return new CheckResult(result);
    }

    private static Map<String, String> withScripts(Map<String, String> sources, Map<String, String> scripts) {
        Map<String, String> merged = new HashMap<>(sources);
        merged.putAll(scripts);
        return merged;
    }

    /** The single-file compile of {@link LanguageServices#compileOnly}, or empty (also when it fails). */
    private Optional<CompilationResult> quickCompile(List<String> targets) {
        try {
            return navigation.compileOnly(targets);
        } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
            // The previous generation's trees can lose their loader to a full compile finishing meanwhile; the
            // snapshot path below is always right, only slower.
            org.groovyscope.engine.log.EngineLog.debug("single-file compile skipped: %s", e);
            return Optional.empty();
        }
    }

    /** The lint result, or the lint thread's failure rethrown as it was. */
    static Map<String, List<Diagnostic>> joined(CompletableFuture<Map<String, List<Diagnostic>>> linted) {
        try {
            return linted.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /**
     * Whether the type check can report anything for {@code targets}: it only speaks about statically typed
     * code, so a request for files without {@code @CompileStatic} or {@code @TypeChecked} skips the whole-workspace
     * type-check compile (the costliest step of a single-file {@code check} after an edit).
     */
    static boolean anyMayNeedTypeCheck(Collection<String> targets, Map<String, String> sources) {
        return TypeCheckService.anyMayNeedTypeCheck(targets, sources);
    }

    /**
     * A text parsed on its own under the virtual name {@code name} (see {@link GroovyCompiler#parse}): syntax only,
     * nothing read from or written to disk, nothing from the workspace classpath and no transformation of its own.
     */
    public GroovyCompiler.Parsed parse(String name, String text) {
        return compiler.parse(name, text);
    }

    /** The formatting edits for one document (empty when it is already formatted). */
    public List<TextEdit> format(String uri) {
        return fixes.format(uri);
    }

    /**
     * The formatter's edits for the lines a range covers (a range ending at column 0 stops at the line before), one
     * edit per changed line.
     */
    public List<TextEdit> formatRange(String uri, Range range) {
        return fixes.formatRange(uri, range);
    }

    /** Quick fixes for the lint findings that overlap {@code range} in {@code uri}. */
    public List<CodeAction> codeActions(String uri, Range range) {
        return fixes.codeActions(uri, range);
    }

    /**
     * Applies every safe fix for the given codes (all fixable codes when empty) to the given documents (all
     * Groovy documents when empty) and returns the edits, without writing anything. A document whose fixed text
     * no longer parses is rejected. For a class missing a typing choice, {@code @CompileStatic} is used only when
     * the class then passes the static type checker; otherwise {@code @CompileDynamic} is added.
     */
    public FixResult fix(Collection<String> uris, Set<String> codes) {
        return fix(uris, codes, null);
    }

    /**
     * {@link #fix(Collection, Set)} limited to findings on the given lines: per document URI, 1-based inclusive
     * {@code [start, end]} ranges (the lines a branch changed). A fix whose finding lies outside them is not applied
     * and not counted; the edits a chosen fix needs elsewhere (an import) still come with it. {@code null} means
     * every line.
     */
    public FixResult fix(Collection<String> uris, Set<String> codes, Map<String, List<int[]>> onlyLines) {
        return fixes.fix(uris, codes, onlyLines);
    }

    /**
     * Whether a published finding has a quick fix: a lint rule with a mechanical fix, or a practice finding that
     * carries one. Cheap: lint codes come from a fixed set and practice findings from the per-snapshot cache.
     */
    public boolean fixable(Diagnostic diagnostic) {
        return fixes.fixable(diagnostic);
    }

    /** Every safe fix for one document ({@link #fix} for that document alone); empty when none applies. */
    public List<TextEdit> fixAll(String uri) {
        return fix(List.of(uri), Set.of()).edits().getOrDefault(uri, List.of());
    }

    /**
     * Edits that delete the imports CodeNarc reports as unused, unnecessary (auto-imported) or duplicated. A line is
     * removed only when it holds that single import, and never inside a region the governing profile protects.
     */
    public List<TextEdit> organizeImports(String uri) {
        return fixes.organizeImports(uri);
    }

    static String hash(String text) {
        return org.groovyscope.engine.util.Hashes.sha256Hex(text);
    }
}
