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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PackageNode;
import org.codehaus.groovy.ast.stmt.Statement;
import org.groovyscope.engine.index.Declarations;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;

/**
 * Edits one declaration of a document as an object, not as text: the whole declaration (with the comments and
 * annotations directly above it, delimited as {@code readSymbol} delimits them), a method's body, a new member of a
 * class, or a new import. Pure text work on a parsed tree: nothing is read or written, and the answer carries the
 * whole new text plus an exact replacement ({@code oldStr} occurs once in the original) for an editor's
 * {@code str_replace}.
 *
 * <p>The given text is dedented and indented again to where it goes; line endings follow the file, and the file's
 * trailing newline (or its absence) is kept.
 */
public final class SymbolEditor {

    /** Most declarations named when a symbol is not found or is ambiguous. */
    public static final int MAX_LISTED = 20;
    /** One indentation level when the file shows none. */
    static final String DEFAULT_UNIT = "    ";

    private static final Pattern IMPORT_NAME = Pattern.compile(
            "[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.([\\p{L}_$][\\p{L}\\p{N}_$]*|\\*))*( as [\\p{L}_$][\\p{L}\\p{N}_$]*)?");
    private static final Pattern AFTER_DECLARATION = Pattern.compile(";?\\s*(//.*)?");

    /** What an edit does. */
    public enum Operation {
        /** The whole declaration, its comments and annotations included, is replaced. */
        REPLACE,
        /** The statements of a method's or constructor's body are replaced. */
        REPLACE_BODY,
        /** New lines go before the declaration and its comments. */
        INSERT_BEFORE,
        /** New lines go after the declaration. */
        INSERT_AFTER,
        /** The declaration and its comments are removed. */
        DELETE,
        /** A new member goes into a class. */
        ADD_MEMBER,
        /** A new import goes with the others. */
        ADD_IMPORT;

        /** The operation as the requests spell it ({@code replace_body}, ...). */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The operation a request names, or empty for an unknown one. */
        public static Optional<Operation> of(String label) {
            for (Operation operation : values()) {
                if (operation.label().equals(label)) {
                    return Optional.of(operation);
                }
            }
            return Optional.empty();
        }

        /** The labels of every operation, for an error message. */
        public static String labels() {
            return String.join(", ", Arrays.stream(values()).map(Operation::label).toList());
        }
    }

    /**
     * One edit.
     *
     * @param symbol {@code method}, {@code Class.method}, {@code Outer.Inner.method}, {@code Class} or
     *               {@code Class.<init>}; ignored by {@link Operation#ADD_IMPORT}
     * @param operation what to do
     * @param text the new code (unused by {@link Operation#DELETE})
     * @param position for {@link Operation#ADD_MEMBER}: {@code start}, {@code end} (the default) or
     *                 {@code after:<member>}
     * @param line 1-based line that picks one of several declarations of the symbol, or {@code null}
     */
    public record Request(String symbol, Operation operation, String text, String position, Integer line) {
    }

    /**
     * The edited document.
     *
     * @param text the whole new text
     * @param oldStr a part of the original text that occurs in it exactly once
     * @param newStr what {@code oldStr} becomes: replacing one with the other gives {@code text}
     * @param startLine first line (1-based) of the original that the edit replaced, or next to which it inserted
     * @param endLine last such line (1-based, inclusive)
     * @param symbol the declaration edited, by its full name ({@code Verbose.countWords}); the import line for
     *               {@link Operation#ADD_IMPORT}
     * @param insertedFrom where the inserted or replacing text starts in {@code text} (a character offset)
     * @param insertedTo where it ends in {@code text} (exclusive; equal to {@code insertedFrom} for a deletion)
     */
    public record Result(String text, String oldStr, String newStr, int startLine, int endLine, String symbol,
            int insertedFrom, int insertedTo) {
    }

    /** A replacement of {@code [start, end)} of the original by {@code replacement}, and the lines it reports. */
    private record Change(int start, int end, String replacement, int startLine, int endLine) {
    }

