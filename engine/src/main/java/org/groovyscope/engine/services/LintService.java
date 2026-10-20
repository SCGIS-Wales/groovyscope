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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.codenarc.ruleset.RuleSet;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.lint.CodeNarcAnalyzer;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.profiles.Profile;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * CodeNarc over a workspace: which profile a document is linted with, the rule set of each profile, the class
 * loader the enhanced rules resolve types through, and the findings, cached per document by content hash and
 * profile so publishing diagnostics after every edit only re-lints what changed.
 */
final class LintService {

    private final WorkspaceModel workspace;
    private final LanguageServices navigation;
    private final ProfileService profiles;
    private final CodeNarcAnalyzer codeNarc = new CodeNarcAnalyzer();
    private final Map<String, LintEntry> lintCache = new ConcurrentHashMap<>();

    private record LintEntry(String key, List<Diagnostic> diagnostics) {
    }

    /**
     * The class loader CodeNarc's enhanced rules resolve types through (Q-05): the workspace's source roots, so a
     * rule sees a superclass the workspace declares, and in a trusted workspace its classpath too (jars can bring
     * AST transformations, which only trust lets run). One per published snapshot; the one before the previous is
     * closed, so a lint still running on the previous keeps working.
     */
    private record LintLoader(long version, groovy.lang.GroovyClassLoader loader) {
    }

    private final AtomicReference<LintLoader> lintLoader = new AtomicReference<>();
    private final AtomicReference<LintLoader> previousLintLoader = new AtomicReference<>();

    LintService(WorkspaceModel workspace, LanguageServices navigation, ProfileService profiles) {
        this.workspace = workspace;
        this.navigation = navigation;
        this.profiles = profiles;
    }

    ClassLoader lintLoader() {
        long version = navigation.currentSnapshot().version();
        LintLoader current = lintLoader.get();
        if (current != null && current.version() == version) {
            return current.loader();
        }
        groovy.lang.GroovyClassLoader loader = new groovy.lang.GroovyClassLoader(
                new org.groovyscope.engine.compile.WorkspaceParentClassLoader(
                        org.groovyscope.engine.compile.GroovyCompiler.class.getClassLoader()));
        for (Path root : workspace.sourceRootPaths()) {
            loader.addClasspath(root.toString());
        }
        if (workspace.config().trust()) {
            for (Path entry : workspace.classpath()) {
                try {
                    loader.addURL(entry.toUri().toURL());
                } catch (java.net.MalformedURLException e) {
                    org.groovyscope.engine.log.EngineLog.debug("lint classpath entry %s skipped: %s", entry, e);
                }
            }
        }
        LintLoader fresh = new LintLoader(version, loader);
        if (!lintLoader.compareAndSet(current, fresh)) {
            closeQuietly(loader);
            return lintLoader.get().loader();
        }
        LintLoader retired = previousLintLoader.getAndSet(current);
        if (retired != null) {
            closeQuietly(retired.loader());
        }
        return loader;
    }

    private static void closeQuietly(groovy.lang.GroovyClassLoader loader) {
        try {
            loader.close();
        } catch (java.io.IOException e) {
            // the jars are released on GC at the latest
        }
    }

    /**
     * The profile of a file in this workspace: {@link QualityServices#profileByName}, and {@code jenkins} as well for
     * the {@code src} classes of a Jenkins shared library (a folder holding {@code vars/} beside {@code src/} or
     * {@code resources/}), which run on the same CPS runtime as its {@code vars} scripts.
     */
    String profileFor(String uri) {
        String byName = QualityServices.profileByName(uri);
        if (!QualityServices.PROFILE_DEFAULT.equals(byName)) {
            return byName;
        }
        Path file = WorkspaceModel.toPath(uri);
        return file != null && WorkspaceModel.inSharedLibrarySource(file, workspace.root())
                ? QualityServices.PROFILE_JENKINS : byName;
    }

