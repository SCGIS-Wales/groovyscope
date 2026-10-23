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
package org.groovyscope.engine.refactor;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;

/** Offsets in a Groovy source: node positions as offsets, brackets matched past strings and comments, type tokens. */
final class SourceText {

    /** The modifiers that may stand before a declaration's name where a type would. */
    static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", "final", "abstract",
            "synchronized", "native", "transient", "volatile", "strictfp", "default");

    private SourceText() {
    }

    /** The offset of a node's start, or -1 for a node without a position. */
    static int start(TextLines lines, ASTNode node) {
        if (node == null || node.getLineNumber() < 1 || node.getColumnNumber() < 1) {
            return -1;
        }
        return lines.offsetOf(new Position(node.getLineNumber() - 1, node.getColumnNumber() - 1));
    }

    /** The offset just past a node's end, or -1 for a node without a position. */
    static int end(TextLines lines, ASTNode node) {
        if (node == null || node.getLastLineNumber() < 1 || node.getLastColumnNumber() < 1) {
            return -1;
        }
        return lines.offsetOf(new Position(node.getLastLineNumber() - 1, node.getLastColumnNumber() - 1));
    }

    /** The source of a node, or {@code null} without a position. */
    static String of(TextLines lines, ASTNode node) {
        int start = start(lines, node);
        int end = end(lines, node);
        return start < 0 || end < start ? null : lines.text().substring(start, end);
    }

    /** The zero-based range of {@code [start, end)}. */
    static Range range(TextLines lines, int start, int end) {
        return new Range(lines.positionOf(start), lines.positionOf(end));
    }

    /** The first offset at or after {@code from} that is neither whitespace nor inside a comment. */
    static int skipBlank(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end + 1;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else {
                return i;
            }
        }
        return text.length();
    }

    /**
     * The offset of the bracket that closes the one at {@code open} (a parenthesis, bracket or brace), past
     * strings, comments and nested brackets; -1 when it is not closed.
     */
    static int matching(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
            } else if (c == '\'' || c == '"') {
                i = stringEnd(text, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The offset of the quote that closes the string opening at {@code start} (the text's end when unclosed). */
    private static int stringEnd(String text, int start) {
        char quote = text.charAt(start);
        String triple = String.valueOf(quote).repeat(3);
        if (text.startsWith(triple, start)) {
            int end = text.indexOf(triple, start + 3);
            return end < 0 ? text.length() : end + 2;
        }
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote || c == '\n') {
                return i;
            }
        }
        return text.length();
    }

    /**
     * The type written directly before a declaration's name ({@code int}, {@code List<String>}, {@code a.B[]},
     * {@code def}) as {@code [start, end)}, or {@code null} when nothing but modifiers or annotations stands there.
     */
    static int[] typeBefore(String text, int nameStart) {
        int end = nameStart;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int i = end;
        int angle = 0;
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (c == '>') {
                angle++;
            } else if (c == '<') {
                angle--;
            } else if (angle == 0 && !(Character.isJavaIdentifierPart(c) || c == '.' || c == '[' || c == ']')) {
                break;
            } else if (angle > 0 && c == '\n') {
                return null;
            }
            i--;
        }
        if (i == end || angle != 0) {
            return null;
        }
        String token = text.substring(i, end);
        if (MODIFIERS.contains(token) || token.startsWith("@") || i > 0 && text.charAt(i - 1) == '@') {
            return null;
        }
        return new int[] {i, end};
    }

    /** Whether {@code name} is a Java identifier that is not a keyword Groovy reserves. */
    static boolean identifier(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED.contains(name);
    }

    private static final Set<String> RESERVED = Set.of("abstract", "as", "assert", "boolean", "break", "byte",
            "case", "catch", "char", "class", "const", "continue", "def", "default", "do", "double", "else", "enum",
            "extends", "false", "final", "finally", "float", "for", "goto", "if", "implements", "import", "in",
            "instanceof", "int", "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "trait", "transient", "true", "try", "var", "void", "volatile", "while");

    /** Annotations at the start of a text, to leave out of a signature. */
    static final Pattern LEADING_ANNOTATIONS = Pattern.compile("^(@[\\w.]+(\\([^)]*\\))?\\s*)+");

    /**
     * A method's signature on one line, from its modifiers to its body (annotations left out), with the spans that
     * lie in it applied.
     */
    static String header(String source, TextLines lines, MethodNode method, List<Changes.Span> spans) {
        int start = start(lines, method);
        int end = method.getCode() != null ? start(lines, method.getCode()) : end(lines, method);
        if (start < 0 || end <= start) {
            return method.getName();
        }
        String text = Changes.slice(source, start, end, spans == null ? List.of() : spans)
                .replaceAll("\\s+", " ").strip();
        text = LEADING_ANNOTATIONS.matcher(text).replaceFirst("").strip();
        return text.endsWith(";") ? text.substring(0, text.length() - 1).strip() : text;
    }

    /**
     * Where a call's name is, from the range of its node: the {@code name} that ends right before {@code from} when
     * the range starts at the parentheses (Groovy positions a static call that is the receiver of another call so),
     * else the first {@code name} in {@code [from, to)} that is a whole word followed by an opening parenthesis; -1
     * when there is none.
     */
    static int callName(String text, int from, int to, String name) {
        int back = Math.min(from, text.length());
        while (back > 0 && (text.charAt(back - 1) == ' ' || text.charAt(back - 1) == '\t')) {
            back--;
        }
        int before = back - name.length();
        if (from < text.length() && text.charAt(from) == '(' && before >= 0 && text.startsWith(name, before)
                && (before == 0 || !Character.isJavaIdentifierPart(text.charAt(before - 1)))) {
            return before;
        }
        Matcher matcher = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(")
                .matcher(text.substring(Math.max(0, from), Math.min(text.length(), Math.max(from, to))));
        return matcher.find() ? Math.max(0, from) + matcher.start() : -1;
    }

    /** The file name at the end of a URI, for messages ({@code Caller.groovy}). */
    static String fileName(String uri) {
        String text = uri == null ? "" : uri;
        return text.substring(Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1);
    }

    /** {@code Caller.groovy:12} for a zero-based line of a document. */
    static String where(String uri, int line) {
        return fileName(uri) + ":" + (line + 1);
    }

    /** The leading spaces and tabs of a line. */
    static String indent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /** Whether a line holds nothing but blanks or a comment ({@code //}, {@code /*}, {@code *}). */
    static boolean blankOrComment(String line) {
        String text = line.strip();
        return text.isEmpty() || text.startsWith("//") || text.startsWith("/*") || text.startsWith("*");
    }
}
