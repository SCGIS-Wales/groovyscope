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
    static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"'\\\\$]*)\"");

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
            case "UnnecessaryGString" -> singleQuotes(diagnostic, line, text);
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

    static ClassNode classOnLine(ModuleNode module, int line) {
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

    private static List<CodeAction> singleQuotes(Diagnostic diagnostic, int line, String text) {
        Matcher matcher = DOUBLE_QUOTED.matcher(text);
        if (!matcher.find()) {
            return List.of();
        }
        Range range = new Range(new Position(line, matcher.start()), new Position(line, matcher.end()));
        return List.of(new CodeAction("Use single quotes", diagnostic,
                List.of(new TextEdit(range, "'" + matcher.group(1) + "'"))));
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
                    actions.add(new CodeAction("Call toString() on the key", diagnostic,
                            List.of(new TextEdit(range, lines.slice(range) + ".toString()"))));
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
                        String name = typeName(type, module);
                        Range defRange = new Range(new Position(range.start().line(), range.start().column() + def),
                                new Position(range.start().line(), range.start().column() + def + 3));
                        actions.add(new CodeAction("Declare as " + name, diagnostic, List.of(new TextEdit(defRange, name))));
                    } else if (typable) {
                        // `final x = ...`: the modifier stands where a type belongs, so insert the type after it.
                        java.util.regex.Matcher modifier = java.util.regex.Pattern
                                .compile("\\bfinal\\s+(?=" + java.util.regex.Pattern.quote(variable.getName()) + "\\b)")
                                .matcher(lines.line(range.start().line()));
                        if (modifier.find()) {
                            String name = typeName(type, module);
                            Position at = new Position(range.start().line(), modifier.end());
                            actions.add(new CodeAction("Declare as " + name, diagnostic,
                                    List.of(new TextEdit(new Range(at, at), name + " "))));
                        }
                    }
                }
                super.visitDeclarationExpression(declaration);
            }
        });
        return actions;
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
                if (start < span[1] && span[0] < end || start == end && span[0] < start && start < span[1]
                        || span[0] == span[1] && start < span[0] && span[0] < end
                        || start == end && span[0] == span[1] && start == span[0]) {
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
        // Latest first; at the same start, the replacement before a zero-width insert, so offsets stay valid.
        ordered.sort(Comparator.comparing((TextEdit e) -> e.range().start()).thenComparing(e -> e.range().end()).reversed());
        StringBuilder result = new StringBuilder(text);
        for (TextEdit edit : ordered) {
            result.replace(lines.offsetOf(edit.range().start()), lines.offsetOf(edit.range().end()), edit.newText());
        }
        return new Applied(result.toString(), accepted);
    }

    /** The outcome of {@link #apply}. */
    public record Applied(String text, List<TextEdit> edits) {
        public Applied {
            edits = List.copyOf(edits);
        }
    }
}
