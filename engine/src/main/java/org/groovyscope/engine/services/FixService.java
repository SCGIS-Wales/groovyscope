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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.compile.GroovyCompiler;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.format.BasicFormatter;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.lint.CodeNarcAnalyzer;
import org.groovyscope.engine.model.CodeAction;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.practices.PracticeCatalog;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.practices.PracticeFixes;
import org.groovyscope.engine.profiles.ProfileFinder;
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * Edits that improve code: the batch fix with its behaviour-preserving rules and {@code @CompileStatic} verdicts,
 * quick fixes and code actions, organising imports and the formatter.
 */
final class FixService {

    /** CodeNarc rules {@link #quickFix} or {@link PracticeFixes#lintFixes} can fix mechanically. */
    static final Set<String> FIXABLE_LINT_CODES = Set.of(BasicFormatter.UNNECESSARY_SEMICOLON, "TrailingWhitespace",
            "ConsecutiveBlankLines", "CompileStatic", "UnnecessaryDefInVariableDeclaration",
            "UnnecessaryDefInMethodDeclaration", "UnnecessaryDefInFieldDeclaration", "UnnecessaryGString",
            "GStringAsMapKey", "NoDef", "VariableTypeRequired");
    /** CodeNarc rules whose findings {@link #organizeImports} removes. */
    static final Set<String> IMPORT_CODES = Set.of("UnusedImport", "UnnecessaryGroovyImport", "DuplicateImport");

    private final WorkspaceModel workspace;
    private final LanguageServices navigation;
    private final LintService lint;
    private final ProfileService profiles;
    private final TypeCheckService typeCheck;
    private final GroovyCompiler compiler;
    private final Function<String, List<PracticeFinder.Practice>> practices;

    FixService(WorkspaceModel workspace, LanguageServices navigation, LintService lint, ProfileService profiles,
            TypeCheckService typeCheck, GroovyCompiler compiler, Function<String, List<PracticeFinder.Practice>> practices) {
        this.workspace = workspace;
        this.navigation = navigation;
        this.lint = lint;
        this.profiles = profiles;
        this.typeCheck = typeCheck;
        this.compiler = compiler;
        this.practices = practices;
    }

    /** The formatting edits for one document (empty when it is already formatted). */
    List<TextEdit> format(String uri) {
        String text = workspace.text(uri);
        if (text == null || WorkspaceModel.isJavaUri(uri)) {
            return List.of();
        }
        EngineConfig.Format settings = workspace.config().format();
        if (!"basic".equalsIgnoreCase(settings.engine())) {
            throw new IllegalStateException("formatter engine '" + settings.engine()
                    + "' is not bundled in this release; use format.engine: basic");
        }
        return BasicFormatter.edits(text, settings, lint.lint(uri, text, null));
    }

    /**
     * The formatter's edits for the lines a range covers (a range ending at column 0 stops at the line before), one
     * edit per changed line.
     */
    List<TextEdit> formatRange(String uri, Range range) {
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
        return BasicFormatter.rangeEdits(text, settings, lint.lint(uri, text, null), range.start().line(), last);
    }

    /** Quick fixes for the lint findings that overlap {@code range} in {@code uri}. */
    List<CodeAction> codeActions(String uri, Range range) {
        String text = workspace.text(uri);
        if (text == null) {
            return List.of();
        }
        TextLines lines = new TextLines(text);
        List<CodeAction> actions = new ArrayList<>();
        ModuleNode module = navigation.snapshot().result().modules().get(uri);
        for (Diagnostic diagnostic : lint.lint(uri, text, null)) {
            if (!overlaps(diagnostic.range(), range)) {
                continue;
            }
            CodeAction action = quickFix(diagnostic, lines);
            if (action != null) {
                actions.add(action);
            }
            actions.addAll(PracticeFixes.lintFixes(diagnostic, lines, module));
        }
        for (PracticeFinder.Practice practice : practices.apply(uri)) {
            if (practice.fixable() && overlaps(practice.diagnostic().range(), range)) {
                actions.add(new CodeAction(practice.fixTitle(), practice.diagnostic(), practice.fix()));
            }
            if (overlaps(practice.diagnostic().range(), range)) {
                for (PracticeFinder.Alternative alternative : practice.alternatives()) {
                    actions.add(new CodeAction(alternative.title(), practice.diagnostic(), alternative.edits()));
                }
            }
        }
        return profiles.unprotected(uri, text, actions);
    }

