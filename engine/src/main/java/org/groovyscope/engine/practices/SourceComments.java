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
package org.groovyscope.engine.practices;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * The comments of a Groovy source and the lines that hold code, found by a small lexer: comments are not nodes of
 * the syntax tree. It knows line, block and Javadoc comments, and skips the Groovy strings ({@code '...'},
 * {@code "..."}, the triple quoted forms, {@code /.../} and {@code $/.../$}, with {@code ${...}} inside), so a
 * {@code "// x"} in a string is not taken for a comment. Lines are zero based.
 */
final class SourceComments {

    /** The three shapes of comment. */
    enum Kind { LINE, BLOCK, JAVADOC }

    /**
     * One comment.
     *
     * @param start the offset of its first marker
     * @param end the offset just past it (a line comment ends before the line break)
     * @param line its first line
     * @param endLine its last line
     * @param ownLine whether no code precedes it on its first line
     */
    record Comment(Kind kind, int start, int end, int line, int endLine, boolean ownLine) {
    }

    /** Words after which a {@code /} starts a slashy string rather than a division. */
    private static final Set<String> BEFORE_REGEX = Set.of("return", "case", "assert", "in", "throw", "else",
            "yield", "and", "or", "not", "def", "var");

    private final String text;
    private final List<Comment> comments = new ArrayList<>();
    private final BitSet code = new BitSet();
    private final BitSet commented = new BitSet();
    private final String[] content;
    private final Comment[] owner;
    private int line;