    private final String source;
    private final TextLines lines;
    private final String eol;
    private final int lineCount;
    private final String unit;

    private SymbolEditor(String source) {
        this.source = source;
        this.lines = new TextLines(source);
        this.eol = source.contains("\r\n") ? "\r\n" : "\n";
        this.lineCount = source.isEmpty() ? 0 : source.endsWith("\n") ? lines.lineCount() - 1 : lines.lineCount();
        this.unit = indentUnit(lines, lineCount);
    }

    /**
     * Applies {@code request} to {@code source}, whose parsed tree is {@code module}.
     *
     * @throws IllegalArgumentException with the reason, when the symbol is not found or ambiguous, the operation
     *                                  does not apply to it, or its text is missing
     */
    public static Result edit(String source, ModuleNode module, Request request) {
        if (request.operation() == null) {
            throw new IllegalArgumentException("operation must be one of " + Operation.labels());
        }
        if (request.operation() != Operation.DELETE && request.text() == null) {
            throw new IllegalArgumentException(request.operation().label() + " needs a text");
        }
        if (request.operation() != Operation.DELETE && request.operation() != Operation.REPLACE_BODY
                && request.text().isBlank()) {
            throw new IllegalArgumentException(request.operation().label() + " needs a text that is not blank"
                    + (request.operation() == Operation.REPLACE ? "; use delete to remove a declaration" : ""));
        }
        return new SymbolEditor(source).apply(module, request);
    }

    private Result apply(ModuleNode module, Request request) {
        if (request.operation() == Operation.ADD_IMPORT) {
            return addImport(module, request.text());
        }
        List<Declarations.Located> all = Declarations.located(module);
        Declarations.Located target = resolve(all, packagePrefix(module), request.symbol(), request.line());
        String name = target.declaration().name();
        Change change = switch (request.operation()) {
            case REPLACE -> replace(target, request.text());
            case REPLACE_BODY -> replaceBody(target, request.text());
            case INSERT_BEFORE -> insertBefore(target, request.text());
            case INSERT_AFTER -> insertAfter(target, request.text());
            case DELETE -> delete(target);
            case ADD_MEMBER -> addMember(all, target, request.text(), request.position());
            case ADD_IMPORT -> throw new IllegalStateException("handled above");
        };
        return result(change, name);
    }

    /** The package of the module with a trailing dot ({@code demo.}), or empty. */
    private static String packagePrefix(ModuleNode module) {
        String name = module == null ? null : module.getPackageName();
        return name == null ? "" : name.endsWith(".") ? name : name + ".";
    }