    /** See {@link QualityServices#fix(Collection, Set, Map)}. */
    QualityServices.FixResult fix(Collection<String> uris, Set<String> codes, Map<String, List<int[]>> onlyLines) {
        Map<String, String> sources = workspace.sources();
        List<String> targets = uris == null || uris.isEmpty() ? new ArrayList<>(sources.keySet()) : new ArrayList<>(uris);
        Map<String, List<TextEdit>> edits = new TreeMap<>();
        Map<String, List<String>> applied = new TreeMap<>();
        Map<String, Map<String, Integer>> appliedCounts = new TreeMap<>();
        Map<String, Map<String, Integer>> left = new TreeMap<>();
        Map<String, String> rejected = new TreeMap<>();
        Map<String, Map<String, Integer>> needsReview = new TreeMap<>();
        Map<String, Map<String, Boolean>> staticOk = staticVerdicts(targets, sources, codes);
        for (String uri : targets) {
            org.groovyscope.engine.concurrent.Cancellation.checkpoint();
            String text = sources.get(uri);
            if (text == null || WorkspaceModel.isJavaUri(uri)) {
                continue;
            }
            Map<String, Integer> missed = new TreeMap<>();
            Map<String, Integer> review = new TreeMap<>();
            List<int[]> only = onlyLines == null ? null : onlyLines.getOrDefault(uri, List.of());
            List<CodeAction> chosen = chooseFixes(uri, text, codes, staticOk, missed, only);
            // A fix that changes behaviour goes in only when its code was asked for by name; otherwise it is listed.
            chosen.removeIf(action -> {
                String code = action.diagnostic().code();
                boolean asked = codes != null && codes.contains(code);
                // @CompileDynamic is Groovy's default made explicit; a runtime profile's fixes are the migrations its
                // author declares; both keep behaviour.
                boolean keeps = PracticeFixes.ADD_COMPILE_DYNAMIC.equals(action.title())
                        || ProfileFinder.SOURCE.equals(action.diagnostic().source());
                if (asked || keeps || PracticeCatalog.PRESERVING.equals(PracticeCatalog.bundled().safety(code))) {
                    return false;
                }
                review.merge(code, 1, Integer::sum);
                return true;
            });
            if (!review.isEmpty()) {
                needsReview.put(uri, review);
            }
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
        return new QualityServices.FixResult(edits, applied, appliedCounts, left, rejected, needsReview);
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

    private List<CodeAction> chooseFixes(String uri, String text, Set<String> codes,
            Map<String, Map<String, Boolean>> staticOk, Map<String, Integer> missed, List<int[]> only) {
        TextLines lines = new TextLines(text);
        ModuleNode module = navigation.snapshot().result().modules().get(uri);
        List<CodeAction> chosen = new ArrayList<>();
        for (Diagnostic diagnostic : lint.lint(uri, text, null)) {
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
                ClassNode target = PracticeFixes.classOnLine(module, diagnostic.range().start().line());
                Boolean passes = target == null ? null : staticOk.getOrDefault(uri, Map.of()).get(target.getName());
                if (passes == null) {
                    missed.merge(diagnostic.code(), 1, Integer::sum); // not type-checked: no verdict, no annotation
                } else if (passes) {
                    chosen.add(options.get(0));
                } else {
                    chosen.add(PracticeFixes.justifyDynamic(options.get(1)));
                    missed.merge(diagnostic.code(), 1, Integer::sum); // left dynamic, to justify
                }
            } else if (!options.isEmpty()) {
                chosen.add(options.get(0));
            } else if (FIXABLE_LINT_CODES.contains(diagnostic.code())) {
                missed.merge(diagnostic.code(), 1, Integer::sum);
            }
        }
        for (PracticeFinder.Practice practice : practices.apply(uri)) {
            if (practice.fixable() && (codes == null || codes.isEmpty() || codes.contains(practice.diagnostic().code()))
                    && (only == null || onLines(practice.diagnostic(), only))) {
                chosen.add(new CodeAction(practice.fixTitle(), practice.diagnostic(), practice.fix()));
            }
        }
        return profiles.unprotected(uri, text, chosen);
    }

    /**
     * For every class with a {@code CompileStatic} finding in the target documents: whether it passes Groovy's own
     * static type checker once annotated (per document, then per class name). Every class of a document is annotated
     * in one trial text, all documents are checked in one whole-workspace pass, and each error is charged to the class
     * that holds it. A class is absent when its document could not be checked (unresolved, timed out, crashed): it
     * gets no annotation either way.
     */
    private Map<String, Map<String, Boolean>> staticVerdicts(Collection<String> uris, Map<String, String> sources,
            Set<String> codes) {
        Map<String, String> candidates = new LinkedHashMap<>();
        Map<String, Set<String>> classes = new HashMap<>();
        for (String uri : uris) {
            String text = sources.get(uri);
            if (text == null || WorkspaceModel.isJavaUri(uri)) {
                continue;
            }
            TextLines lines = new TextLines(text);
            ModuleNode module = navigation.snapshot().result().modules().get(uri);
            List<TextEdit> edits = new ArrayList<>();
            for (Diagnostic diagnostic : lint.lint(uri, text, null)) {
                if (!"CompileStatic".equals(diagnostic.code())
                        || (codes != null && !codes.isEmpty() && !codes.contains(diagnostic.code()))) {
                    continue;
                }
                List<CodeAction> options = PracticeFixes.lintFixes(diagnostic, lines, module);
                ClassNode target = PracticeFixes.classOnLine(module, diagnostic.range().start().line());
                if (options.size() == 2 && target != null) {
                    options.get(0).edits().stream().filter(edit -> !edits.contains(edit)).forEach(edits::add);
                    classes.computeIfAbsent(uri, k -> new LinkedHashSet<>()).add(target.getName());
                }
            }
            if (!edits.isEmpty()) {
                candidates.put(uri, PracticeFixes.apply(text, edits).text());
            }
        }
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, String> trial = new LinkedHashMap<>(workspace.compileSources());
        trial.putAll(candidates);
        TypeCheckAnalyzer.Result typed = typeCheck.coreCheck(trial);
        Map<String, Map<String, Boolean>> verdicts = new HashMap<>();
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            String uri = candidate.getKey();
            if (typed.status().get(uri) != TypeCheckAnalyzer.Status.CHECKED) {
                continue;
            }
            ModuleNode annotated = compiler.parseOnly(uri, candidate.getValue()).orElse(null);
            if (annotated == null) {
                // the trial text does not parse: no verdict rather than a failure for every class
                continue;
            }
            Set<String> failing = new HashSet<>();
            for (Diagnostic error : typed.diagnostics().getOrDefault(uri, List.of())) {
                ClassNode holder = PracticeFixes.classOnLine(annotated, error.range().start().line());
                // An error in a nested or anonymous class counts against the enclosing class the annotation covers.
                while (holder != null && !classes.get(uri).contains(holder.getName())
                        && holder.getOuterClass() != null) {
                    holder = holder.getOuterClass();
                }
                failing.add(holder == null ? "" : holder.getName());
            }
            Map<String, Boolean> perClass = new HashMap<>();
            for (String name : classes.get(uri)) {
                perClass.put(name, !failing.contains(name) && !failing.contains(""));
            }
            verdicts.put(uri, perClass);
        }
        return verdicts;
    }

