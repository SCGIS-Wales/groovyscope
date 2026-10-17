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
package org.groovyscope.engine.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.FoldingRange;
import org.groovyscope.engine.model.Highlight;
import org.groovyscope.engine.model.InlayHint;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.practices.PracticeFixes;
import org.groovyscope.engine.semantics.Resolution;

/** Per-document editor features: implementations, highlights, folding ranges and inlay type hints. */
final class DocumentSupport {

    private DocumentSupport() {
    }

    /**
     * For a method, the methods with the same name and arity declared in workspace subclasses and implementations of
     * its class; for a class or interface, its workspace subclasses and implementors.
     */
    static List<Location> implementations(Snapshot current, String uri, Position position) {
        Optional<ASTNode> declaration = current.index().nodeAt(uri, position)
                .flatMap(entry -> current.resolver().resolve(entry))
                .map(Resolution::primary);
        if (declaration.isEmpty()) {
            return List.of();
        }
        Set<ASTNode> found = new LinkedHashSet<>();
        if (declaration.get() instanceof MethodNode method && !(method instanceof ConstructorNode)) {
            ClassNode owner = method.getDeclaringClass();
            int arity = method.getParameters().length;
            for (ClassNode classNode : current.index().classes()) {
                if (classNode != owner && isSubtype(classNode, owner)) {
                    for (MethodNode candidate : declaredMethods(classNode, method.getName())) {
                        if (candidate.getParameters().length == arity && !candidate.isSynthetic()) {
                            found.add(candidate);
                        }
                    }
                }
            }
        } else if (declaration.get() instanceof ClassNode type) {
            for (ClassNode classNode : current.index().classes()) {
                if (classNode != type && isSubtype(classNode, type)) {
                    found.add(classNode);
                }
            }
        }
        List<Location> locations = new ArrayList<>();
        for (ASTNode node : found) {
            Symbol symbol = current.symbols().of(node);
            if (symbol.location() != null) {
                Range range = symbol.selectionRange() != null ? symbol.selectionRange() : symbol.location().range();
                locations.add(new Location(symbol.location().uri(), range));
            }
        }
        return locations;
    }

    private static List<MethodNode> declaredMethods(ClassNode classNode, String name) {
        try {
            return classNode.getDeclaredMethods(name);
        } catch (LinkageError e) {
            return List.of(); // a member mentions a class missing from the classpath
        }
    }

