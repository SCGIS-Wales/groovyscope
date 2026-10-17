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
package org.groovyscope.engine.format;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.TextEdit;

/**
 * The bundled formatter: whitespace hygiene plus fixes that CodeNarc has already proven safe. It never
 * re-flows code. Inside triple-quoted strings only tabs-to-spaces at line starts are skipped as well, so
 * string contents are left alone.
 *
 * <ul>
 *   <li>leading tabs become {@code indentSize} spaces;</li>
 *   <li>trailing whitespace is removed;</li>
 *   <li>runs of blank lines are capped at {@code maxBlankLines};</li>
 *   <li>the file ends with exactly one newline;</li>
 *   <li>semicolons on lines CodeNarc flagged as {@code UnnecessarySemicolon} are removed.</li>
 * </ul>
 */
public final class BasicFormatter {

    /** Rule whose findings drive semicolon removal. */
    public static final String UNNECESSARY_SEMICOLON = "UnnecessarySemicolon";

    private BasicFormatter() {
    }

    /** The formatted text. */
    public static String format(String text, EngineConfig.Format settings, List<Diagnostic> lintFindings) {
        List<String> out = new ArrayList<>();
        for (String line : formattedLines(text, settings, lintFindings)) {
            if (line != null) {
                out.add(line);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out.isEmpty() ? "" : String.join("\n", out) + "\n";
    }

    /**
     * The formatter's edits restricted to the lines {@code firstLine..lastLine} (zero-based, inclusive): one edit per
     * changed line, a dropped blank line deleted with its line break. The end-of-file newline is left alone.
     */
    public static List<TextEdit> rangeEdits(String text, EngineConfig.Format settings, List<Diagnostic> lintFindings,
            int firstLine, int lastLine) {
        List<String> formatted = formattedLines(text, settings, lintFindings);
        TextLines lines = new TextLines(text);
        List<TextEdit> edits = new ArrayList<>();
        for (int i = Math.max(0, firstLine); i <= lastLine && i < formatted.size(); i++) {
            String original = lines.line(i);
            String replacement = formatted.get(i);
            if (replacement == null) {
                edits.add(new TextEdit(new org.groovyscope.engine.model.Range(
                        new org.groovyscope.engine.model.Position(i, 0),
                        new org.groovyscope.engine.model.Position(i + 1, 0)), ""));
            } else if (!replacement.equals(original)) {
                edits.add(new TextEdit(new org.groovyscope.engine.model.Range(
                        new org.groovyscope.engine.model.Position(i, 0),
                        new org.groovyscope.engine.model.Position(i, original.length())), replacement));
            }
        }
        return edits;
    }

    /** Each input line formatted, or {@code null} where the line is dropped (a blank line over the limit). */
    static List<String> formattedLines(String text, EngineConfig.Format settings, List<Diagnostic> lintFindings) {
        Set<Integer> semicolonLines = new HashSet<>();
        if (settings.removeSemicolons()) {
            for (Diagnostic finding : lintFindings) {
                if (UNNECESSARY_SEMICOLON.equals(finding.code())) {
                    semicolonLines.add(finding.range().start().line());
                }
            }
        }
        String indent = " ".repeat(Math.max(1, settings.indentSize()));
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        List<String> out = new ArrayList<>();
        boolean inTripleString = false;
        int blankRun = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            boolean insideString = inTripleString;
            inTripleString = togglesTripleQuote(line, inTripleString);
            if (!insideString) {
                line = expandLeadingTabs(line, indent);
                line = stripTrailing(line);
                if (semicolonLines.contains(i)) {
                    line = removeTrailingSemicolon(line);
                }
            }
            if (line.isEmpty() && !insideString) {
                blankRun++;
                if (blankRun > Math.max(0, settings.maxBlankLines())) {
                    out.add(null);
                    continue;
                }
            } else {
                blankRun = 0;
            }
            out.add(line);
        }
        return out;
    }

    /** A single whole-document edit when formatting changes anything; otherwise no edits. */
    public static List<TextEdit> edits(String text, EngineConfig.Format settings, List<Diagnostic> lintFindings) {
        String formatted = format(text, settings, lintFindings);
        if (formatted.equals(text)) {
            return List.of();
        }
        return List.of(new TextEdit(new TextLines(text).fullRange(), formatted));
    }

    static String expandLeadingTabs(String line, String indent) {
        int i = 0;
        StringBuilder prefix = new StringBuilder();
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            prefix.append(line.charAt(i) == '\t' ? indent : " ");
            i++;
        }
        return prefix + line.substring(i);
    }

    static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    static String removeTrailingSemicolon(String line) {
        String stripped = stripTrailing(line);
        if (stripped.endsWith(";")) {
            return stripTrailing(stripped.substring(0, stripped.length() - 1));
        }
        return line;
    }

    /** Whether the line leaves the reader inside a triple-quoted string (naive: ignores escapes in code). */
    static boolean togglesTripleQuote(String line, boolean inside) {
        boolean state = inside;
        int i = 0;
        while (i < line.length()) {
            if (!state && line.startsWith("//", i)) {
                break;
            }
            if (line.startsWith("'''", i) || line.startsWith("\"\"\"", i)) {
                state = !state;
                i += 3;
                continue;
            }
            i++;
        }
        return state;
    }
}
