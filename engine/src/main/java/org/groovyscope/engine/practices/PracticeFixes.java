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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.CodeAction;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;

/**
 * Quick fixes for CodeNarc findings that need the syntax tree (the purely textual ones stay in
 * {@code QualityServices}), and the plumbing that applies a set of fixes to a document.
 */
public final class PracticeFixes {

    public static final String ADD_COMPILE_STATIC = "Add @CompileStatic";
    public static final String ADD_COMPILE_DYNAMIC = "Add @CompileDynamic";
    static final Set<String> AUTO_IMPORTED = Set.of("java.lang", "java.util", "java.io", "java.net", "java.math",
            "groovy.lang", "groovy.util");
    static final Pattern DEF_WITH_TYPE = Pattern.compile("\\bdef\\s+(?=[A-Z][\\w.<>, ?\\[\\]]*\\s+\\w)");

    private PracticeFixes() {
    }

    /** Fixes for one CodeNarc finding; empty when the rule has no safe mechanical fix here. */
    public static List<CodeAction> lintFixes(Diagnostic diagnostic, TextLines lines, ModuleNode module) {
        int line = diagnostic.range().start().line();
        if (line < 0 || line >= lines.lineCount()) {
            return List.of();
        }
        String text = lines.line(line);
        return switch (diagnostic.code()) {
            case "CompileStatic" -> compileChoice(diagnostic, lines, module);
            case "UnnecessaryDefInVariableDeclaration", "UnnecessaryDefInMethodDeclaration",
                 "UnnecessaryDefInFieldDeclaration" -> removeDef(diagnostic, line, text);
            case "UnnecessaryGString" -> singleQuotes(diagnostic, line, module, lines);
            case "GStringAsMapKey" -> mapKeyToString(diagnostic, line, module, lines);
            case "NoDef", "VariableTypeRequired" -> inferType(diagnostic, line, module, lines);
            default -> List.of();
        };
    }

    private static List<CodeAction> compileChoice(Diagnostic diagnostic, TextLines lines, ModuleNode module) {
        ClassNode target = classOnLine(module, diagnostic.range().start().line());
        if (target == null) {
            return List.of();
        }
        return List.of(annotate(diagnostic, lines, module, target, "CompileStatic", ADD_COMPILE_STATIC),
                annotate(diagnostic, lines, module, target, "CompileDynamic", ADD_COMPILE_DYNAMIC));
    }

    /** The {@code @CompileDynamic} option with a reminder that the choice still needs its reason written down. */
    public static CodeAction justifyDynamic(CodeAction dynamic) {
        List<TextEdit> edits = new ArrayList<>();
        for (TextEdit edit : dynamic.edits()) {
            edits.add(edit.newText().endsWith("@CompileDynamic\n")
                    ? new TextEdit(edit.range(), edit.newText().replace("@CompileDynamic\n",
                            "@CompileDynamic // TODO justify why this class stays dynamic (it does not type-check)\n"))
                    : edit);
        }
        return new CodeAction(dynamic.title(), dynamic.diagnostic(), edits);
    }

