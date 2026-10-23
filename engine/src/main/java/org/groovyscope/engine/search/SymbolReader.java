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

import java.util.ArrayList;
import java.util.List;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.groovyscope.engine.index.AstIndex;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.semantics.Symbols;

/**
 * The source of one declaration in a document, found by name: {@code greet}, {@code Greeter.greet},
 * {@code Greeter#greet} or a class name. The text spans the declaration's full range plus the Javadoc, comments and
 * annotations directly above it.
 */
public final class SymbolReader {

    /** Default cap on the returned text. */
    public static final int DEFAULT_MAX_CHARS = 12_000;

    static final int MAX_CANDIDATES = 20;

    /** A declaration that was not the one asked for, offered as a suggestion; {@code line} is zero-based. */
    public record Candidate(String name, String kind, String container, int line) {
    }

    /**
     * The answer. When {@code found}, the declaration's name, kind, container, zero-based inclusive line span and
     * text; otherwise {@code candidates} with similar names in the document.
     */
    public record Result(boolean found, String name, String kind, String container, int startLine, int endLine,
            String text, boolean truncated, List<Candidate> candidates) {

        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static Result notFound(List<Candidate> candidates) {
            return new Result(false, null, null, null, -1, -1, null, false, candidates);
        }
    }

    private SymbolReader() {
    }

    /**
     * Reads {@code symbol} from {@code uri}. {@code line} (zero-based, optional) picks among several matches the one
     * whose range contains it, else the one starting nearest to it.
     */
    public static Result read(AstIndex index, String uri, String symbol, Integer line, int maxChars) {
        String wanted = symbol == null ? "" : symbol.trim();
        List<NodeEntry> declarations = declarations(index, uri);
        List<NodeEntry> matches = matching(declarations, wanted);
        if (matches.isEmpty()) {
            return Result.notFound(candidates(declarations, simpleName(wanted)));
        }
        NodeEntry chosen = choose(matches, line);
        TextLines lines = index.lines(uri);
        int start = leadingStart(lines, chosen.range().start().line());
        int end = Math.max(start, chosen.range().end().line());
        int limit = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        for (int i = start; i <= end; i++) {
            String current = lines.line(i);
            int needed = current.length() + (i > start ? 1 : 0);
            if (text.length() + needed > limit) {
                if (i == start) {
                    text.append(current, 0, Math.min(current.length(), limit));
                }
                truncated = true;
                break;
            }
            if (i > start) {
                text.append('\n');
            }
            text.append(current);
        }
        ASTNode node = chosen.node();
        String kind = Symbols.kindOf(node).name().toLowerCase();
        return new Result(true, Symbols.nameOf(node), kind, Symbols.containerOf(node), start, end, text.toString(),
                truncated, List.of());
    }

    /** Classes, methods, constructors, fields and properties declared in the document, in source order. */
    static List<NodeEntry> declarations(AstIndex index, String uri) {
        List<NodeEntry> declarations = new ArrayList<>();
        for (NodeEntry entry : index.entries(uri)) {
            ASTNode node = entry.node();
            if (!entry.isDeclaration()) {
                continue;
            }
            if (node instanceof ClassNode || node instanceof MethodNode method && !method.isSynthetic()
                    && !method.isScriptBody() || node instanceof FieldNode || node instanceof PropertyNode) {
                declarations.add(entry);
            }
        }
        return declarations;
    }

    static List<NodeEntry> matching(List<NodeEntry> declarations, String wanted) {
        List<NodeEntry> matches = new ArrayList<>();
        if (wanted.isEmpty()) {
            return matches;
        }
        int hash = wanted.indexOf('#');
        if (hash < 0 && wanted.contains(".")) {
            for (NodeEntry entry : declarations) {
                if (entry.node() instanceof ClassNode classNode && classMatches(classNode.getName(), wanted)) {
                    matches.add(entry);
                }
            }
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        int split = hash >= 0 ? hash : wanted.lastIndexOf('.');
        String owner = split >= 0 ? wanted.substring(0, split) : null;
        String member = split >= 0 ? wanted.substring(split + 1) : wanted;
        for (NodeEntry entry : declarations) {
            ASTNode node = entry.node();
            if (!member.equals(Symbols.nameOf(node))) {
                continue;
            }
            if (owner == null) {
                matches.add(entry);
            } else if (!(node instanceof ClassNode)) {
                String container = Symbols.containerOf(node);
                if (container != null && classMatches(container, owner)) {
                    matches.add(entry);
                }
            } else if (((ClassNode) node).getOuterClass() != null
                    && classMatches(((ClassNode) node).getOuterClass().getName(), owner)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    /** True when a qualified class name (with {@code $} for nesting) is {@code wanted} by simple or qualified name. */
    static boolean classMatches(String qualified, String wanted) {
        String name = qualified.replace('$', '.');
        String target = wanted.replace('$', '.');
        return name.equals(target) || name.endsWith("." + target);
    }

    static NodeEntry choose(List<NodeEntry> matches, Integer line) {
        if (line == null || matches.size() == 1) {
            return matches.get(0);
        }
        NodeEntry best = null;
        for (NodeEntry entry : matches) {
            Range range = entry.range();
            if (range.start().line() <= line && line <= range.end().line()
                    && (best == null || range.extent() < best.range().extent())) {
                best = entry;
            }
        }
        if (best != null) {
            return best;
        }
        for (NodeEntry entry : matches) {
            if (best == null || Math.abs(entry.range().start().line() - line) < Math.abs(best.range().start().line() - line)) {
                best = entry;
            }
        }
        return best;
    }

    /**
     * The first line (zero-based) of the Javadoc, comments and annotations directly above {@code line}: where a
     * declaration's span begins for {@code readSymbol} and for {@link SymbolEditor}, which replaces, moves or deletes
     * that span. A blank line ends the run.
     */
    public static int leadingStart(TextLines lines, int line) {
        int start = line;
        while (start > 0) {
            String previous = lines.line(start - 1).trim();
            if (previous.startsWith("@") || previous.startsWith("//")) {
                start--;
            } else if (previous.endsWith("*/")) {
                int open = start - 1;
                while (open > 0 && !lines.line(open).contains("/*")) {
                    open--;
                }
                if (!lines.line(open).contains("/*")) {
                    break;
                }
                start = open;
            } else {
                break;
            }
        }
        return start;
    }

    static String simpleName(String wanted) {
        int split = Math.max(wanted.lastIndexOf('#'), wanted.lastIndexOf('.'));
        return split >= 0 ? wanted.substring(split + 1) : wanted;
    }

    static List<Candidate> candidates(List<NodeEntry> declarations, String simpleName) {
        String needle = simpleName.toLowerCase();
        List<Candidate> candidates = new ArrayList<>();
        for (NodeEntry entry : declarations) {
            String name = Symbols.nameOf(entry.node());
            if (name != null && name.toLowerCase().contains(needle)) {
                candidates.add(new Candidate(name, Symbols.kindOf(entry.node()).name().toLowerCase(),
                        Symbols.containerOf(entry.node()), entry.range().start().line()));
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        }
        return candidates;
    }
}
