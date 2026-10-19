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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.codenarc.ruleset.RuleSet;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.format.BasicFormatter;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.lint.CodeNarcAnalyzer;
import org.groovyscope.engine.model.CheckResult;
import org.groovyscope.engine.model.CodeAction;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.profiles.Contracts;
import org.groovyscope.engine.profiles.Descriptors;
import org.groovyscope.engine.profiles.Profile;
import org.groovyscope.engine.profiles.ProfileArtefacts;
import org.groovyscope.engine.profiles.ProfileFinder;
import org.groovyscope.engine.profiles.ProfileNavigation;
import org.groovyscope.engine.profiles.Profiles;
import org.groovyscope.engine.profiles.RemoteCompiler;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.practices.PracticeFixes;
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * Lint, type-check, format and quick fixes over a workspace. Lint results are cached per document by
 * content hash and profile, so publishing diagnostics after every edit only re-lints what changed.
 */
public final class QualityServices {

    /** Profile names accepted by {@code check}. */
    public static final String PROFILE_DEFAULT = "default";
    public static final String PROFILE_JENKINS = "jenkins";
    /** Includes accepted by {@code check}. */
    public static final String INCLUDE_LINT = "lint";
    public static final String INCLUDE_COMPILE = "compile";
    public static final String INCLUDE_TYPECHECK = "typecheck";
    public static final String INCLUDE_PRACTICES = "practices";
    public static final Set<String> ALL_INCLUDES = Set.of(INCLUDE_LINT, INCLUDE_COMPILE, INCLUDE_TYPECHECK,
            INCLUDE_PRACTICES);

    private final WorkspaceModel workspace;
    private final LanguageServices navigation;
    private final CodeNarcAnalyzer codeNarc = new CodeNarcAnalyzer();
    private final TypeCheckAnalyzer typeChecker = new TypeCheckAnalyzer();
    private final org.groovyscope.engine.compile.GroovyCompiler compiler = new org.groovyscope.engine.compile.GroovyCompiler();
    private final Map<String, LintEntry> lintCache = new ConcurrentHashMap<>();
    private final Map<String, PracticeEntry> practiceCache = new ConcurrentHashMap<>();

    private record PracticeEntry(long version, List<PracticeFinder.Practice> practices) {
    }

    private record TypeCheckEntry(long version, Map<String, List<Diagnostic>> typed) {
    }

    private volatile TypeCheckEntry typeCache;

    /**
     * The type check of the whole workspace, run once per snapshot: every {@code check} asks for all of it (the
     * checkers need every class), so a request for one file must not pay for a full compile each time.
     */
    private Map<String, List<Diagnostic>> typeCheckAll() {
        org.groovyscope.engine.services.Snapshot current = navigation.snapshot();
        TypeCheckEntry cached = typeCache;
        if (cached != null && cached.version() == current.version()) {
            return cached.typed();
        }
        long started = System.nanoTime();
        Map<String, List<Diagnostic>> typed = typeChecker.analyze(workspace.compileSources(), workspace.classpath(),
                workspace.config().trust(), workspace.config().typecheck(), workspace.config().groovy(),
                current.result().unresolvedUris());
        typeCache = new TypeCheckEntry(current.version(), typed);
        org.groovyscope.engine.log.EngineLog.debug("type-checked %d file(s) in %d ms (snapshot %d)", typed.size(),
                (System.nanoTime() - started) / 1_000_000, current.version());
        return typed;
    }

    /**
     * What {@link #fix} changed per document: the edits, the codes whose fixes went in (every edit of the fix
     * applied) with how many, the findings of fixable codes left without a safe fix (a type that cannot be inferred,
     * a fix that clashed with another), and the documents refused whole.
     */
    public record FixResult(Map<String, List<TextEdit>> edits, Map<String, List<String>> applied,
            Map<String, Map<String, Integer>> appliedCounts, Map<String, Map<String, Integer>> left,
            Map<String, String> rejected) {
        public FixResult(Map<String, List<TextEdit>> edits, Map<String, List<String>> applied,
                Map<String, String> rejected) {
            this(edits, applied, Map.of(), Map.of(), rejected);
        }
    }

    private record LintEntry(String key, List<Diagnostic> diagnostics) {
    }

    public QualityServices(WorkspaceModel workspace, LanguageServices navigation) {
        this.workspace = workspace;
        this.navigation = navigation;
    }

