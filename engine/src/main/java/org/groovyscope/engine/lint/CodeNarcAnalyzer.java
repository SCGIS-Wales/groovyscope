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
package org.groovyscope.engine.lint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import org.codenarc.analyzer.StringSourceAnalyzer;
import org.codenarc.source.SourceString;
import org.codenarc.results.Results;
import org.codenarc.rule.Rule;
import org.codenarc.rule.Violation;
import org.codenarc.ruleset.GroovyDslRuleSet;
import org.codenarc.ruleset.RuleSet;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;

/**
 * Runs CodeNarc in process, one document at a time, with rule sets loaded once per path. Violations become
 * diagnostics whose code is the rule name and whose severity follows the rule priority.
 */
public final class CodeNarcAnalyzer {

    /** Reported as the diagnostic source. */
    public static final String SOURCE = "codenarc";
    /** Resource path of the bundled defensive profile. */
    public static final String BUNDLED_DEFAULT = "org/groovyscope/engine/lint/groovyscope.groovy";
    /** Resource path of the bundled Jenkins pipeline profile. */
    public static final String BUNDLED_JENKINS = "org/groovyscope/engine/lint/jenkins.groovy";

    private static final java.util.Set<String> REFUSED = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private final Map<String, RuleSet> ruleSets = new ConcurrentHashMap<>();
    private final ResourceBundle messages;

