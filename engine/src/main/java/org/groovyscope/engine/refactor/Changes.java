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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.search.ExactReplacement;

/**
 * The edits a refactoring computed, nothing written: per document the LSP text edits (the shape
 * {@code groovyscope/rename} answers) and one exact replacement for an editor's {@code str_replace}.
 *
 * @param edits per document URI, the text edits in document order
 * @param files per changed document, the replacement and the whole new text
 */
public record Changes(Map<String, List<TextEdit>> edits, List<FileChange> files) {

    public Changes {
        edits = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(edits));
        files = List.copyOf(files);
    }

    /** No edits. */
    public static Changes none() {
        return new Changes(Map.of(), List.of());
    }

    /**
     * One changed document.
     *
     * @param uri the document
     * @param oldStr a part of its current text that occurs in it exactly once
     * @param newStr what {@code oldStr} becomes
     * @param text the whole new text
     */
    public record FileChange(String uri, String oldStr, String newStr, String text) {
    }

    /** A replacement of {@code [start, end)} of one document's text by {@code text}. */
    record Span(int start, int end, String text) {
    }

    /** Collects spans per document; two spans of one document must not overlap. */
    static final class Builder {

        private final Map<String, String> sources = new TreeMap<>();
        private final Map<String, List<Span>> spans = new TreeMap<>();

        /** Replaces {@code [start, end)} of the document {@code uri}, whose current text is {@code source}. */
        Builder replace(String uri, String source, int start, int end, String text) {
            sources.putIfAbsent(uri, source);
            spans.computeIfAbsent(uri, key -> new ArrayList<>()).add(new Span(start, end, text));
            return this;
        }

        /** Inserts {@code text} at {@code offset} of the document {@code uri}. */
        Builder insert(String uri, String source, int offset, String text) {
            return replace(uri, source, offset, offset, text);
        }

        /**
         * The edits and replacements.
         *
         * @throws IllegalStateException when two spans of one document overlap
         */
        Changes build() {
            Map<String, List<TextEdit>> edits = new LinkedHashMap<>();
            List<FileChange> files = new ArrayList<>();
            for (Map.Entry<String, List<Span>> entry : spans.entrySet()) {
                String uri = entry.getKey();
                String source = sources.get(uri);
                List<Span> sorted = new ArrayList<>(entry.getValue());
                sorted.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));
                for (int i = 1; i < sorted.size(); i++) {
                    if (sorted.get(i).start() < sorted.get(i - 1).end()) {
                        throw new IllegalStateException("two edits of " + SourceText.fileName(uri) + " overlap");
                    }
                }
                String text = apply(source, sorted);
                if (text.equals(source)) {
                    continue;
                }
                TextLines lines = new TextLines(source);
                List<TextEdit> list = new ArrayList<>();
                for (Span span : sorted) {
                    list.add(new TextEdit(SourceText.range(lines, span.start(), span.end()), span.text()));
                }
                edits.put(uri, list);
                int first = sorted.get(0).start();
                int last = sorted.stream().mapToInt(Span::end).max().orElse(first);
                ExactReplacement replacement = ExactReplacement.of(source, text, first, last);
                files.add(new FileChange(uri, replacement.oldStr(), replacement.newStr(), text));
            }
            return new Changes(edits, files);
        }
    }

    /** {@code source} with non-overlapping spans, sorted by start, applied. */
    static String apply(String source, List<Span> sorted) {
        StringBuilder text = new StringBuilder();
        int at = 0;
        for (Span span : sorted) {
            text.append(source, at, span.start()).append(span.text());
            at = span.end();
        }
        return text.append(source.substring(at)).toString();
    }

    /**
     * {@code source[start, end)} with the spans that lie inside it applied (offsets in the spans are of the whole
     * source); spans that are not wholly inside are ignored.
     */
    static String slice(String source, int start, int end, List<Span> spans) {
        List<Span> inside = new ArrayList<>();
        for (Span span : spans) {
            if (span.start() >= start && span.end() <= end) {
                inside.add(new Span(span.start() - start, span.end() - start, span.text()));
            }
        }
        inside.sort(Comparator.comparingInt(Span::start));
        return apply(source.substring(start, end), inside);
    }
}
