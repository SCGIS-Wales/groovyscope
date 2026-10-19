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

import groovyjarjarantlr4.v4.runtime.CharStreams;
import groovyjarjarantlr4.v4.runtime.Token;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.groovy.parser.antlr4.GroovyLangLexer;
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

    /** The formatted text; the document's line endings are kept (CRLF stays CRLF). */
    public static String format(String text, EngineConfig.Format settings, List<Diagnostic> lintFindings) {
        List<String> formatted = formattedLines(text, settings, lintFindings);
        List<String> endings = lineEndings(text);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> out = new ArrayList<>();
        List<String> outEndings = new ArrayList<>();
        for (int i = 0; i < formatted.size(); i++) {
            if (formatted.get(i) != null) {
                out.add(formatted.get(i));
                outEndings.add(i < endings.size() && !endings.get(i).isEmpty() ? endings.get(i) : newline);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
            outEndings.remove(outEndings.size() - 1);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            result.append(out.get(i)).append(outEndings.get(i));
        }
        return result.toString();
    }

    /** Each line's terminator ({@code \r\n}, {@code \n}, or empty for a last line without one). */
    static List<String> lineEndings(String text) {
        List<String> endings = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) {
                if (start < text.length()) {
                    endings.add("");
                }
                break;
            }
            endings.add(newline > start && text.charAt(newline - 1) == '\r' ? "\r\n" : "\n");
            start = newline + 1;
        }
        return endings;
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

    /**
     * Each input line formatted, or {@code null} where the line is dropped (a blank line over the limit). The Groovy
     * lexer marks strings and block comments: a line that starts inside one keeps its indentation and is never
     * dropped, a line that ends inside one keeps its trailing whitespace, so no string changes value. When the text
     * does not lex, every line is returned as it is.
     */
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
        List<int[]> spans = protectedSpans(text);
        List<String> out = new ArrayList<>();
        int blankRun = 0;
        int offset = 0;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            int start = offset;
            int end = offset + line.length();
            offset += raw.length() + 1;
            if (spans == null) {
                out.add(line);
                continue;
            }
            boolean startsInside = inside(spans, start);
            boolean endsInside = inside(spans, end);
            if (!startsInside) {
                line = expandLeadingTabs(line, indent);
            }
            if (!endsInside) {
                line = stripTrailing(line);
                if (semicolonLines.contains(i)) {
                    line = removeTrailingSemicolon(line);
                }
            }
            if (line.isEmpty() && !startsInside && !endsInside) {
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

    /** True when {@code offset} lies after the start of a span and no later than its end. */
    static boolean inside(List<int[]> spans, int offset) {
        for (int[] span : spans) {
            if (span[0] < offset && offset <= span[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code [start, end]} character offsets of every string literal, GString (from its start to its end, nested ones
     * included) and block comment, from Groovy's own lexer; null when the text does not lex.
     */
    static List<int[]> protectedSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        try {
            GroovyLangLexer lexer = new GroovyLangLexer(CharStreams.fromString(text));
            lexer.removeErrorListeners();
            int depth = 0;
            int gstringStart = -1;
            for (Token token : lexer.getAllTokens()) {
                String name = lexer.getVocabulary().getSymbolicName(token.getType());
                if ("GStringBegin".equals(name)) {
                    if (depth++ == 0) {
                        gstringStart = token.getStartIndex();
                    }
                } else if ("GStringEnd".equals(name) && depth > 0) {
                    if (--depth == 0) {
                        spans.add(new int[] {gstringStart, token.getStopIndex()});
                    }
                } else if ("StringLiteral".equals(name) && depth == 0
                        || token.getText().startsWith("/*") && depth == 0) {
                    spans.add(new int[] {token.getStartIndex(), token.getStopIndex()});
                }
            }
        } catch (RuntimeException | AssertionError | StackOverflowError e) {
            // the lexer reports an unclosed string as GroovySyntaxError, an AssertionError
            return null;
        }
        return spans;
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

}