    public CodeNarcAnalyzer() {
        ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle("codenarc-base-messages");
        } catch (MissingResourceException e) {
            bundle = null;
        }
        messages = bundle;
    }

    /**
     * The rule set at {@code path}: an absolute or relative file path, a {@code file:} URL, or a classpath
     * resource. Loaded once; a failure to load propagates so the configuration error is visible.
     */
    public RuleSet ruleSet(String path) {
        return ruleSet(path, null, BUNDLED_DEFAULT);
    }

    /**
     * The rule set at {@code path}, with a relative file path resolved against the workspace root (not the
     * engine's working directory). Remote ({@code http:}/{@code https:}) locations are refused: the engine does
     * not fetch configuration over the network, so a warning goes to stderr and {@code fallback} is used.
     */
    public RuleSet ruleSet(String path, Path root, String fallback) {
        return ruleSets.computeIfAbsent(resolve(path, root, fallback), CodeNarcAnalyzer::load);
    }

    /**
     * The rule set at {@code path} with {@code lint.rules} overrides applied: a rule mapped to
     * {@link org.groovyscope.engine.config.EngineConfig.Lint#RULE_OFF} is removed, any other value replaces the
     * rule's priority (and so the severity of its findings). The overridden set is loaded afresh, so the shared
     * unmodified set keeps its priorities. Names the rule set does not contain are ignored with one warning.
     */
    public RuleSet ruleSet(String path, Path root, String fallback, Map<String, Integer> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return ruleSet(path, root, fallback);
        }
        String resolved = resolve(path, root, fallback);
        Map<String, Integer> sorted = new java.util.TreeMap<>(overrides);
        return ruleSets.computeIfAbsent(resolved + "#" + sorted, key -> withOverrides(resolved, load(resolved), sorted));
    }

    static RuleSet withOverrides(String name, RuleSet loaded, Map<String, Integer> overrides) {
        java.util.Set<String> unknown = new java.util.TreeSet<>(overrides.keySet());
        List<Rule> kept = new ArrayList<>();
        for (Object item : loaded.getRules()) {
            if (!(item instanceof Rule rule)) {
                continue;
            }
            Integer override = overrides.get(rule.getName());
            unknown.remove(rule.getName());
            if (override == null) {
                kept.add(rule);
            } else if (override != org.groovyscope.engine.config.EngineConfig.Lint.RULE_OFF) {
                if (rule instanceof org.codenarc.rule.AbstractRule configurable) {
                    configurable.setPriority(override);
                }
                kept.add(rule);
            }
        }
        if (!unknown.isEmpty() && WARNED.add(name + "#" + unknown)) {
            org.groovyscope.engine.log.EngineLog.warn("lint.rules names rule(s) not in %s, ignored: %s", name,
                    String.join(", ", unknown));
        }
        return new org.codenarc.ruleset.ListRuleSet(kept);
    }

    private static RuleSet load(String resolved) {
        try {
            return new GroovyDslRuleSet(resolved);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load CodeNarc rule set " + resolved + ": " + e.getMessage(), e);
        }
    }

    static String resolve(String path) {
        return resolve(path, null, BUNDLED_DEFAULT);
    }

    static String resolve(String path, Path root, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        String lower = path.strip().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http:") || lower.startsWith("https:")) {
            if (REFUSED.add(path)) {
                org.groovyscope.engine.log.EngineLog.warn("refusing remote CodeNarc rule set %s; using the bundled %s",
                        path, fallback);
            }
            return fallback;
        }
        if (path.startsWith("file:")) {
            return path;
        }
        Path candidate;
        try {
            candidate = Paths.get(path);
        } catch (java.nio.file.InvalidPathException e) {
            return path;
        }
        if (candidate.isAbsolute()) {
            return candidate.toUri().toString();
        }
        Path relative = root == null ? candidate.toAbsolutePath() : root.resolve(candidate).normalize();
        if (Files.isRegularFile(relative)) {
            return relative.toAbsolutePath().toUri().toString();
        }
        return path;
    }

    /** The file path of a {@code file:} URI with forward slashes, or {@code null}. */
    static String pathOf(String uri) {
        java.nio.file.Path path = org.groovyscope.engine.workspace.WorkspaceModel.toPath(uri);
        return path == null ? null : path.toString().replace('\\', '/');
    }

    /** Lints one document's text with the given rule set. */
    public List<Diagnostic> analyze(String uri, String text, RuleSet ruleSet) {
        Results results;
        try {
            StringSourceAnalyzer analyzer = new StringSourceAnalyzer(text);
            // With a path, rules' applyToFilesMatching / doNotApplyToFilesMatching work as they do for files.
            String path = pathOf(uri);
            if (path != null) {
                analyzer.setSource(new SourceString(text, path, path.substring(path.lastIndexOf('/') + 1)));
            }
            results = analyzer.analyze(ruleSet);
        } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
            // A rule that breaks on exotic input (or overflows the stack on deep nesting) must not take the
            // server down; OutOfMemoryError is left to propagate.
            return List.of(new Diagnostic(uri, Range.of(0, 0, 0, 1), Severity.WARNING, SOURCE, "codenarc-failed",
                    "CodeNarc could not analyse this file: " + e.getMessage()));
        }
        TextLines lines = new TextLines(text);
        List<Diagnostic> diagnostics = new ArrayList<>();
        collect(uri, results, lines, diagnostics);
        diagnostics.sort((a, b) -> a.range().start().compareTo(b.range().start()));
        return diagnostics;
    }

    private void collect(String uri, Results results, TextLines lines, List<Diagnostic> out) {
        if (results == null) {
            return;
        }
        List<?> violations = results.getViolations();
        if (violations != null) {
            for (Object item : violations) {
                if (item instanceof Violation violation) {
                    out.add(toDiagnostic(uri, violation, lines));
                }
            }
        }
        List<?> children;
        try {
            children = results.getChildren();
        } catch (UnsupportedOperationException e) {
            // Single-source analysis returns "virtual" results that have violations but no tree.
            return;
        }
        if (children != null) {
            for (Object child : children) {
                if (child instanceof Results nested) {
                    collect(uri, nested, lines, out);
                }
            }
        }
    }

    Diagnostic toDiagnostic(String uri, Violation violation, TextLines lines) {
        Rule rule = violation.getRule();
        String name = rule == null ? "unknown" : rule.getName();
        int priority = rule == null ? 3 : rule.getPriority();
        Integer lineNumber = violation.getLineNumber();
        int line = lineNumber == null || lineNumber < 1 ? 0 : lineNumber - 1;
        return new Diagnostic(uri, lineRange(lines, line), severity(priority), SOURCE, name, message(name, violation));
    }

    static Range lineRange(TextLines lines, int line) {
        String text = lines.line(line);
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        int end = Math.max(start + 1, text.length());
        return new Range(new Position(line, start), new Position(line, end));
    }

    /** The CodeNarc priority a lint finding's severity stands for (1 is most severe). */
    public static int priorityOf(Diagnostic diagnostic) {
        return switch (diagnostic.severity()) {
            case ERROR -> 1;
            case WARNING -> 2;
            default -> 3;
        };
    }

    static Severity severity(int priority) {
        return switch (priority) {
            case 1 -> Severity.ERROR;
            case 2 -> Severity.WARNING;
            default -> Severity.INFORMATION;
        };
    }

    String message(String ruleName, Violation violation) {
        String description = null;
        if (messages != null) {
            try {
                description = messages.getString(ruleName + ".description");
            } catch (MissingResourceException e) {
                description = null;
            }
        }
        String detail = violation.getMessage();
        if (description == null || description.isBlank()) {
            return detail == null || detail.isBlank() ? ruleName : detail;
        }
        return detail == null || detail.isBlank() ? description : description + " " + detail;
    }
}
