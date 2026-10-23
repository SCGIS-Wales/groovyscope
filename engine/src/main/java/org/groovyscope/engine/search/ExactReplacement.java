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
package org.groovyscope.engine.search;

import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;

/**
 * An editor's {@code str_replace} for one change of a text: {@code oldStr} occurs exactly once in the original, and
 * replacing it with {@code newStr} gives the new text.
 *
 * @param oldStr a part of the original text that occurs in it exactly once
 * @param newStr what {@code oldStr} becomes
 */
public record ExactReplacement(String oldStr, String newStr) {

    /**
     * The replacement that turns {@code source} into {@code text}, where the two differ only in
     * {@code [start, end)} of {@code source}: that span widened to whole lines, then by one line of context on each
     * side at a time until it occurs once in the original (the whole text at worst).
     */
    public static ExactReplacement of(String source, String text, int start, int end) {
        TextLines lines = new TextLines(source);
        int delta = text.length() - source.length();
        int from = lineStart(lines, lines.positionOf(start).line());
        int to = contentEnd(lines, lines.positionOf(end).line());
        while (!(from == 0 && to == source.length())) {
            String candidate = source.substring(from, to);
            if (!candidate.isEmpty() && occursOnce(source, candidate)) {
                break;
            }
            int above = lines.positionOf(from).line();
            if (above > 0) {
                from = lineStart(lines, above - 1);
            }
            int below = lines.positionOf(to).line();
            if (below + 1 < lines.lineCount()) {
                to = contentEnd(lines, below + 1);
            } else {
                to = source.length();
            }
        }
        return new ExactReplacement(source.substring(from, to), text.substring(from, to + delta));
    }

    /** Whether {@code part} occurs in {@code text} exactly once. */
    public static boolean occursOnce(String text, String part) {
        int first = text.indexOf(part);
        return first >= 0 && text.indexOf(part, first + 1) < 0;
    }

    private static int lineStart(TextLines lines, int line) {
        return lines.offsetOf(new Position(line, 0));
    }

    private static int contentEnd(TextLines lines, int line) {
        return lineStart(lines, line) + lines.line(line).length();
    }
}
