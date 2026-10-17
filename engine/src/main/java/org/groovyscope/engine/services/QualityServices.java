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
    public static final Set<String> ALL_INCLUDES = Set.of(INCLUDE_LINT, INCLUDE_COMPILE, INCLUDE_TYPECHECK);

    private final WorkspaceModel workspace;
    private final LanguageServices navigation;
    private final CodeNarcAnalyzer codeNarc = new CodeNarcAnalyzer();
    private final TypeCheckAnalyzer typeChecker = new TypeCheckAnalyzer();
    private final Map<String, LintEntry> lintCache = new ConcurrentHashMap<>();

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
            return codeNarc.ruleSet(lint.jenkinsRuleset() == null ? CodeNarcAnalyzer.BUNDLED_JENKINS : lint.jenkinsRuleset());
        }
        return codeNarc.ruleSet(lint.ruleset() == null ? CodeNarcAnalyzer.BUNDLED_DEFAULT : lint.ruleset());
    }

    /** Lint one document; {@code profile} may be {@code null} to pick one from the path. */
    public List<Diagnostic> lint(String uri, String text, String profile) {
        if (WorkspaceModel.isJavaUri(uri)) {
            return List.of(); // Java sources join the compile only; CodeNarc rules target Groovy
        }
        String chosen = profile == null || profile.isBlank() ? profileFor(uri) : profile;
        String key = chosen + ":" + hash(text);
        LintEntry cached = lintCache.get(uri);
        if (cached != null && cached.key.equals(key)) {
            return cached.diagnostics;
        }
        List<Diagnostic> diagnostics = codeNarc.analyze(uri, text, ruleSet(chosen));
        lintCache.put(uri, new LintEntry(key, List.copyOf(diagnostics)));
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
        if (wanted.contains(INCLUDE_TYPECHECK)) {
            Map<String, List<Diagnostic>> typed = typeChecker.analyze(workspace.compileSources(), workspace.classpath(),
                    workspace.config().trust(), workspace.config().typecheck());
            for (String uri : targets) {
                for (Diagnostic diagnostic : typed.getOrDefault(uri, List.of())) {
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
        for (Diagnostic diagnostic : lint(uri, text, null)) {
            if (!overlaps(diagnostic.range(), range)) {
                continue;
            }
            CodeAction action = quickFix(diagnostic, lines);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
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