    public static ClassNode classOnLine(ModuleNode module, int line) {
        if (module == null) {
            return null;
        }
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() - 1 <= line && line <= classNode.getLastLineNumber() - 1
                    && !classNode.isScript()) {
                ClassNode best = classNode;
                for (ClassNode other : module.getClasses()) {
                    if (other != classNode && other.getLineNumber() - 1 <= line && line <= other.getLastLineNumber() - 1
                            && other.getLineNumber() > best.getLineNumber()) {
                        best = other;
                    }
                }
                return best;
            }
        }
        return null;
    }

    static CodeAction annotate(Diagnostic diagnostic, TextLines lines, ModuleNode module, ClassNode target,
            String annotation, String title) {
        int classLine = target.getLineNumber() - 1;
        String declaration = lines.line(classLine);
        String indent = declaration.substring(0, declaration.length() - declaration.stripLeading().length());
        List<TextEdit> edits = new ArrayList<>();
        edits.add(new TextEdit(new Range(new Position(classLine, 0), new Position(classLine, 0)),
                indent + "@" + annotation + "\n"));
        TextEdit importEdit = importEdit(module, lines, "groovy.transform." + annotation);
        if (importEdit != null) {
            edits.add(importEdit);
        }
        return new CodeAction(title, diagnostic, edits);
    }

    /** An edit adding {@code import fqn}, or {@code null} when it is already imported. */
    static TextEdit importEdit(ModuleNode module, TextLines lines, String fqn) {
        String packageName = fqn.substring(0, fqn.lastIndexOf('.'));
        for (ImportNode existing : module.getImports()) {
            if (fqn.equals(existing.getClassName())) {
                return null;
            }
        }
        for (ImportNode star : module.getStarImports()) {
            if ((packageName + ".").equals(star.getPackageName())) {
                return null;
            }
        }
        int line = 0;
        int lastImport = -1;
        for (int i = 0; i < lines.lineCount(); i++) {
            String text = lines.line(i).strip();
            if (text.startsWith("package ") && lastImport < 0) {
                line = i + 1;
            }
            if (text.startsWith("import ")) {
                lastImport = i;
            }
        }
        if (lastImport >= 0) {
            return new TextEdit(new Range(new Position(lastImport + 1, 0), new Position(lastImport + 1, 0)),
                    "import " + fqn + "\n");
        }
        String prefix = line > 0 ? "\n" : "";
        return new TextEdit(new Range(new Position(line, 0), new Position(line, 0)),
                prefix + "import " + fqn + "\n" + (line > 0 ? "" : "\n"));
    }

    private static List<CodeAction> removeDef(Diagnostic diagnostic, int line, String text) {
        Matcher matcher = DEF_WITH_TYPE.matcher(text);
        if (!matcher.find()) {
            return List.of();
        }
        Range range = new Range(new Position(line, matcher.start()), new Position(line, matcher.end()));
        return List.of(new CodeAction("Remove def", diagnostic, List.of(new TextEdit(range, ""))));
    }

    /**
     * Every plain double-quoted string (no interpolation) on the finding's line, requoted from its value: the AST says
     * exactly where each one is, so no other text on the line is touched, and the value is escaped for single quotes
     * so the string means the same. Triple-quoted, slashy and dollar-slashy strings are left alone.
     */
    private static List<CodeAction> singleQuotes(Diagnostic diagnostic, int line, ModuleNode module, TextLines lines) {
        List<TextEdit> edits = new ArrayList<>();
        visit(module, new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitGStringExpression(GStringExpression expression) {
                for (Expression value : expression.getValues()) {
                    value.visit(this); // the literal parts are not strings of their own
                }
            }

            @Override
            public void visitConstantExpression(ConstantExpression constant) {
                Range range = AstRanges.rangeOf(constant);
                if (constant.getValue() instanceof String value && range != null && range.start().line() == line
                        && range.end().line() == line) {
                    String source = lines.slice(range);
                    if (source.length() >= 2 && source.startsWith("\"") && source.endsWith("\"")
                            && !source.startsWith("\"\"\"") && !source.contains("$")) {
                        edits.add(new TextEdit(range, singleQuoted(value)));
                    }
                }
                super.visitConstantExpression(constant);
            }
        });
        if (edits.isEmpty()) {
            return List.of();
        }
        edits.sort(Comparator.comparing((TextEdit edit) -> edit.range().start()));
        return List.of(new CodeAction("Use single quotes", diagnostic, edits));
    }

    /** {@code value} as a single-quoted Groovy string literal with the same value. */
    static String singleQuoted(String value) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('\'').toString();
    }

    private static List<CodeAction> mapKeyToString(Diagnostic diagnostic, int line, ModuleNode module, TextLines lines) {
        List<CodeAction> actions = new ArrayList<>();
        visit(module, new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitMapEntryExpression(MapEntryExpression entry) {
                Expression key = entry.getKeyExpression();
                Range range = AstRanges.rangeOf(key);
                if (actions.isEmpty() && key instanceof GStringExpression && range != null
                        && range.start().line() == line) {
                    // A map key that is a method call must be parenthesised: `"${k}".toString(): v` does not parse.
                    actions.add(new CodeAction("Call toString() on the key", diagnostic,
                            List.of(new TextEdit(range, "(" + lines.slice(range) + ".toString())"))));
                }
                super.visitMapEntryExpression(entry);
            }
        });
        return actions;
    }

    private static List<CodeAction> inferType(Diagnostic diagnostic, int line, ModuleNode module, TextLines lines) {
        List<CodeAction> actions = new ArrayList<>();
        visit(module, new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitDeclarationExpression(DeclarationExpression declaration) {
                Range range = AstRanges.rangeOf(declaration);
                if (actions.isEmpty() && range != null && range.start().line() == line
                        && !declaration.isMultipleAssignmentDeclaration()
                        && declaration.getLeftExpression() instanceof VariableExpression variable
                        && variable.isDynamicTyped()) {
                    ClassNode type = PracticeFinder.typeOf(declaration.getRightExpression());
                    String text = lines.slice(range);
                    int def = text.indexOf("def ");
                    boolean typable = type != null && !type.isGenericsPlaceHolder() && type.getGenericsTypes() == null;
                    if (typable && def >= 0) {
                        TypeReference reference = reference(type, module, lines);
                        Range defRange = new Range(new Position(range.start().line(), range.start().column() + def),
                                new Position(range.start().line(), range.start().column() + def + 3));
                        actions.add(new CodeAction("Declare as " + reference.name(), diagnostic,
                                reference.with(new TextEdit(defRange, reference.name()))));
                    } else if (typable) {
                        // `final x = ...`: the modifier stands where a type belongs, so insert the type after it.
                        java.util.regex.Matcher modifier = java.util.regex.Pattern
                                .compile("\\bfinal\\s+(?=" + java.util.regex.Pattern.quote(variable.getName()) + "\\b)")
                                .matcher(lines.line(range.start().line()));
                        if (modifier.find()) {
                            TypeReference reference = reference(type, module, lines);
                            Position at = new Position(range.start().line(), modifier.end());
                            actions.add(new CodeAction("Declare as " + reference.name(), diagnostic,
                                    reference.with(new TextEdit(new Range(at, at), reference.name() + " "))));
                        }
                    }
                }
                super.visitDeclarationExpression(declaration);
            }
        });
        return actions;
    }

    /** How a fix writes a type into a document: the name to write and the import that makes it resolve, if any. */
    record TypeReference(String name, TextEdit importEdit) {
        List<TextEdit> with(TextEdit edit) {
            return importEdit == null ? List.of(edit) : List.of(edit, importEdit);
        }
    }

    /**
     * The name a fix may write for {@code type} in {@code module}: the simple name when it already resolves there
     * (same package, imported, or imported by default), else the simple name plus an import, else (another type
     * there has the same simple name) the qualified name.
     */
    static TypeReference reference(ClassNode type, ModuleNode module, TextLines lines) {
        String simple = type.getNameWithoutPackage();
        String packageName = type.getPackageName();
        if (packageName == null || AUTO_IMPORTED.contains(packageName)
                || packageName.equals(module.getPackageName() == null ? null : stripDot(module.getPackageName()))) {
            return new TypeReference(simple, null);
        }
        for (ImportNode existing : module.getImports()) {
            if (type.getName().equals(existing.getClassName())) {
                return new TypeReference(simple, null);
            }
        }
        for (ImportNode star : module.getStarImports()) {
            if ((packageName + ".").equals(star.getPackageName())) {
                return new TypeReference(simple, null);
            }
        }
        boolean taken = module.getImports().stream().anyMatch(i -> simple.equals(i.getAlias()))
                || module.getClasses().stream().anyMatch(c -> simple.equals(c.getNameWithoutPackage()));
        if (taken || type.getName().contains("$")) {
            return new TypeReference(type.getName().replace('$', '.'), null);
        }
        return new TypeReference(simple, importEdit(module, lines, type.getName()));
    }

    private static String stripDot(String packageName) {
        return packageName.endsWith(".") ? packageName.substring(0, packageName.length() - 1) : packageName;
    }

    public static String typeName(ClassNode type, ModuleNode module) {
        if (type.isPrimaryClassNode() || type.getPackageName() == null
                || AUTO_IMPORTED.contains(type.getPackageName())) {
            return type.getNameWithoutPackage();
        }
        for (ImportNode existing : module.getImports()) {
            if (type.getName().equals(existing.getClassName())) {
                return type.getNameWithoutPackage();
            }
        }
        return type.getName();
    }

    private static void visit(ModuleNode module, ClassCodeVisitorSupport visitor) {
        if (module == null) {
            return;
        }
        for (ClassNode classNode : module.getClasses()) {
            visitor.visitClass(classNode);
        }
    }

    /**
     * Applies edits to a text. Earlier edits in the list win: an edit overlapping one already accepted is
     * skipped. The result says which were used.
     */
    public static Applied apply(String text, List<TextEdit> edits) {
        TextLines lines = new TextLines(text);
        List<int[]> spans = new ArrayList<>();
        List<TextEdit> accepted = new ArrayList<>();
        for (TextEdit edit : edits) {
            int start = lines.offsetOf(edit.range().start());
            int end = lines.offsetOf(edit.range().end());
            if (start > end || accepted.contains(edit)) {
                // Two findings on one spot (NoDef and VariableTypeRequired) propose the same edit: apply it once.
                continue;
            }
            boolean clash = false;
            for (int[] span : spans) {
                // Two inserts at one point clash, unless both insert whole lines (two imports after the last one,
                // an annotation and an import): those both apply, in list order.
                if (start < span[1] && span[0] < end || start == end && span[0] < start && start < span[1]
                        || span[0] == span[1] && start < span[0] && span[0] < end
                        || start == end && span[0] == span[1] && start == span[0]
                        && !(wholeLine(edit) && wholeLine(accepted.get(spans.indexOf(span))))) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                spans.add(new int[] {start, end});
                accepted.add(edit);
            }
        }
        List<TextEdit> ordered = new ArrayList<>(accepted);
        // Latest first; at the same start, the replacement before a zero-width insert, so offsets stay valid; of
        // two inserts at one point the later one goes in first, so the text reads in list order, except that an
        // import always reads before an annotation line (which would otherwise annotate the import).
        ordered.sort(Comparator.comparing((TextEdit e) -> e.range().start()).thenComparing(e -> e.range().end())
                .thenComparing(e -> e.newText().strip().startsWith("import ") ? 0 : 1)
                .thenComparing(accepted::indexOf).reversed());
        StringBuilder result = new StringBuilder(text);
        for (TextEdit edit : ordered) {
            result.replace(lines.offsetOf(edit.range().start()), lines.offsetOf(edit.range().end()), edit.newText());
        }
        return new Applied(result.toString(), accepted);
    }

    /** The outcome of {@link #apply}. */
    /** An insert of complete lines at the start of a line: an import or an annotation line. */
    static boolean wholeLine(TextEdit edit) {
        return edit.range().start().equals(edit.range().end()) && edit.range().start().column() == 0
                && edit.newText().endsWith("\n");
    }

    public record Applied(String text, List<TextEdit> edits) {
        public Applied {
            edits = List.copyOf(edits);
        }
    }
}
