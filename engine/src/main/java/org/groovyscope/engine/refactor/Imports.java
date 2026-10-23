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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PackageNode;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;

/**
 * New import lines for a document, sorted in among the imports of their kind (static or not) as
 * {@code groovyscope/edit}'s {@code add_import} places one; after the package, or before the code, when there are
 * none.
 */
final class Imports {

    private Imports() {
    }

    /** One import line: where it is and whether it is static. */
    private record Line(int line, boolean isStatic, String key) {
    }

    /**
     * Adds the {@code statements} ({@code import a.B}, {@code import static a.B.c}) that {@code module} lacks to
     * {@code builder}; nothing when it lacks none.
     */
    static void add(Changes.Builder builder, String uri, String source, ModuleNode module,
            Collection<String> statements) {
        TreeSet<String> missing = new TreeSet<>(statements);
        TextLines lines = new TextLines(source);
        int count = source.endsWith("\n") ? lines.lineCount() - 1 : lines.lineCount();
        List<Line> existing = new ArrayList<>();
        if (module != null) {
            List<ImportNode> imports = new ArrayList<>(module.getImports());
            imports.addAll(module.getStarImports());
            imports.addAll(module.getStaticImports().values());
            imports.addAll(module.getStaticStarImports().values());
            for (ImportNode node : imports) {
                int line = node.getLineNumber() - 1;
                if (line >= 0 && line < count && existing.stream().noneMatch(known -> known.line() == line)) {
                    String text = normalise(lines.line(line));
                    missing.remove(text);
                    existing.add(new Line(line, text.startsWith("import static "), key(text)));
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        existing.sort((a, b) -> Integer.compare(a.line(), b.line()));
        if (existing.isEmpty()) {
            insertAlone(builder, uri, source, lines, count, module, String.join(eol, missing), eol);
            return;
        }
        Map<Integer, List<String>> before = new TreeMap<>();
        Map<Integer, List<String>> after = new TreeMap<>();
        for (String statement : missing) {
            boolean isStatic = statement.startsWith("import static ");
            List<Line> same = existing.stream().filter(line -> line.isStatic() == isStatic).toList();
            if (same.isEmpty()) {
                if (isStatic) {
                    after.computeIfAbsent(existing.get(existing.size() - 1).line(), k -> new ArrayList<>())
                            .add(statement);
                } else {
                    before.computeIfAbsent(existing.get(0).line(), k -> new ArrayList<>()).add(statement);
                }
                continue;
            }
            Line next = same.stream().filter(line -> line.key().compareTo(key(statement)) > 0).findFirst()
                    .orElse(null);
            if (next != null) {
                before.computeIfAbsent(next.line(), k -> new ArrayList<>()).add(statement);
            } else {
                after.computeIfAbsent(same.get(same.size() - 1).line(), k -> new ArrayList<>()).add(statement);
            }
        }
        for (Map.Entry<Integer, List<String>> entry : before.entrySet()) {
            builder.insert(uri, source, lines.offsetOf(new Position(entry.getKey(), 0)),
                    String.join(eol, entry.getValue()) + eol);
        }
        for (Map.Entry<Integer, List<String>> entry : after.entrySet()) {
            builder.insert(uri, source, contentEnd(lines, entry.getKey()), eol + String.join(eol, entry.getValue()));
        }
    }

    /** The imports of a document that has none: after its package, else before its first line of code. */
    private static void insertAlone(Changes.Builder builder, String uri, String source, TextLines lines, int count,
            ModuleNode module, String block, String eol) {
        PackageNode pack = module == null ? null : module.getPackage();
        if (pack != null && pack.getLineNumber() >= 1 && pack.getLineNumber() - 1 < count) {
            int line = pack.getLineNumber() - 1;
            boolean blankAfter = line + 1 >= count || lines.line(line + 1).isBlank();
            builder.insert(uri, source, contentEnd(lines, line), eol + eol + block + (blankAfter ? "" : eol));
            return;
        }
        int first = 0;
        while (first < count && SourceText.blankOrComment(lines.line(first))) {
            first++;
        }
        builder.insert(uri, source, lines.offsetOf(new Position(first, 0)), block + eol + eol);
    }

    /** What imports sort by: the imported name. */
    private static String key(String statement) {
        String rest = statement.substring("import ".length());
        return rest.startsWith("static ") ? rest.substring("static ".length()) : rest;
    }

    /** An import line with its spacing and semicolon normalised. */
    private static String normalise(String line) {
        String text = line.strip().replaceAll("\\s+", " ");
        return text.endsWith(";") ? text.substring(0, text.length() - 1).strip() : text;
    }

    private static int contentEnd(TextLines lines, int line) {
        return lines.offsetOf(new Position(line, 0)) + lines.line(line).length();
    }
}
