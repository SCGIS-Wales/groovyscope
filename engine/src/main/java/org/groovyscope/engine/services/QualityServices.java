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

    /** What {@link #fix} changed in one document. */
    public record FixResult(Map<String, List<TextEdit>> edits, Map<String, List<String>> applied,
            Map<String, String> rejected) {
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
                    workspace.root(), CodeNarcAnalyzer.BUNDLED_JENKINS);
        }
        return codeNarc.ruleSet(lint.ruleset() == null ? CodeNarcAnalyzer.BUNDLED_DEFAULT : lint.ruleset(),
                workspace.root(), CodeNarcAnalyzer.BUNDLED_DEFAULT);
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

    /** Compile diagnostics plus, when {@code lint.publish} is on, lint findings for every document. */
    public Map<String, List<Diagnostic>> publishedDiagnostics() {
        Map<String, List<Diagnostic>> merged = new TreeMap<>();
        Map<String, String> sources = workspace.sources();
        for (Map.Entry<String, List<Diagnostic>> entry : navigation.diagnostics().entrySet()) {
            merged.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        if (workspace.config().lint().publish()) {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                merged.computeIfAbsent(source.getKey(), k -> new ArrayList<>())
                        .addAll(lint(source.getKey(), source.getValue(), null));
            }
        }
        for (String uri : sources.keySet()) {
            merged.computeIfAbsent(uri, k -> new ArrayList<>()).addAll(practiceDiagnostics(uri));
        }
        for (String uri : sources.keySet()) {
            merged.computeIfAbsent(uri, k -> new ArrayList<>());
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
        if (wanted.contains(INCLUDE_TYPECHECK)) {
            Map<String, List<Diagnostic>> typed = typeChecker.analyze(workspace.compileSources(), workspace.classpath(),
                    workspace.config().trust(), workspace.config().typecheck(), workspace.config().groovy(),
                    navigation.snapshot().result().unresolvedUris());
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
        Map<String, String> sources = workspace.sources();
        List<String> targets = uris == null || uris.isEmpty() ? new ArrayList<>(sources.keySet()) : new ArrayList<>(uris);
        Map<String, List<TextEdit>> edits = new TreeMap<>();
        Map<String, List<String>> applied = new TreeMap<>();
        Map<String, String> rejected = new TreeMap<>();
        for (String uri : targets) {
            String text = sources.get(uri);
            if (text == null || WorkspaceModel.isJavaUri(uri)) {
                continue;
            }
            List<CodeAction> chosen = chooseFixes(uri, text, codes);
            if (chosen.isEmpty()) {
                continue;
            }
            List<TextEdit> all = new ArrayList<>();
            List<String> used = new ArrayList<>();
            for (CodeAction action : chosen) {
                all.addAll(action.edits());
                used.add(action.diagnostic().code());
            }
            PracticeFixes.Applied result = PracticeFixes.apply(text, all);
            if (!compiler.parses(uri, result.text())) {
                rejected.put(uri, "the fixed text does not parse; nothing applied");
                continue;
            }
            edits.put(uri, result.edits());
            applied.put(uri, used);
        }
        return new FixResult(edits, applied, rejected);
    }

    private List<CodeAction> chooseFixes(String uri, String text, Set<String> codes) {
        TextLines lines = new TextLines(text);
        org.codehaus.groovy.ast.ModuleNode module = navigation.snapshot().result().modules().get(uri);
        List<CodeAction> chosen = new ArrayList<>();
        for (Diagnostic diagnostic : lint(uri, text, null)) {
            if (codes != null && !codes.isEmpty() && !codes.contains(diagnostic.code())) {
                continue;
            }
            CodeAction textual = quickFix(diagnostic, lines);
            if (textual != null) {
                chosen.add(textual);
                continue;
            }
            List<CodeAction> options = PracticeFixes.lintFixes(diagnostic, lines, module);
            if ("CompileStatic".equals(diagnostic.code()) && options.size() == 2) {
                chosen.add(staticCompiles(uri, text, options.get(0)) ? options.get(0) : options.get(1));
            } else if (!options.isEmpty()) {
                chosen.add(options.get(0));
            }
        }
        for (PracticeFinder.Practice practice : practices(uri)) {
            if (practice.fixable() && (codes == null || codes.isEmpty() || codes.contains(practice.diagnostic().code()))) {
                chosen.add(new CodeAction(practice.fixTitle(), practice.diagnostic(), practice.fix()));
            }
        }
        return unprotected(uri, text, chosen);
    }

    /** Whether the document still passes the static type checker with {@code action} applied. */
    private boolean staticCompiles(String uri, String text, CodeAction action) {
        Map<String, String> sources = new java.util.LinkedHashMap<>(workspace.compileSources());
        sources.put(uri, PracticeFixes.apply(text, action.edits()).text());
        Map<String, List<Diagnostic>> typed = typeChecker.analyze(sources, workspace.classpath(),
                workspace.config().trust(), workspace.config().typecheck(), workspace.config().groovy(),
                navigation.snapshot().result().unresolvedUris());
        return typed.getOrDefault(uri, List.of()).isEmpty();
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