    /** Whether {@code candidate} extends or implements {@code target}, directly or through its supertypes. */
    static boolean isSubtype(ClassNode candidate, ClassNode target) {
        return isSubtype(candidate, target.getName(), Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean isSubtype(ClassNode candidate, String target, Set<ClassNode> seen) {
        if (candidate == null || !seen.add(candidate)) {
            return false;
        }
        try {
            ClassNode superClass = candidate.getSuperClass();
            if (superClass != null && (target.equals(superClass.getName()) || isSubtype(superClass.redirect(), target, seen))) {
                return true;
            }
            for (ClassNode type : candidate.getInterfaces()) {
                if (target.equals(type.getName()) || isSubtype(type.redirect(), target, seen)) {
                    return true;
                }
            }
        } catch (LinkageError e) {
            return false;
        }
        return false;
    }

    /** Occurrences of the symbol under the cursor in the same document; declarations and assignments write. */
    static List<Highlight> highlights(Snapshot current, String uri, Position position) {
        Optional<NodeEntry> at = current.index().nodeAt(uri, position);
        Optional<Resolution> resolution = at.flatMap(entry -> current.resolver().resolve(entry));
        if (resolution.isEmpty()) {
            return List.of();
        }
        ASTNode declaration = resolution.get().primary();
        Set<ASTNode> written = assignmentTargets(current.result().modules().get(uri));
        TextLines lines = current.index().lines(uri);
        List<Highlight> highlights = new ArrayList<>();
        Set<Range> seen = new LinkedHashSet<>();
        for (NodeEntry candidate : current.index().entries(uri)) {
            if (candidate.role() == NodeEntry.Role.OTHER) {
                continue;
            }
            Range range;
            boolean write;
            if (candidate.isDeclaration()) {
                if (candidate.node() != declaration) {
                    continue;
                }
                range = AstRanges.nameRange(declaration, candidate.range(), lines);
                write = true;
            } else {
                Optional<Resolution> target = current.resolver().resolve(candidate);
                if (target.isEmpty() || !target.get().includes(declaration)) {
                    continue;
                }
                range = LanguageServices.nameRangeOfUsage(current, candidate, declaration);
                write = written.contains(candidate.node()) || candidate.parent() != null && written.contains(candidate.parent());
            }
            if (range != null && seen.add(range)) {
                highlights.add(new Highlight(range, write));
            }
        }
        return highlights;
    }

    /** Left-hand sides of assignments (not declarations) and operands of {@code ++}/{@code --}, by identity. */
    static Set<ASTNode> assignmentTargets(ModuleNode module) {
        Set<ASTNode> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        visit(module, new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitBinaryExpression(BinaryExpression expression) {
                if (!(expression instanceof DeclarationExpression)
                        && Types.isAssignment(expression.getOperation().getType())) {
                    targets.add(expression.getLeftExpression());
                }
                super.visitBinaryExpression(expression);
            }

            @Override
            public void visitPostfixExpression(PostfixExpression expression) {
                targets.add(expression.getExpression());
                super.visitPostfixExpression(expression);
            }

            @Override
            public void visitPrefixExpression(PrefixExpression expression) {
                targets.add(expression.getExpression());
                super.visitPrefixExpression(expression);
            }
        });
        return targets;
    }

    /** Classes, methods, closures, multi-line comments and import groups of a document. */
    static List<FoldingRange> foldingRanges(ModuleNode module, String text) {
        if (text == null) {
            return List.of();
        }
        TextLines lines = new TextLines(text);
        Set<FoldingRange> ranges = new LinkedHashSet<>();
        commentsAndImports(text, lines, ranges);
        if (module != null) {
            for (ClassNode classNode : module.getClasses()) {
                if (!classNode.isScript()) {
                    region(AstRanges.rangeOf(classNode), lines, ranges);
                }
                for (MethodNode method : methods(classNode)) {
                    if (!(classNode.isScript() && "run".equals(method.getName()) && method.getParameters().length == 0)
                            && !method.isSynthetic()) {
                        region(AstRanges.rangeOf(method), lines, ranges);
                    }
                }
            }
            visit(module, new ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return module.getContext();
                }

                @Override
                public void visitClosureExpression(ClosureExpression expression) {
                    region(AstRanges.rangeOf(expression), lines, ranges);
                    super.visitClosureExpression(expression);
                }
            });
        }
        List<FoldingRange> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> a.startLine() != b.startLine() ? Integer.compare(a.startLine(), b.startLine())
                : Integer.compare(b.endLine(), a.endLine()));
        return sorted;
    }

    private static List<MethodNode> methods(ClassNode classNode) {
        List<MethodNode> methods = new ArrayList<>(classNode.getDeclaredConstructors());
        methods.addAll(classNode.getMethods());
        return methods;
    }

    /** A code region folds up to the line before its closing brace. */
    private static void region(Range range, TextLines lines, Set<FoldingRange> out) {
        if (range == null) {
            return;
        }
        int end = range.end().line();
        if (lines.line(end).strip().startsWith("}")) {
            end--;
        }
        if (end > range.start().line()) {
            out.add(new FoldingRange(range.start().line(), end, FoldingRange.Kind.REGION));
        }
    }

    /** Scans the text for block comments (outside strings and line comments) and runs of import lines. */
    static void commentsAndImports(String text, TextLines lines, Set<FoldingRange> out) {
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int newline = text.indexOf('\n', i);
                i = newline < 0 ? length : newline;
            } else if (text.startsWith("/*", i)) {
                int close = text.indexOf("*/", i + 2);
                int end = close < 0 ? length : close + 2;
                int first = lines.positionOf(i).line();
                int last = lines.positionOf(end).line();
                if (last > first) {
                    out.add(new FoldingRange(first, last, FoldingRange.Kind.COMMENT));
                }
                i = end;
            } else if (text.startsWith("'''", i) || text.startsWith("\"\"\"", i)) {
                int close = text.indexOf(text.substring(i, i + 3), i + 3);
                i = close < 0 ? length : close + 3;
            } else if (c == '\'' || c == '"') {
                i = skipQuoted(text, i, c);
            } else {
                i++;
            }
        }
        int first = -1;
        int last = -1;
        for (int line = 0; line < lines.lineCount(); line++) {
            String content = lines.line(line).strip();
            if (content.startsWith("import ")) {
                if (first < 0) {
                    first = line;
                }
                last = line;
            } else if (!content.isEmpty() && first >= 0) {
                addImports(first, last, out);
                first = -1;
            }
        }
        addImports(first, last, out);
    }

    private static void addImports(int first, int last, Set<FoldingRange> out) {
        if (first >= 0 && last > first) {
            out.add(new FoldingRange(first, last, FoldingRange.Kind.IMPORTS));
        }
    }

    private static int skipQuoted(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote || c == '\n') {
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    /**
     * Type hints after the names of dynamically typed local variables ({@code def x = ...}) whose initializer has a
     * statically known, non-generic type, for declarations that start inside {@code range}.
     */
    static List<InlayHint> inlayHints(ModuleNode module, TextLines lines, Range range) {
        Set<InlayHint> hints = new LinkedHashSet<>();
        if (module == null) {
            return List.of();
        }
        visit(module, new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitDeclarationExpression(DeclarationExpression declaration) {
                hint(declaration, module, lines, range).ifPresent(hints::add);
                super.visitDeclarationExpression(declaration);
            }
        });
        List<InlayHint> sorted = new ArrayList<>(hints);
        sorted.sort((a, b) -> a.position().compareTo(b.position()));
        return sorted;
    }

    private static Optional<InlayHint> hint(DeclarationExpression declaration, ModuleNode module, TextLines lines,
            Range range) {
        Range declared = AstRanges.rangeOf(declaration);
        if (declared == null || declaration.isMultipleAssignmentDeclaration()
                || !(declaration.getLeftExpression() instanceof VariableExpression variable)
                || !variable.isDynamicTyped()) {
            return Optional.empty();
        }
        org.codehaus.groovy.ast.ClassNode type = PracticeFinder.typeOf(declaration.getRightExpression());
        if (type == null || type.isGenericsPlaceHolder() || type.getGenericsTypes() != null) {
            return Optional.empty();
        }
        int line = declared.start().line();
        String text = lines.line(line);
        Matcher name = Pattern.compile("\\b" + Pattern.quote(variable.getName()) + "\\b").matcher(text);
        if (!name.find(Math.min(declared.start().column(), text.length()))) {
            return Optional.empty();
        }
        Position at = new Position(line, name.end());
        if (at.compareTo(range.start()) < 0 || at.compareTo(range.end()) > 0) {
            return Optional.empty();
        }
        return Optional.of(new InlayHint(at, ": " + PracticeFixes.typeName(type, module)));
    }

    private static void visit(ModuleNode module, ClassCodeVisitorSupport visitor) {
        if (module == null) {
            return;
        }
        for (ClassNode classNode : module.getClasses()) {
            visitor.visitClass(classNode);
        }
    }
}
