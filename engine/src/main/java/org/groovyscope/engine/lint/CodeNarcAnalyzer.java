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
        return ruleSets.computeIfAbsent(resolve(path), CodeNarcAnalyzer::load);
    }

    private static RuleSet load(String resolved) {
        try {
            return new GroovyDslRuleSet(resolved);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load CodeNarc rule set " + resolved + ": " + e.getMessage(), e);
        }
    }

    static String resolve(String path) {
        if (path == null || path.isBlank()) {
            return BUNDLED_DEFAULT;
        }
        if (path.startsWith("file:") || path.startsWith("http:") || path.startsWith("https:")) {
            return path;
        }
        Path candidate = Paths.get(path);
        if (candidate.isAbsolute() || Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath().toUri().toString();
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
        } catch (RuntimeException e) {
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