    /**
     * The one declaration {@code symbol} names: by its full name ({@code Outer.Inner.member}) or a dotted suffix of
     * it, the package optional; {@code line} picks among several the one whose range contains it.
     */
    static Declarations.Located resolve(List<Declarations.Located> all, String packagePrefix, String symbol,
            Integer line) {
        String wanted = symbol == null ? "" : symbol.strip().replace('#', '.');
        if (!packagePrefix.isEmpty() && wanted.startsWith(packagePrefix)) {
            wanted = wanted.substring(packagePrefix.length());
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("needs a symbol (method, Class.method, Class or Class.<init>)");
        }
        List<Declarations.Located> matches = new ArrayList<>();
        for (Declarations.Located located : all) {
            String name = located.declaration().name();
            if (name.equals(wanted) || name.endsWith("." + wanted)) {
                matches.add(located);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("symbol " + wanted + " not found; " + (all.isEmpty()
                    ? "the document declares nothing" : "declared here: " + listed(all)));
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (line != null) {
            Declarations.Located best = null;
            for (Declarations.Located located : matches) {
                Declarations.Declaration declaration = located.declaration();
                if (declaration.line() <= line && line <= declaration.endLine() && (best == null
                        || declaration.endLine() - declaration.line()
                        < best.declaration().endLine() - best.declaration().line())) {
                    best = located;
                }
            }
            if (best != null) {
                return best;
            }
            throw new IllegalArgumentException("no declaration of " + wanted + " spans line " + line + "; it is "
                    + "declared at: " + listed(matches));
        }
        throw new IllegalArgumentException("symbol " + wanted + " is ambiguous; pass line to pick one of: "
                + listed(matches));
    }

    /** {@code Name (line 3), ...} for at most {@value #MAX_LISTED} declarations. */
    private static String listed(List<Declarations.Located> declarations) {
        List<String> names = new ArrayList<>();
        for (Declarations.Located located : declarations) {
            if (names.size() >= MAX_LISTED) {
                names.add("and " + (declarations.size() - MAX_LISTED) + " more");
                break;
            }
            Declarations.Declaration declaration = located.declaration();
            String lineText = declaration.line() == declaration.endLine() ? "line " + declaration.line()
                    : "lines " + declaration.line() + "-" + declaration.endLine();
            names.add(declaration.name() + " (" + lineText + ")");
        }
        return String.join(", ", names);
    }

    private Change replace(Declarations.Located target, String text) {
        int start = spanStart(target);
        int end = target.declaration().endLine() - 1;
        return replaceLines(start, end, reindent(text, indentOf(target)), start + 1, end + 1);
    }

    private Change insertBefore(Declarations.Located target, String text) {
        int start = spanStart(target);
        List<String> block = reindent(text, indentOf(target));
        block.add("");
        return insertLines(start, block, start + 1, start + 1);
    }

    private Change insertAfter(Declarations.Located target, String text) {
        requireAlone(target);
        int end = target.declaration().endLine() - 1;
        List<String> block = new ArrayList<>();
        block.add("");
        block.addAll(reindent(text, indentOf(target)));
        return insertLines(end + 1, block, end + 1, end + 1);
    }

    private Change delete(Declarations.Located target) {
        int start = spanStart(target);
        int end = target.declaration().endLine() - 1;
        if (end + 1 < lineCount && lines.line(end + 1).isBlank()) {
            end++;
        } else if (start > 0 && lines.line(start - 1).isBlank()) {
            start--;
        }
        return replaceLines(start, end, List.of(), start + 1, end + 1);
    }

    private Change replaceBody(Declarations.Located target, String text) {
        Declarations.Kind kind = target.declaration().kind();
        if (kind != Declarations.Kind.METHOD && kind != Declarations.Kind.CONSTRUCTOR
                || !(target.node() instanceof MethodNode method)) {
            throw new IllegalArgumentException("replace_body needs a method or a constructor; "
                    + target.declaration().name() + " is a " + kind.label());
        }
        Statement code = method.getCode();
        int open = -1;
        int close = -1;
        if (code != null && code.getLineNumber() >= 1 && code.getLastLineNumber() >= 1) {
            open = offset(code.getLineNumber(), code.getColumnNumber());
            close = offset(code.getLastLineNumber(), code.getLastColumnNumber() - 1);
        }
        if (open < 0 || open >= source.length() || source.charAt(open) != '{' || close <= open
                || close >= source.length() || source.charAt(close) != '}') {
            throw new IllegalArgumentException(target.declaration().name() + " has no body to replace");
        }
        String indent = indentOf(target);
        Position closing = lines.positionOf(close);
        String closeLine = lines.line(closing.line());
        String closingIndent = closeLine.substring(0, closing.column()).isBlank()
                ? closeLine.substring(0, closing.column()) : indent;
        List<String> body = reindent(text, indent + unit);
        String replacement = body.isEmpty() ? eol + closingIndent
                : eol + String.join(eol, body) + eol + closingIndent;
        return new Change(open + 1, close, replacement, lines.positionOf(open).line() + 1, closing.line() + 1);
    }

    private Change addMember(List<Declarations.Located> all, Declarations.Located target, String text,
            String position) {
        if (target.declaration().kind() != Declarations.Kind.CLASS || !(target.node() instanceof ClassNode type)) {
            throw new IllegalArgumentException("add_member needs a class; " + target.declaration().name() + " is a "
                    + target.declaration().kind().label());
        }
        if (type.isScript()) {
            throw new IllegalArgumentException(target.declaration().name() + " is a script, not a class body; use "
                    + "insert_before or insert_after on one of its declarations");
        }
        int from = offset(type.getLineNumber(), type.getColumnNumber());
        int to = Math.min(source.length(), offset(type.getLastLineNumber(), type.getLastColumnNumber()));
        int open = openBrace(source, from, to);
        int close = to > 0 ? source.lastIndexOf('}', to - 1) : -1;
        if (open < 0 || close <= open) {
            throw new IllegalArgumentException("the body of " + target.declaration().name() + " was not found");
        }
        String indent = indentOf(target);
        List<String> block = reindent(text, indent + unit);
        int openLine = lines.positionOf(open).line();
        int closeLine = lines.positionOf(close).line();
        String where = position == null || position.isBlank() ? "end" : position.strip();
        if (openLine == closeLine) {
            if (!source.substring(open + 1, close).isBlank()) {
                throw new IllegalArgumentException("the body of " + target.declaration().name()
                        + " shares one line with its members; replace the class instead");
            }
            return new Change(open + 1, close, eol + String.join(eol, block) + eol + indent, openLine + 1,
                    closeLine + 1);
        }
        if (where.equals("start")) {
            int next = openLine + 1;
            List<String> inserted = new ArrayList<>();
            if (next == closeLine) {
                inserted.addAll(block);
            } else if (lines.line(next).isBlank()) {
                inserted.add("");
                inserted.addAll(block);
            } else {
                inserted.addAll(block);
                inserted.add("");
            }
            return insertLines(next, inserted, openLine + 1, openLine + 1);
        }
        if (where.equals("end")) {
            int previous = closeLine - 1;
            List<String> inserted = new ArrayList<>();
            if (previous != openLine && !lines.line(previous).isBlank()) {
                inserted.add("");
            }
            inserted.addAll(block);
            return insertLines(closeLine, inserted, closeLine + 1, closeLine + 1);
        }
        if (where.startsWith("after:")) {
            Declarations.Located member = member(all, target, where.substring("after:".length()).strip());
            requireAlone(member);
            int end = member.declaration().endLine() - 1;
            List<String> inserted = new ArrayList<>();
            inserted.add("");
            inserted.addAll(block);
            if (end + 1 < closeLine && !lines.line(end + 1).isBlank()) {
                inserted.add("");
            }
            return insertLines(end + 1, inserted, end + 1, end + 1);
        }
        throw new IllegalArgumentException("position must be start, end or after:<member>, not " + where);
    }

    /** The one direct member of {@code type} named {@code name} (simple, or prefixed with the class's name). */
    private static Declarations.Located member(List<Declarations.Located> all, Declarations.Located type,
            String name) {
        String className = type.declaration().name();
        String simple = name.startsWith(className + ".") ? name.substring(className.length() + 1) : name;
        List<Declarations.Located> matches = new ArrayList<>();
        List<Declarations.Located> members = new ArrayList<>();
        for (Declarations.Located located : all) {
            String full = located.declaration().name();
            if (full.startsWith(className + ".") && full.indexOf('.', className.length() + 1) < 0) {
                members.add(located);
                if (full.equals(className + "." + simple)) {
                    matches.add(located);
                }
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("member " + simple + " not found in " + className + "; "
                    + (members.isEmpty() ? "it has none" : "its members: " + listed(members)));
        }
        throw new IllegalArgumentException("member " + simple + " of " + className + " is ambiguous ("
                + listed(matches) + "); use insert_after on that symbol with its line instead");
    }

    private Result addImport(ModuleNode module, String text) {
        String statement = importStatement(text);
        boolean isStatic = statement.startsWith("import static ");
        String key = importKey(statement);
        List<int[]> existing = new ArrayList<>(); // {zero-based line, 1 when static}
        if (module != null) {
            List<ImportNode> imports = new ArrayList<>(module.getImports());
            imports.addAll(module.getStarImports());
            imports.addAll(module.getStaticImports().values());
            imports.addAll(module.getStaticStarImports().values());
            for (ImportNode node : imports) {
                int line = node.getLineNumber() - 1;
                if (line < 0 || line >= lineCount) {
                    continue;
                }
                String present = normalise(lines.line(line));
                if (present.equals(statement)) {
                    throw new IllegalArgumentException("already imported: " + statement + " (line " + (line + 1) + ")");
                }
                if (existing.stream().noneMatch(entry -> entry[0] == line)) {
                    existing.add(new int[] {line, present.startsWith("import static ") ? 1 : 0});
                }
            }
        }
        existing.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> same = existing.stream().filter(entry -> (entry[1] == 1) == isStatic).toList();
        Change change;
        if (!same.isEmpty()) {
            int before = -1;
            for (int[] entry : same) {
                if (importKey(normalise(lines.line(entry[0]))).compareTo(key) > 0) {
                    before = entry[0];
                    break;
                }
            }
            if (before >= 0) {
                change = insertLines(before, new ArrayList<>(List.of(statement)), before + 1, before + 1);
            } else {
                int after = same.get(same.size() - 1)[0];
                change = insertLines(after + 1, new ArrayList<>(List.of(statement)), after + 1, after + 1);
            }
        } else if (!existing.isEmpty()) {
            if (isStatic) {
                int after = existing.get(existing.size() - 1)[0];
                change = insertLines(after + 1, new ArrayList<>(List.of("", statement)), after + 1, after + 1);
            } else {
                int before = existing.get(0)[0];
                change = insertLines(before, new ArrayList<>(List.of(statement, "")), before + 1, before + 1);
            }
        } else {
            PackageNode pack = module == null ? null : module.getPackage();
            if (pack != null && pack.getLineNumber() >= 1 && pack.getLineNumber() - 1 < lineCount) {
                int line = pack.getLineNumber() - 1;
                List<String> block = new ArrayList<>(List.of("", statement));
                if (line + 1 < lineCount && !lines.line(line + 1).isBlank()) {
                    block.add("");
                }
                change = insertLines(line + 1, block, line + 1, line + 1);
            } else {
                int first = firstCodeLine();
                List<String> block = new ArrayList<>(List.of(statement));
                if (first < lineCount) {
                    block.add("");
                }
                int anchor = Math.min(first, Math.max(0, lineCount - 1)) + 1;
                change = insertLines(first, block, anchor, anchor);
            }
        }
        return result(change, statement);
    }

    /** {@code import a.b.C} (or {@code import static ...}) from what a request gives, checked. */
    static String importStatement(String text) {
        String raw = text.strip().replaceAll("\\s+", " ");
        if (raw.endsWith(";")) {
            raw = raw.substring(0, raw.length() - 1).strip();
        }
        if (raw.startsWith("import ")) {
            raw = raw.substring("import ".length()).strip();
        }
        boolean isStatic = raw.startsWith("static ");
        String name = isStatic ? raw.substring("static ".length()).strip() : raw;
        if (!IMPORT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not an import: " + text.strip());
        }
        return "import " + (isStatic ? "static " : "") + name;
    }

    /** An import line with its spacing and semicolon normalised, to compare with another. */
    private static String normalise(String line) {
        String text = line.strip().replaceAll("\\s+", " ");
        return text.endsWith(";") ? text.substring(0, text.length() - 1).strip() : text;
    }

    /** What imports sort by: the imported name. */
    private static String importKey(String statement) {
        String rest = statement.substring("import ".length());
        return rest.startsWith("static ") ? rest.substring("static ".length()) : rest;
    }

    /** The first line that is not blank, a comment or a shebang ({@link #lineCount} when there is none). */
    private int firstCodeLine() {
        boolean inComment = false;
        for (int i = 0; i < lineCount; i++) {
            String line = lines.line(i).strip();
            if (inComment) {
                inComment = !line.contains("*/");
                continue;
            }
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#!")) {
                continue;
            }
            if (line.startsWith("/*")) {
                inComment = !line.contains("*/");
                continue;
            }
            return i;
        }
        return lineCount;
    }

    /** The first line (zero-based) of the declaration with its comments and annotations; it must stand alone. */
    private int spanStart(Declarations.Located target) {
        requireAlone(target);
        return SymbolReader.leadingStart(lines, target.declaration().line() - 1);
    }

    /**
     * Refuses a declaration that shares its first or last line with other code ({@code class A { void a() {} }}):
     * its lines cannot be replaced, moved or deleted without taking that code along.
     */
    private void requireAlone(Declarations.Located target) {
        AnnotatedNode node = target.node();
        if (node instanceof ClassNode type && type.isScript()) {
            return;
        }
        Declarations.Declaration declaration = target.declaration();
        int column = node.getLineNumber() == declaration.line() ? node.getColumnNumber() : Integer.MAX_VALUE;
        for (AnnotationNode annotation : node.getAnnotations()) {
            if (annotation.getLineNumber() == declaration.line()) {
                column = Math.min(column, annotation.getColumnNumber());
            }
        }
        String first = lines.line(declaration.line() - 1);
        String before = column == Integer.MAX_VALUE ? "" : first.substring(0, Math.max(0, Math.min(first.length(),
                column - 1)));
        String last = lines.line(declaration.endLine() - 1);
        String after = node.getLastLineNumber() == declaration.endLine() && node.getLastColumnNumber() >= 1
                ? last.substring(Math.min(last.length(), node.getLastColumnNumber() - 1)).strip() : "";
        if (!before.isBlank() || !AFTER_DECLARATION.matcher(after).matches()) {
            throw new IllegalArgumentException(declaration.name() + " shares a line with other code (line "
                    + (before.isBlank() ? declaration.endLine() : declaration.line())
                    + "); edit the declaration that holds it instead");
        }
    }

    /** The leading whitespace of the declaration's first line. */
    private String indentOf(Declarations.Located target) {
        return leading(lines.line(target.declaration().line() - 1));
    }

    /** The offset of a 1-based line and column. */
    private int offset(int line, int column) {
        return lines.offsetOf(new Position(line - 1, column - 1));
    }

    private int lineStart(int line) {
        return lines.offsetOf(new Position(line, 0));
    }

    private int contentEnd(int line) {
        return lineStart(line) + lines.line(line).length();
    }

    /** Whether a line break follows {@code line}. */
    private boolean terminated(int line) {
        return line + 1 < lines.lineCount();
    }

    /** {@code block} inserted as whole lines before {@code line} (zero-based; {@link #lineCount} appends). */
    private Change insertLines(int line, List<String> block, int startLine, int endLine) {
        String joined = String.join(eol, block);
        if (line < lineCount) {
            int at = lineStart(line);
            return new Change(at, at, joined + eol, startLine, endLine);
        }
        String replacement = source.isEmpty() ? joined + eol : source.endsWith("\n") ? joined + eol : eol + joined;
        return new Change(source.length(), source.length(), replacement, startLine, endLine);
    }

    /** Lines {@code first} to {@code last} (zero-based, inclusive) replaced by {@code block}, or removed when empty. */
    private Change replaceLines(int first, int last, List<String> block, int startLine, int endLine) {
        int start = lineStart(first);
        int end = terminated(last) ? lineStart(last + 1) : source.length();
        if (block.isEmpty()) {
            if (!terminated(last) && first > 0) {
                start = contentEnd(first - 1); // the last line goes with the break before it
            }
            return new Change(start, end, "", startLine, endLine);
        }
        return new Change(start, end, String.join(eol, block) + (terminated(last) ? eol : ""), startLine, endLine);
    }

    /**
     * The new text, and the replacement for {@code str_replace}: the changed span widened to whole lines, then by
     * one line of context on each side at a time until it occurs once in the original.
     */
    private Result result(Change change, String symbol) {
        String text = source.substring(0, change.start()) + change.replacement() + source.substring(change.end());
        int delta = text.length() - source.length();
        int from = lineStart(lines.positionOf(change.start()).line());
        int to = contentEnd(lines.positionOf(change.end()).line());
        while (!(from == 0 && to == source.length())) {
            String candidate = source.substring(from, to);
            if (!candidate.isEmpty() && occursOnce(source, candidate)) {
                break;
            }
            int above = lines.positionOf(from).line();
            if (above > 0) {
                from = lineStart(above - 1);
            }
            int below = lines.positionOf(to).line();
            if (below + 1 < lines.lineCount()) {
                to = contentEnd(below + 1);
            } else {
                to = source.length();
            }
        }
        return new Result(text, source.substring(from, to), text.substring(from, to + delta), change.startLine(),
                change.endLine(), symbol, change.start(), change.start() + change.replacement().length());
    }

    static boolean occursOnce(String text, String part) {
        int first = text.indexOf(part);
        return first >= 0 && text.indexOf(part, first + 1) < 0;
    }

    /**
     * {@code text} as lines without the blank lines around it, dedented (the whitespace every line starts with
     * removed) and indented with {@code indent}; blank lines stay empty.
     */
    static List<String> reindent(String text, String indent) {
        List<String> raw = new ArrayList<>(Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", -1)));
        while (!raw.isEmpty() && raw.get(0).isBlank()) {
            raw.remove(0);
        }
        while (!raw.isEmpty() && raw.get(raw.size() - 1).isBlank()) {
            raw.remove(raw.size() - 1);
        }
        String common = null;
        for (String line : raw) {
            if (!line.isBlank()) {
                String whitespace = leading(line);
                common = common == null ? whitespace : commonPrefix(common, whitespace);
            }
        }
        List<String> indented = new ArrayList<>();
        for (String line : raw) {
            indented.add(line.isBlank() ? "" : indent + line.substring(common == null ? 0 : common.length()));
        }
        return indented;
    }

    /**
     * One indentation level of the file: a tab when lines are indented with tabs, else the smallest indentation a
     * line has ({@value #DEFAULT_UNIT} spaces when none has any, or an unlikely width). Javadoc continuation lines
     * are left out.
     */
    static String indentUnit(TextLines lines, int count) {
        int smallest = 0;
        for (int i = 0; i < count; i++) {
            String line = lines.line(i);
            String whitespace = leading(line);
            if (line.isBlank() || whitespace.isEmpty() || line.strip().startsWith("*")) {
                continue;
            }
            if (whitespace.charAt(0) == '\t') {
                return "\t";
            }
            if (smallest == 0 || whitespace.length() < smallest) {
                smallest = whitespace.length();
            }
        }
        return smallest >= 2 && smallest <= 8 ? " ".repeat(smallest) : DEFAULT_UNIT;
    }

    /**
     * The offset of the first opening brace in {@code [from, to)} outside parentheses, brackets, strings and comments:
     * the brace that opens a class body after its annotations, name, generics and supertypes.
     */
    static int openBrace(String text, int from, int to) {
        int depth = 0;
        for (int i = Math.max(0, from); i < to; i++) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < to && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? to : end;
            } else if (c == '/' && i + 1 < to && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? to : end + 1;
            } else if (c == '\'' || c == '"') {
                i = stringEnd(text, i, to);
            } else if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == '{' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The offset of the quote closing the string that opens at {@code start} ({@code to} when it is not closed). */
    private static int stringEnd(String text, int start, int to) {
        char quote = text.charAt(start);
        String triple = String.valueOf(quote).repeat(3);
        if (text.startsWith(triple, start)) {
            int end = text.indexOf(triple, start + 3);
            return end < 0 ? to : end + 2;
        }
        for (int i = start + 1; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote || c == '\n') {
                return i;
            }
        }
        return to;
    }

    private static String leading(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static String commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }
}