    /** The rule set for a profile, from configuration or the bundled copies. */
    RuleSet ruleSet(String profile) {
        EngineConfig.Lint lint = workspace.config().lint();
        if (QualityServices.PROFILE_JENKINS.equals(profile)) {
            return codeNarc.ruleSet(lint.jenkinsRuleset() == null ? CodeNarcAnalyzer.BUNDLED_JENKINS : lint.jenkinsRuleset(),
                    workspace.root(), CodeNarcAnalyzer.BUNDLED_JENKINS, lint.rules());
        }
        if (QualityServices.PROFILE_GRADLE.equals(profile)) {
            return codeNarc.ruleSet(lint.gradleRuleset() == null ? CodeNarcAnalyzer.BUNDLED_GRADLE : lint.gradleRuleset(),
                    workspace.root(), CodeNarcAnalyzer.BUNDLED_GRADLE, lint.rules());
        }
        RuleSet base = codeNarc.ruleSet(lint.ruleset() == null ? CodeNarcAnalyzer.BUNDLED_DEFAULT : lint.ruleset(),
                workspace.root(), CodeNarcAnalyzer.BUNDLED_DEFAULT, lint.rules());
        return lint.licenceHeader() == null ? base : codeNarc.withLicenceHeader(base, lint.licenceHeader());
    }

    /** Lint one document; {@code profile} may be {@code null} to pick one from the path. */
    List<Diagnostic> lint(String uri, String text, String profile) {
        if (WorkspaceModel.isJavaUri(uri)) {
            return List.of(); // Java sources join the compile only; CodeNarc rules target Groovy
        }
        String chosen = profile == null || profile.isBlank() ? profileFor(uri) : profile;
        String key = chosen + ":" + QualityServices.hash(text);
        LintEntry cached = lintCache.get(uri);
        List<Diagnostic> diagnostics;
        if (cached != null && cached.key.equals(key)) {
            diagnostics = cached.diagnostics;
        } else {
            diagnostics = List.copyOf(codeNarc.analyze(uri, text, ruleSet(chosen), lintLoader()));
            lintCache.put(uri, new LintEntry(key, diagnostics));
        }
        return withoutDisabled(uri, diagnostics);
    }

    /** The findings minus the rules the governing runtime profile switches off. */
    private List<Diagnostic> withoutDisabled(String uri, List<Diagnostic> diagnostics) {
        Optional<Profile> governing = profiles.governing(uri);
        if (governing.isEmpty() || governing.get().codenarcDisable().isEmpty()) {
            return diagnostics;
        }
        List<String> disabled = governing.get().codenarcDisable();
        return diagnostics.stream().filter(d -> !disabled.contains(d.code())).toList();
    }

    /**
     * The lint findings already computed for this exact text, or none: for hover, which must not run CodeNarc on the
     * light lane (the diagnostics pass fills the cache).
     */
    List<Diagnostic> cachedLint(String uri, String text) {
        LintEntry cached = lintCache.get(uri);
        if (cached == null || text == null || !cached.key.endsWith(":" + QualityServices.hash(text))) {
            return List.of();
        }
        return withoutDisabled(uri, cached.diagnostics);
    }

    /** CodeNarc findings per target (on a lint thread; {@link #lint} caches by text). */
    Map<String, List<Diagnostic>> lintAll(List<String> targets, Map<String, String> sources, String profile) {
        Map<String, List<Diagnostic>> found = new HashMap<>();
        for (String uri : targets) {
            String text = sources.get(uri);
            if (text != null) {
                found.put(uri, lint(uri, text, profile));
            }
        }
        // A rule set naming rules this CodeNarc does not know still runs; the names are reported once per check.
        List<String> problems = codeNarc.configProblems();
        if (!problems.isEmpty() && !found.isEmpty()) {
            String first = new TreeSet<>(found.keySet()).first();
            List<Diagnostic> withProblem = new ArrayList<>(found.get(first));
            withProblem.add(new Diagnostic(first, Range.of(0, 0, 0, 1), Diagnostic.Severity.WARNING,
                    CodeNarcAnalyzer.SOURCE, "lint-config", String.join("; ", problems)));
            found.put(first, withProblem);
        }
        return found;
    }
}