    private SourceComments(String text) {
        this.text = text;
        int start = 0;
        if (text.startsWith("#!")) {
            code.set(0);
            start = text.indexOf('\n') < 0 ? text.length() : text.indexOf('\n');
        }
        scan(start, false);
        int lines = line + 1;
        content = new String[lines];
        owner = new Comment[lines];
        for (Comment comment : comments) {
            commented.set(comment.line(), comment.endLine() + 1);
            String[] parts = text.substring(comment.start(), comment.end()).split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                int at = comment.line() + i;
                if (owner[at] == null) {
                    owner[at] = comment;
                    content[at] = strip(comment.kind(), parts[i], i == 0, i == parts.length - 1);
                }
            }
        }
    }

    /** The comments of {@code text}. */
    static SourceComments of(String text) {
        return new SourceComments(text == null ? "" : text);
    }

    List<Comment> comments() {
        return comments;
    }

    /** The number of lines the lexer saw. */
    int lineCount() {
        return content.length;
    }

    /** Whether a line holds code (a string's text counts; a trailing comment does not make it a comment line). */
    boolean isCode(int at) {
        return code.get(at);
    }

    /** Whether a line holds a comment and no code. */
    boolean isCommentOnly(int at) {
        return commented.get(at) && !code.get(at);
    }

    /** The comment on a comment line, or {@code null}. */
    Comment commentAt(int at) {
        return at >= 0 && at < owner.length && isCommentOnly(at) ? owner[at] : null;
    }

    /** The first comment on any part of a line (a trailing one included), or {@code null}. */
    Comment commentOn(int at) {
        return at >= 0 && at < owner.length ? owner[at] : null;
    }

    /** A comment line's text without its markers ({@code //}, {@code /*}, a leading {@code *}), trimmed. */
    String contentAt(int at) {
        return commentAt(at) == null ? null : content[at];
    }

    /** The raw source text of a comment. */
    String raw(Comment comment) {
        return text.substring(comment.start(), comment.end());
    }

    static String strip(Kind kind, String part, boolean first, boolean last) {
        String value = part.endsWith("\r") ? part.substring(0, part.length() - 1) : part;
        if (kind == Kind.LINE) {
            return value.substring(value.indexOf("//") + 2).strip();
        }
        if (first) {
            value = value.substring(value.indexOf("/*") + (kind == Kind.JAVADOC ? 3 : 2));
        }
        if (last && value.endsWith("*/")) {
            value = value.substring(0, value.length() - 2);
        }
        value = value.strip();
        if (!first && value.startsWith("*")) {
            value = value.substring(1).strip();
        }
        return value;
    }

    /**
     * Lexes from {@code index}; inside a {@code ${...}} ({@code nested}) it stops just past the closing brace.
     * Returns the offset where it stopped.
     */
    private int scan(int index, boolean nested) {
        int i = index;
        int depth = 0;
        boolean regexAllowed = true;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            char next = i + 1 < length ? text.charAt(i + 1) : '\0';
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && next == '/') {
                i = lineComment(i);
            } else if (c == '/' && next == '*') {
                i = blockComment(i);
            } else {
                code.set(line);
                if (nested && c == '}' && depth == 0) {
                    return i + 1;
                }
                if (c == '\'' || c == '"') {
                    i = string(i, c);
                    regexAllowed = false;
                } else if (c == '$' && next == '/') {
                    i = quoted(i + 2, "/$", true, true);
                    regexAllowed = false;
                } else if (c == '/' && regexAllowed && next != '=' && !Character.isWhitespace(next)) {
                    i = quoted(i + 1, "/", true, false);
                    regexAllowed = false;
                } else if (Character.isJavaIdentifierStart(c)) {
                    int end = i + 1;
                    while (end < length && Character.isJavaIdentifierPart(text.charAt(end))) {
                        end++;
                    }
                    regexAllowed = BEFORE_REGEX.contains(text.substring(i, end));
                    i = end;
                } else if (Character.isDigit(c)) {
                    while (i < length && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_'
                            || text.charAt(i) == '.' && i + 1 < length && Character.isDigit(text.charAt(i + 1)))) {
                        i++;
                    }
                    regexAllowed = false;
                } else {
                    depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                    regexAllowed = c != ')' && c != ']' && c != '}';
                    i++;
                }
            }
        }
        return i;
    }

    private int lineComment(int start) {
        int end = text.indexOf('\n', start);
        end = end < 0 ? text.length() : end;
        if (end > start && text.charAt(end - 1) == '\r') {
            end--;
        }
        comments.add(new Comment(Kind.LINE, start, end, line, line, !code.get(line)));
        return end;
    }

    private int blockComment(int start) {
        int close = text.indexOf("*/", start + 2);
        int end = close < 0 ? text.length() : close + 2;
        boolean javadoc = text.startsWith("/**", start) && !text.startsWith("/**/", start);
        int first = line;
        boolean ownLine = !code.get(first);
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        comments.add(new Comment(javadoc ? Kind.JAVADOC : Kind.BLOCK, start, end, first, line, ownLine));
        return end;
    }

    /** Skips a quoted string starting at {@code start} (its quote), triple quoted or not. */
    private int string(int start, char quote) {
        String triple = String.valueOf(quote).repeat(3);
        boolean interpolated = quote == '"';
        if (text.startsWith(triple, start)) {
            return quoted(start + 3, triple, interpolated, false);
        }
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote || c == '\n') {
                return c == quote ? i + 1 : i;
            } else if (interpolated && c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                i = scan(i + 2, true);
            } else {
                i++;
            }
        }
        return i;
    }

    /**
     * Skips the body of a string that may span lines, from {@code start} to just past {@code close}, marking each
     * line it covers as code. {@code dollar} selects the dollar slashy escapes ({@code $$}, {@code $/}) over the
     * backslash.
     */
    private int quoted(int start, String close, boolean interpolated, boolean dollar) {
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (text.startsWith(close, i)) {
                return i + close.length();
            }
            if (c == '\n') {
                line++;
                code.set(line);
                i++;
            } else if (!dollar && c == '\\') {
                i += i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 1 : 2;
            } else if (dollar && c == '$' && i + 1 < text.length() && "$/".indexOf(text.charAt(i + 1)) >= 0) {
                i += 2;
            } else if (interpolated && c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                i = scan(i + 2, true);
            } else {
                i++;
            }
        }
        return i;
    }
}