    /**
     * Whether a published finding has a quick fix: a lint rule with a mechanical fix, or a practice finding that
     * carries one. Cheap: lint codes come from a fixed set and practice findings from the per-snapshot cache.
     */
    boolean fixable(Diagnostic diagnostic) {
        if (CodeNarcAnalyzer.SOURCE.equals(diagnostic.source())) {
            return FIXABLE_LINT_CODES.contains(diagnostic.code());
        }
        if (diagnostic.uri() == null || GroovyCompiler.SOURCE.equals(diagnostic.source())) {
            return false;
        }
        for (PracticeFinder.Practice practice : practices.apply(diagnostic.uri())) {
            if (practice.fixable() && practice.diagnostic().equals(diagnostic)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Edits that delete the imports CodeNarc reports as unused, unnecessary (auto-imported) or duplicated. A line is
     * removed only when it holds that single import, and never inside a region the governing profile protects.
     */
    List<TextEdit> organizeImports(String uri) {
        String text = workspace.text(uri);
        if (text == null || WorkspaceModel.isJavaUri(uri)) {
            return List.of();
        }
        TextLines lines = new TextLines(text);
        SortedMap<Integer, Diagnostic> remove = new TreeMap<>();
        for (Diagnostic diagnostic : lint.lint(uri, text, null)) {
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
        for (CodeAction action : profiles.unprotected(uri, text, actions)) {
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
}