    /** {@code jenkins} for Jenkinsfiles and shared-library {@code vars} scripts, {@code default} otherwise. */
    public static String profileFor(String uri) {
        String path = uri.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.startsWith("Jenkinsfile") || path.contains("/vars/")) {
            return PROFILE_JENKINS;
        }
        return PROFILE_DEFAULT;
    }

    /** The rule set for a profile, from configuration or the bundled copies. */
    RuleSet ruleSet(String profile) {
        EngineConfig.Lint lint = workspace.config().lint();
        if (PROFILE_JENKINS.equals(profile)) {
            return codeNarc.ruleSet(lint.jenkinsRuleset() == null ? CodeNarcAnalyzer.BUNDLED_JENKINS : lint.jenkinsRuleset(),
                    workspace.root(), CodeNarcAnalyzer.BUNDLED_JENKINS, lint.rules());
        }
        return codeNarc.ruleSet(lint.ruleset() == null ? CodeNarcAnalyzer.BUNDLED_DEFAULT : lint.ruleset(),
                workspace.root(), CodeNarcAnalyzer.BUNDLED_DEFAULT, lint.rules());
    }

    /** Lint one document; {@code profile} may be {@code null} to pick one from the path. */
    public List<Diagnostic> lint(String uri, String text, String profile) {
        if (WorkspaceModel.isJavaUri(uri)) {
            return List.of(); // Java sources join the compile only; CodeNarc rules target Groovy
        }
        String chosen = profile == null || profile.isBlank() ? profileFor(uri) : profile;
        String key = chosen + ":" + hash(text);
        LintEntry cached = lintCache.get(uri);
        List<Diagnostic> diagnostics;
        if (cached != null && cached.key.equals(key)) {
            diagnostics = cached.diagnostics;
        } else {
            diagnostics = codeNarc.analyze(uri, text, ruleSet(chosen));
            lintCache.put(uri, new LintEntry(key, List.copyOf(diagnostics)));
        }
        Optional<Profile> governing = governing(uri);
        if (governing.isEmpty() || governing.get().codenarcDisable().isEmpty()) {
            return diagnostics;
        }
        List<String> disabled = governing.get().codenarcDisable();
        return diagnostics.stream().filter(d -> !disabled.contains(d.code())).toList();
    }

    private final Map<String, java.util.Set<String>> seamCache = new ConcurrentHashMap<>();
    private volatile long seamVersion = -1;

    /** Methods tests stub through instance dispatch, for the profile's test sources (cached per profile and snapshot). */
    java.util.Set<String> seams(Profile profile) {
        org.groovyscope.engine.services.Snapshot snapshot = navigation.snapshot();
        if (seamVersion != snapshot.version()) {
            seamCache.clear();
            seamVersion = snapshot.version();
        }
        String key = profile.name() + "@" + snapshot.version();
        java.util.Set<String> cached = seamCache.get(key);
        if (cached == null) {
            cached = Contracts.seams(profile, workspace.root(), snapshot.result().modules());
            seamCache.put(key, cached);
        }
        return cached;
    }

    /** The contract items of a governed document. */
    public Map<String, String> contractItems(Profile profile, String uri) {
        java.nio.file.Path file = WorkspaceModel.toPath(uri);
        return Contracts.extract(profile, navigation.snapshot().result().modules().get(uri),
                profile.descriptor() == null ? null
                        : Descriptors.forFile(profile.descriptor(), workspace.root(), file).orElse(null),
                seams(profile));
    }

    /** Contract items for every governed document, keyed by workspace-relative path (what the baseline stores). */
    public Map<String, Map<String, String>> contracts() {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (String uri : workspace.sources().keySet()) {
            Optional<Profile> governing = governing(uri);
            governing.ifPresent(profile -> result.put(relative(uri), contractItems(profile, uri)));
        }
        return result;
    }

    /** What the governing profile says about a document: target, provenance, descriptor, seams, protection. */
    public Map<String, Object> profileStatus(String uri) {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        Optional<Profile> governing = governing(uri);
        status.put("governed", governing.isPresent());
        if (governing.isEmpty()) {
            return status;
        }
        Profile profile = governing.get();
        String relative = relative(uri);
        status.put("profile", profile.name());
        status.put("targetGroovy", profile.target().groovy());
        status.put("groovy6Checkers", profile.target().groovy6Checkers());
        status.put("compiledBy", profile.compiledBy(relative));
        Map<String, String> items = contractItems(profile, uri);
        if (profile.descriptor() != null) {
            Descriptors.forFile(profile.descriptor(), workspace.root(), WorkspaceModel.toPath(uri)).ifPresent(d -> {
                status.put("descriptor", workspace.root().relativize(d.file()).toString());
                Map<String, String> parameters = new java.util.LinkedHashMap<>();
                d.parameters().values().forEach(p -> parameters.put(p.name(), p.type()));
                status.put("parameters", parameters);
            });
        }
        status.put("contractItems", items.size());
        status.put("seams", items.keySet().stream().filter(k -> k.startsWith("seam:")).map(k -> k.substring(5)).toList());
        List<Map<String, String>> constructs = new ArrayList<>();
        for (Profile.Construct construct : profile.constructs()) {
            if (construct.files().isEmpty() || construct.files().stream()
                    .anyMatch(glob -> Profile.matches(glob, java.nio.file.Path.of(relative)))) {
                Map<String, String> entry = new java.util.LinkedHashMap<>();
                entry.put("id", construct.id());
                entry.put("status", construct.status());
                if (!construct.evidence().isBlank()) {
                    entry.put("evidence", construct.evidence());
                }
                if (!construct.replacement().isBlank()) {
                    entry.put("replacement", construct.replacement());
                }
                constructs.add(entry);
            }
        }
        status.put("constructs", constructs);
        status.put("protectedMethods", profile.protectedRegions().methods());
        status.put("baselineRecorded", Contracts.baseline(workspace.root(), relative) != null);
        status.put("guidance", profile.guidance());
        return status;
    }

    /** The parameter under a position in a governed document, with its profile, when the position is on one. */
    private Optional<Map.Entry<Profile, String>> parameterAt(String uri, org.groovyscope.engine.model.Position position) {
        Optional<Profile> governing = governing(uri);
        if (governing.isEmpty()) {
            return Optional.empty();
        }
        return ProfileNavigation.parameterAt(governing.get(), uri, navigation.snapshot().result().modules().get(uri), position)
                .map(name -> Map.entry(governing.get(), name));
    }

    /** Descriptor and infrastructure declarations of the parameter under a position (empty when none). */
    public List<org.groovyscope.engine.model.Location> parameterDeclarations(String uri, org.groovyscope.engine.model.Position position) {
        return parameterAt(uri, position).map(found -> ProfileNavigation.declarations(found.getKey(), workspace.root(),
                WorkspaceModel.toPath(uri), found.getValue())).orElse(List.of());
    }

    /** Every reader of the parameter under a position, plus its declarations when asked. */
    public List<org.groovyscope.engine.model.Location> parameterReferences(String uri,
            org.groovyscope.engine.model.Position position, boolean includeDeclaration) {
        Optional<Map.Entry<Profile, String>> found = parameterAt(uri, position);
        if (found.isEmpty()) {
            return List.of();
        }
        List<org.groovyscope.engine.model.Location> locations = new ArrayList<>(
                ProfileNavigation.readers(found.get().getKey(), governedModules(found.get().getKey()), found.get().getValue()));
        if (includeDeclaration) {
            locations.addAll(parameterDeclarations(uri, position));
        }
        return locations;
    }

    /** Hover text for the parameter under a position, if any. */
    public Optional<String> parameterHover(String uri, org.groovyscope.engine.model.Position position) {
        return parameterAt(uri, position).flatMap(found -> ProfileNavigation.hover(found.getKey(), workspace.root(),
                WorkspaceModel.toPath(uri), found.getValue(),
                ProfileNavigation.readers(found.getKey(), governedModules(found.getKey()), found.getValue()).size()));
    }

    private Map<String, org.codehaus.groovy.ast.ModuleNode> governedModules(Profile profile) {
        Map<String, org.codehaus.groovy.ast.ModuleNode> modules = new TreeMap<>();
        for (Map.Entry<String, org.codehaus.groovy.ast.ModuleNode> entry : navigation.snapshot().result().modules().entrySet()) {
            if (governing(entry.getKey()).filter(p -> p == profile).isPresent()) {
                modules.put(entry.getKey(), entry.getValue());
            }
        }
        return modules;
    }

    /** Most emulation results kept; each holds the findings of one profile's governed sources. */
    static final int EMULATION_CACHE_LIMIT = 8;
    private final Map<String, Map<String, List<Diagnostic>>> emulationCache = lru(EMULATION_CACHE_LIMIT);

    /** A map that keeps its {@code limit} most recently used entries (not thread-safe; callers lock it). */
    static <K, V> Map<K, V> lru(int limit) {
        return new java.util.LinkedHashMap<K, V>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }

    /** Number of cached emulation results (for tests). */
    int emulationCacheSize() {
        synchronized (emulationCache) {
            return emulationCache.size();
        }
    }

    /** The runtime-emulation compile (RP050) of governed targets, per profile, cached by the sources' content. */
    private Map<String, List<Diagnostic>> emulate(List<String> targets) {
        Map<String, List<Diagnostic>> result = new TreeMap<>();
        Map<String, String> sources = workspace.sources();
        for (Profile profile : workspace.config().profiles()) {
            if (profile.target().emulationJars().isEmpty()) {
                continue;
            }
            Map<String, String> governed = new TreeMap<>();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                if (governing(source.getKey()).filter(p -> p == profile).isPresent()) {
                    governed.put(source.getKey(), source.getValue());
                }
            }
            String key = profile.name() + ":" + hash(String.join("\u0000", governed.values()) + governed.keySet());
            Map<String, List<Diagnostic>> compiled;
            synchronized (emulationCache) {
                compiled = emulationCache.get(key);
            }
            if (compiled == null) {
                // Compiled outside the lock: an emulation compile can take seconds.
                compiled = RemoteCompiler.compile(profile, governed, workspace.classpath(), workspace.config().trust(),
                        workspace.config(), workspace.root());
                if (!couldNotRun(compiled)) {
                    synchronized (emulationCache) {
                        emulationCache.put(key, compiled);
                    }
                }
            }
            for (Map.Entry<String, List<Diagnostic>> entry : compiled.entrySet()) {
                if (targets.contains(entry.getKey())) {
                    result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
                }
            }
        }
        return result;
    }

    /** True when an emulation result only says the compile could not run (worth retrying, so never cached). */
    static boolean couldNotRun(Map<String, List<Diagnostic>> compiled) {
        return compiled.values().stream().flatMap(List::stream)
                .anyMatch(d -> d.message().startsWith(RemoteCompiler.COULD_NOT_RUN));
    }

    private String relative(String uri) {
        java.nio.file.Path file = WorkspaceModel.toPath(uri);
        java.nio.file.Path top = workspace.root().toAbsolutePath().normalize();
        return file == null ? uri : top.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The runtime profile governing a document, if any. */
    public Optional<Profile> governing(String uri) {
        if (workspace.config().profiles().isEmpty()) {
            return Optional.empty();
        }
        return Profiles.governing(workspace.config().profiles(), workspace.root(), uri,
                navigation.snapshot().result().modules().get(uri));
    }

    /** groovyscope's practice findings (GS codes) for one Groovy document, from the current snapshot. */
    public List<PracticeFinder.Practice> practices(String uri) {
        if (WorkspaceModel.isJavaUri(uri) || !workspace.config().practices().enabled()) {
            return List.of();
        }
        org.groovyscope.engine.services.Snapshot snapshot = navigation.snapshot();
        PracticeEntry cached = practiceCache.get(uri);
        if (cached != null && cached.version() == snapshot.version()) {
            return cached.practices();
        }
        org.codehaus.groovy.ast.ModuleNode module = snapshot.result().modules().get(uri);
        String text = snapshot.result().sources().get(uri);
        Optional<Profile> governing = governing(uri);
        EngineConfig config = workspace.config();
        if (governing.isPresent() && !governing.get().target().groovy().isBlank()) {
            config = config.withGroovy(new EngineConfig.Groovy(governing.get().target().groovy(), List.of()));
        }
        List<PracticeFinder.Practice> found = new ArrayList<>();
        for (PracticeFinder.Practice practice : PracticeFinder.find(uri, module, text, config)) {
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
            String relative = relative(uri);
            found.addAll(Contracts.drift(uri, Contracts.baseline(workspace.root(), relative),
                    contractItems(governing.get(), uri), workspace.config()));
        }
        practiceCache.put(uri, new PracticeEntry(snapshot.version(), List.copyOf(found)));
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
        Map<String, List<Diagnostic>> result = new TreeMap<>();
        for (String uri : targets) {
            result.put(uri, new ArrayList<>());
        }
        if (wanted.contains(INCLUDE_COMPILE)) {
            Map<String, List<Diagnostic>> compile = navigation.diagnostics();
            for (String uri : targets) {
                result.get(uri).addAll(compile.getOrDefault(uri, List.of()));
            }
        }
        if (wanted.contains(INCLUDE_LINT)) {
            for (String uri : targets) {
                org.groovyscope.engine.concurrent.Cancellation.checkpoint();
                String text = sources.get(uri);
                if (text != null) {
                    result.get(uri).addAll(lint(uri, text, profile));
                }
            }
        }
        if (wanted.contains(INCLUDE_PRACTICES)) {
            for (String uri : targets) {
                result.get(uri).addAll(practiceDiagnostics(uri));
            }
            for (Map.Entry<String, List<Diagnostic>> entry : emulate(targets).entrySet()) {
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
        if (wanted.contains(INCLUDE_TYPECHECK) && anyMayNeedTypeCheck(targets, sources)) {
            Map<String, List<Diagnostic>> typed = typeCheckAll();
            for (String uri : targets) {
                for (Diagnostic diagnostic : profileCheckerPolicy(uri, typed.getOrDefault(uri, List.of()))) {
                    if (!result.get(uri).contains(diagnostic)) {
                        result.get(uri).add(diagnostic);
                    }
                }
            }
        }
        for (List<Diagnostic> list : result.values()) {
            list.sort((a, b) -> a.range().start().compareTo(b.range().start()));
        }
        return new CheckResult(result);
    }

    /**
     * Whether the type check can report anything for {@code targets}: it only speaks about statically typed
     * code, so a request for files without {@code @CompileStatic} or {@code @TypeChecked} skips the whole-workspace
     * type-check compile (the costliest step of a single-file {@code check} after an edit).
     */
    static boolean anyMayNeedTypeCheck(Collection<String> targets, Map<String, String> sources) {
        for (String uri : targets) {
            if (TypeCheckAnalyzer.mayNeedTypeCheck(sources.get(uri))) {
                return true;
            }
        }
        return false;
    }

    /** The formatting edits for one document (empty when it is already formatted). */
    public List<TextEdit> format(String uri) {
        String text = workspace.text(uri);
        if (text == null || WorkspaceModel.isJavaUri(uri)) {
            return List.of();
        }
        EngineConfig.Format settings = workspace.config().format();
        if (!"basic".equalsIgnoreCase(settings.engine())) {
            throw new IllegalStateException("formatter engine '" + settings.engine()
                    + "' is not bundled in this release; use format.engine: basic");
        }
        return BasicFormatter.edits(text, settings, lint(uri, text, null));
    }

    /**
     * The formatter's edits for the lines a range covers (a range ending at column 0 stops at the line before), one
     * edit per changed line.
     */
    public List<TextEdit> formatRange(String uri, Range range) {
        String text = workspace.text(uri);
        if (text == null || WorkspaceModel.isJavaUri(uri)) {
            return List.of();
        }
        EngineConfig.Format settings = workspace.config().format();
        if (!"basic".equalsIgnoreCase(settings.engine())) {
            throw new IllegalStateException("formatter engine '" + settings.engine()
                    + "' is not bundled in this release; use format.engine: basic");
        }
        int last = range.end().column() == 0 && range.end().line() > range.start().line()
                ? range.end().line() - 1 : range.end().line();
        return BasicFormatter.rangeEdits(text, settings, lint(uri, text, null), range.start().line(), last);
    }

    /** Quick fixes for the lint findings that overlap {@code range} in {@code uri}. */
    public List<CodeAction> codeActions(String uri, Range range) {
        String text = workspace.text(uri);
        if (text == null) {
            return List.of();
        }
        TextLines lines = new TextLines(text);
        List<CodeAction> actions = new ArrayList<>();
        org.codehaus.groovy.ast.ModuleNode module = navigation.snapshot().result().modules().get(uri);
        for (Diagnostic diagnostic : lint(uri, text, null)) {
            if (!overlaps(diagnostic.range(), range)) {
                continue;
            }
            CodeAction action = quickFix(diagnostic, lines);
            if (action != null) {
                actions.add(action);
            }
            actions.addAll(PracticeFixes.lintFixes(diagnostic, lines, module));
        }
        for (PracticeFinder.Practice practice : practices(uri)) {
            if (practice.fixable() && overlaps(practice.diagnostic().range(), range)) {
                actions.add(new CodeAction(practice.fixTitle(), practice.diagnostic(), practice.fix()));
            }
        }
        return unprotected(uri, text, actions);
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
        Map<String, String> sources = workspace.sources();
        List<String> targets = uris == null || uris.isEmpty() ? new ArrayList<>(sources.keySet()) : new ArrayList<>(uris);
        Map<String, List<TextEdit>> edits = new TreeMap<>();
        Map<String, List<String>> applied = new TreeMap<>();
        Map<String, Map<String, Integer>> appliedCounts = new TreeMap<>();
        Map<String, Map<String, Integer>> left = new TreeMap<>();
        Map<String, String> rejected = new TreeMap<>();
        Map<String, Boolean> staticOk = staticVerdicts(targets, sources, codes);
        for (String uri : targets) {
            org.groovyscope.engine.concurrent.Cancellation.checkpoint();
            String text = sources.get(uri);
            if (text == null || WorkspaceModel.isJavaUri(uri)) {
                continue;
            }
            Map<String, Integer> missed = new TreeMap<>();
            List<int[]> only = onlyLines == null ? null : onlyLines.getOrDefault(uri, List.of());
            List<CodeAction> chosen = chooseFixes(uri, text, codes, staticOk, missed, only);
            if (chosen.isEmpty()) {
                if (!missed.isEmpty()) {
                    left.put(uri, missed);
                }
                continue;
            }
            List<TextEdit> all = new ArrayList<>();
            for (CodeAction action : chosen) {
                all.addAll(action.edits());
            }
            PracticeFixes.Applied result = PracticeFixes.apply(text, all);
            if (!compiler.parses(uri, result.text())) {
                rejected.put(uri, "the fixed text does not parse; nothing applied");
                continue;
            }
            // A fix counts only when every one of its edits went in; one that clashed with another is left.
            Map<String, Integer> counts = new TreeMap<>();
            for (CodeAction action : chosen) {
                String code = action.diagnostic().code();
                if (result.edits().containsAll(action.edits())) {
                    counts.merge(code, 1, Integer::sum);
                } else {
                    missed.merge(code, 1, Integer::sum);
                }
            }
            if (!result.edits().isEmpty()) {
                edits.put(uri, result.edits());
                applied.put(uri, new ArrayList<>(counts.keySet()));
                appliedCounts.put(uri, counts);
            }
            if (!missed.isEmpty()) {
                left.put(uri, missed);
            }
        }
        return new FixResult(edits, applied, appliedCounts, left, rejected);
    }

    private static boolean onLines(Diagnostic diagnostic, List<int[]> ranges) {
        int line = diagnostic.range().start().line() + 1;
        for (int[] range : ranges) {
            if (range.length == 2 && line >= range[0] && line <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private List<CodeAction> chooseFixes(String uri, String text, Set<String> codes, Map<String, Boolean> staticOk,
            Map<String, Integer> missed, List<int[]> only) {
        TextLines lines = new TextLines(text);
        org.codehaus.groovy.ast.ModuleNode module = navigation.snapshot().result().modules().get(uri);
        List<CodeAction> chosen = new ArrayList<>();
        for (Diagnostic diagnostic : lint(uri, text, null)) {
            if (codes != null && !codes.isEmpty() && !codes.contains(diagnostic.code())
                    || only != null && !onLines(diagnostic, only)) {
                continue;
            }
            CodeAction textual = quickFix(diagnostic, lines);
            if (textual != null) {
                chosen.add(textual);
                continue;
            }
            List<CodeAction> options = PracticeFixes.lintFixes(diagnostic, lines, module);
            if ("CompileStatic".equals(diagnostic.code()) && options.size() == 2) {
                chosen.add(staticOk.getOrDefault(uri, false) ? options.get(0) : options.get(1));
            } else if (!options.isEmpty()) {
                chosen.add(options.get(0));
            } else if (FIXABLE_LINT_CODES.contains(diagnostic.code())) {
                missed.merge(diagnostic.code(), 1, Integer::sum);
            }
        }
        for (PracticeFinder.Practice practice : practices(uri)) {
            if (practice.fixable() && (codes == null || codes.isEmpty() || codes.contains(practice.diagnostic().code()))
                    && (only == null || onLines(practice.diagnostic(), only))) {
                chosen.add(new CodeAction(practice.fixTitle(), practice.diagnostic(), practice.fix()));
            }
        }
        return unprotected(uri, text, chosen);
    }

    /**
     * For every target document with a {@code CompileStatic} finding that offers the annotation as its first
     * option: whether the document still passes the static type checker with that option applied. One
     * whole-workspace type check for the whole {@code fix} call, with every candidate applied at once, instead
     * of one per finding: the checker reports each class on its own, so the verdicts stay independent.
     */
    private Map<String, Boolean> staticVerdicts(Collection<String> uris, Map<String, String> sources, Set<String> codes) {
        Map<String, String> candidates = new java.util.LinkedHashMap<>();
        for (String uri : uris) {
            String text = sources.get(uri);
            if (text == null || WorkspaceModel.isJavaUri(uri)) {
                continue;
            }
            TextLines lines = new TextLines(text);
            org.codehaus.groovy.ast.ModuleNode module = navigation.snapshot().result().modules().get(uri);
            for (Diagnostic diagnostic : lint(uri, text, null)) {
                if (!"CompileStatic".equals(diagnostic.code())
                        || (codes != null && !codes.isEmpty() && !codes.contains(diagnostic.code()))) {
                    continue;
                }
                List<CodeAction> options = PracticeFixes.lintFixes(diagnostic, lines, module);
                if (options.size() == 2) {
                    candidates.put(uri, PracticeFixes.apply(text, options.get(0).edits()).text());
                }
            }
        }
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, String> trial = new java.util.LinkedHashMap<>(workspace.compileSources());
        trial.putAll(candidates);
        Map<String, List<Diagnostic>> typed = typeChecker.analyze(trial, workspace.classpath(),
                workspace.config().trust(), workspace.config().typecheck(), workspace.config().groovy(),
                navigation.snapshot().result().unresolvedUris());
        Map<String, Boolean> verdicts = new HashMap<>();
        for (String uri : candidates.keySet()) {
            verdicts.put(uri, typed.getOrDefault(uri, List.of()).isEmpty());
        }
        return verdicts;
    }

    /**
     * In files a runtime profile governs, Groovy 6 checker findings are advisory (information) or dropped, as the
     * profile says: the runtime may not run those checkers at all.
     */
    private List<Diagnostic> profileCheckerPolicy(String uri, List<Diagnostic> typed) {
        Optional<Profile> governing = governing(uri);
        if (governing.isEmpty()) {
            return typed;
        }
        String policy = governing.get().target().groovy6Checkers();
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic diagnostic : typed) {
            boolean checker = !"typecheck".equals(diagnostic.code());
            if (!checker || "on".equalsIgnoreCase(policy)) {
                kept.add(diagnostic);
            } else if ("advisory".equalsIgnoreCase(policy)) {
                kept.add(new Diagnostic(diagnostic.uri(), diagnostic.range(), Diagnostic.Severity.INFORMATION,
                        diagnostic.source(), diagnostic.code(), "advisory on " + governing.get().name() + ": "
                        + diagnostic.message()));
            }
        }
        return kept;
    }

    /** Actions whose edits stay outside the protected regions of the governing profile. */
    private List<CodeAction> unprotected(String uri, String text, List<CodeAction> actions) {
        Optional<Profile> governing = governing(uri);
        if (governing.isEmpty()) {
            return actions;
        }
        List<Range> ranges = Profiles.protectedRanges(governing.get(),
                navigation.snapshot().result().modules().get(uri), new TextLines(text));
        if (ranges.isEmpty()) {
            return actions;
        }
        List<CodeAction> kept = new ArrayList<>();
        for (CodeAction action : actions) {
            if (action.edits().stream().noneMatch(edit -> Profiles.touchesProtected(edit, ranges))) {
                kept.add(action);
            }
        }
        return kept;
    }

    /** CodeNarc rules {@link #quickFix} or {@link PracticeFixes#lintFixes} can fix mechanically. */
    static final Set<String> FIXABLE_LINT_CODES = Set.of(BasicFormatter.UNNECESSARY_SEMICOLON, "TrailingWhitespace",
            "ConsecutiveBlankLines", "CompileStatic", "UnnecessaryDefInVariableDeclaration",
            "UnnecessaryDefInMethodDeclaration", "UnnecessaryDefInFieldDeclaration", "UnnecessaryGString",
            "GStringAsMapKey", "NoDef", "VariableTypeRequired");
    /** CodeNarc rules whose findings {@link #organizeImports} removes. */
    static final Set<String> IMPORT_CODES = Set.of("UnusedImport", "UnnecessaryGroovyImport", "DuplicateImport");

    /**
     * Whether a published finding has a quick fix: a lint rule with a mechanical fix, or a practice finding that
     * carries one. Cheap: lint codes come from a fixed set and practice findings from the per-snapshot cache.
     */
    public boolean fixable(Diagnostic diagnostic) {
        if (CodeNarcAnalyzer.SOURCE.equals(diagnostic.source())) {
            return FIXABLE_LINT_CODES.contains(diagnostic.code());
        }
        if (diagnostic.uri() == null || org.groovyscope.engine.compile.GroovyCompiler.SOURCE.equals(diagnostic.source())) {
            return false;
        }
        for (PracticeFinder.Practice practice : practices(diagnostic.uri())) {
            if (practice.fixable() && practice.diagnostic().equals(diagnostic)) {
                return true;
            }
        }
        return false;
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
        String text = workspace.text(uri);
        if (text == null || WorkspaceModel.isJavaUri(uri)) {
            return List.of();
        }
        TextLines lines = new TextLines(text);
        java.util.SortedMap<Integer, Diagnostic> remove = new java.util.TreeMap<>();
        for (Diagnostic diagnostic : lint(uri, text, null)) {
            int line = diagnostic.range().start().line();
            if (IMPORT_CODES.contains(diagnostic.code()) && line < lines.lineCount()) {
                String content = lines.line(line).strip();
                if (content.startsWith("import ") && content.indexOf("import ", 1) < 0) {
                    remove.putIfAbsent(line, diagnostic);
                }
            }
        }
        List<CodeAction> actions = new ArrayList<>();
        for (Map.Entry<Integer, Diagnostic> entry : remove.entrySet()) {
            int line = entry.getKey();
            Position end = line + 1 < lines.lineCount() ? new Position(line + 1, 0)
                    : new Position(line, lines.line(line).length());
            actions.add(new CodeAction("Remove import", entry.getValue(),
                    List.of(new TextEdit(new Range(new Position(line, 0), end), ""))));
        }
        List<TextEdit> edits = new ArrayList<>();
        for (CodeAction action : unprotected(uri, text, actions)) {
            edits.addAll(action.edits());
        }
        return edits;
    }

    /** Lint findings are line-granular, so a request anywhere on a flagged line matches it. */
    static boolean overlaps(Range a, Range b) {
        return a.start().line() <= b.end().line() && b.start().line() <= a.end().line();
    }

    static CodeAction quickFix(Diagnostic diagnostic, TextLines lines) {
        int line = diagnostic.range().start().line();
        String text = lines.line(line);
        switch (diagnostic.code()) {
            case BasicFormatter.UNNECESSARY_SEMICOLON -> {
                int semicolon = text.lastIndexOf(';');
                if (semicolon < 0 || !text.substring(semicolon + 1).isBlank()) {
                    return null;
                }
                int end = semicolon;
                while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                    end--;
                }
                Range range = new Range(new Position(line, end), new Position(line, text.length()));
                return new CodeAction("Remove unnecessary semicolon", diagnostic, List.of(new TextEdit(range, "")));
            }
            case "TrailingWhitespace" -> {
                int end = text.length();
                while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                    end--;
                }
                if (end == text.length()) {
                    return null;
                }
                Range range = new Range(new Position(line, end), new Position(line, text.length()));
                return new CodeAction("Remove trailing whitespace", diagnostic, List.of(new TextEdit(range, "")));
            }
            case "ConsecutiveBlankLines" -> {
                if (!text.isBlank() || line + 1 >= lines.lineCount()) {
                    return null;
                }
                Range range = new Range(new Position(line, 0), new Position(line + 1, 0));
                return new CodeAction("Remove extra blank line", diagnostic, List.of(new TextEdit(range, "")));
            }
            default -> {
                return null;
            }
        }
    }

    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
