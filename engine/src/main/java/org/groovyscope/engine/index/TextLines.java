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
package org.groovyscope.engine.index;

import java.util.ArrayList;
import java.util.List;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;

/** Line-offset table for a document, to convert between positions and character offsets. */
public final class TextLines {

    private final String text;
    private final int[] lineStarts;

    public TextLines(String text) {
        this.text = text == null ? "" : text;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < this.text.length(); i++) {
            if (this.text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    public String text() {
        return text;
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public int offsetOf(Position position) {
        int line = Math.max(0, Math.min(position.line(), lineStarts.length - 1));
        int lineEnd = line + 1 < lineStarts.length ? lineStarts[line + 1] : text.length();
        return Math.min(lineStarts[line] + Math.max(0, position.column()), lineEnd);
    }

    public Position positionOf(int offset) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts[mid] <= clamped) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return new Position(low, clamped - lineStarts[low]);
    }

    public String slice(Range range) {
        int start = offsetOf(range.start());
        int end = offsetOf(range.end());
        return end <= start ? "" : text.substring(start, end);
    }

    /** The text of a line without its terminator. */
    public String line(int line) {
        if (line < 0 || line >= lineStarts.length) {
            return "";
        }
        int start = lineStarts[line];
        int end = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : text.length();
        if (end > start && text.charAt(end - 1) == '\r') {
            end--;
        }
        return text.substring(start, Math.max(start, end));
    }

    /** The range of the whole document. */
    public Range fullRange() {
        return new Range(new Position(0, 0), positionOf(text.length()));
    }
}
