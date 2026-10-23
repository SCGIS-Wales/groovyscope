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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.Declarations;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.search.SymbolEditor;
import org.groovyscope.engine.search.SymbolReader;
import org.groovyscope.engine.services.Snapshot;

/**
 * Moves a static method, or a method that uses no instance state (it becomes static), into another workspace class:
 * the declaration with its comments and annotations goes to the end of the target class, the members of its old
 * class it uses are qualified, the imports it needs are added, and every call site whose target is known calls it
 * through the new class. Refuses a call matched by name only, a call through an instance, instance state and a
 * method of the same signature already in the target.
 */
public final class MemberMover {

    private static final String WHAT = "move member";
    private static final Pattern PRIVATE = Pattern.compile("\\bprivate\\s+");

    private MemberMover() {
    }

    /**
     * A move.
     *
     * @param symbol the method, as {@code groovyscope/edit} names it
     * @param line 1-based line that picks one of several overloads, or {@code null}
     * @param toClass the target, a workspace class by simple or qualified name
     */
    public record Request(String symbol, Integer line, String toClass) {
    }

    /**
     * What the move does, or why it is refused.
     *
     * @param refused the reason nothing is changed, or {@code null}
     * @param changes the edits
     * @param method the moved method's signature
     * @param from the class it leaves, qualified
     * @param to the class it joins, qualified
     * @param callSites the call sites rewritten
     * @param madeStatic whether it was an instance method and is now static
     * @param madePublic whether it was private and lost the modifier, because other classes now call it
     */
    public record Result(String refused, Changes changes, String method, String from, String to, int callSites,
            boolean madeStatic, boolean madePublic) {

        static Result refusal(String reason) {
            return new Result(reason, Changes.none(), null, null, null, 0, false, false);
        }
    }

    /**
     * Computes the move of the method {@code request} names in the document {@code uri} of {@code snapshot}.
     *
     * @throws IllegalArgumentException when the document is not compiled or the symbol is not found or not a method
     */
    public static Result move(Snapshot snapshot, String uri, Request request) {
        ModuleNode module = snapshot.result().modules().get(uri);
        String source = snapshot.result().sources().get(uri);
        if (module == null || source == null) {
            throw new IllegalArgumentException(uri + " is not a compiled Groovy document of the workspace");
        }
        if (request.toClass() == null || request.toClass().isBlank()) {
            throw new IllegalArgumentException("move member needs toClass, the class to move the method into");
        }
        Declarations.Located located = SymbolEditor.resolve(Declarations.located(module),
                TypeText.packageOf(module), request.symbol(), request.line());
        if (!(located.node() instanceof MethodNode method) || method instanceof ConstructorNode) {
            throw new IllegalArgumentException("move member needs a method; " + located.declaration().name()
                    + " is a " + located.declaration().kind().label());
        }
        return new Work(snapshot, uri, module, source, method, located.declaration(), request.toClass().strip())
                .run();
    }

    /** One move being computed. */
    private static final class Work {

        private final Snapshot snapshot;
        private final String uri;
        private final ModuleNode module;
        private final String source;
        private final TextLines lines;
        private final MethodNode method;
        private final Declarations.Declaration declaration;
        private final String toClass;
        private final ClassNode owner;
        private final String eol;
        private final Map<String, TextLines> linesByUri = new LinkedHashMap<>();

        Work(Snapshot snapshot, String uri, ModuleNode module, String source, MethodNode method,
                Declarations.Declaration declaration, String toClass) {
            this.snapshot = snapshot;
            this.uri = uri;
            this.module = module;
            this.source = source;
            this.lines = new TextLines(source);
            this.method = method;
            this.declaration = declaration;
            this.toClass = toClass;
            this.owner = method.getDeclaringClass();
            this.eol = source.contains("\r\n") ? "\r\n" : "\n";
        }

        Result run() {
            String name = method.getName();
            if (!method.isStatic()) {
                Optional<String> state = InstanceState.in(method);
                if (state.isPresent()) {
                    return Result.refusal(name + " uses instance state (" + state.get() + "); " + WHAT + " moves a "
                            + "static method or one that uses none");
                }
                String hierarchy = SignatureChanger.hierarchy(snapshot, method);
                if (hierarchy != null) {
                    return Result.refusal(hierarchy.replace("; change the two together", "; " + WHAT
                            + " refused"));
                }
                if (method.isAbstract()) {
                    return Result.refusal(name + " is abstract; " + WHAT + " refused");
                }
            }
            Object resolved = target();
            if (resolved instanceof String reason) {
                return Result.refusal(reason);
            }
            ClassNode target = (ClassNode) resolved;
            String targetUri = snapshot.index().declarationEntry(target).map(NodeEntry::uri).orElse(null);
            ModuleNode targetModule = targetUri == null ? null : snapshot.result().modules().get(targetUri);
            String targetSource = targetUri == null ? null : snapshot.result().sources().get(targetUri);
            if (targetModule == null || targetSource == null) {
                return Result.refusal(TypeText.simpleName(target) + " is not a compiled class of the workspace");
            }
            for (MethodNode existing : target.getDeclaredMethods(name)) {
                if (SignatureChanger.sameParameters(existing.getParameters(), method.getParameters())) {
                    return Result.refusal(TypeText.simpleName(target) + " already has " + name + "("
                            + String.join(", ", java.util.Arrays.stream(method.getParameters())
                            .map(p -> TypeText.simpleName(p.getType())).toList()) + ") at line "
                            + existing.getLineNumber() + "; " + WHAT + " refused");
                }
            }
            Object span = span();
            if (span instanceof String reason) {
                return Result.refusal(reason);
            }
            int[] removed = (int[]) span;
            Usages usages = Usages.of(snapshot, method);
            String refusal = usages.dynamicRefusal(WHAT);
            if (refusal == null) {
                refusal = usages.otherRefusal(WHAT);
            }
            if (refusal != null) {
                return Result.refusal(refusal);
            }
            Changes.Builder builder = new Changes.Builder();
            List<Changes.Span> inside = new ArrayList<>();
            Map<String, Set<String>> imports = new LinkedHashMap<>();
            int outsideCalls = 0;
            for (Usages.Call call : usages.calls()) {
                Cancellation.checkpoint();
                String text = snapshot.result().sources().get(call.uri());
                TextLines callLines = textLines(call.uri());
                ModuleNode callModule = snapshot.result().modules().get(call.uri());
                int at = callLines.offsetOf(call.entry().range().start());
                boolean within = call.uri().equals(uri) && at >= removed[0] && at < removed[1];
                String written = within ? TypeText.simpleName(target)
                        : nameIn(target, callModule, call.uri(), imports);
                Object edit = callEdit(call, text, callLines, written, within);
                if (edit instanceof String reason) {
                    return Result.refusal(reason);
                }
                if (edit == null) {
                    continue;
                }
                Changes.Span change = (Changes.Span) edit;
                if (within) {
                    inside.add(change);
                } else {
                    outsideCalls++;
                    builder.replace(call.uri(), text, change.start(), change.end(), change.text());
                }
            }
            Object qualified = qualifyOwnMembers(name, inside);
            if (qualified instanceof String reason) {
                return Result.refusal(reason);
            }
            boolean qualifiesOwner = Boolean.TRUE.equals(qualified);
            Range whole = AstRanges.rangeOf(method);
            Range nameRange = AstRanges.nameRange(method, whole, lines);
            int nameStart = lines.offsetOf(nameRange.start());
            int methodStart = SourceText.start(lines, method);
            boolean madeStatic = !method.isStatic();
            if (madeStatic) {
                int[] type = SourceText.typeBefore(source, nameStart);
                int at = type == null ? nameStart : type[0];
                inside.add(new Changes.Span(at, at, "static "));
            }
            boolean madePublic = false;
            if (method.isPrivate() && outsideCalls > 0 && methodStart >= 0 && methodStart < nameStart) {
                Matcher matcher = PRIVATE.matcher(source.substring(methodStart, nameStart));
                if (matcher.find()) {
                    inside.add(new Changes.Span(methodStart + matcher.start(), methodStart + matcher.end(), ""));
                    madePublic = true;
                }
            }
            String moved = Changes.slice(source, removed[2], removed[3], inside);
            TextLines targetLines = textLines(targetUri);
            Object insertion = insertion(target, targetSource, targetLines, moved);
            if (insertion instanceof String reason) {
                return Result.refusal(reason);
            }
            Changes.Span insert = (Changes.Span) insertion;
            builder.replace(uri, source, removed[0], removed[1], "");
            builder.insert(targetUri, targetSource, insert.start(), insert.text());
            Set<String> needed = new LinkedHashSet<>(typeImports(targetModule));
            if (qualifiesOwner) {
                String ownerImport = TypeText.importFor(owner, targetModule);
                if (ownerImport != null) {
                    needed.add(ownerImport);
                }
            }
            needed.addAll(staticImports(targetModule));
            imports.computeIfAbsent(targetUri, key -> new LinkedHashSet<>()).addAll(needed);
            for (Map.Entry<String, Set<String>> entry : imports.entrySet()) {
                Imports.add(builder, entry.getKey(), snapshot.result().sources().get(entry.getKey()),
                        snapshot.result().modules().get(entry.getKey()), entry.getValue());
            }
            Changes changes;
            try {
                changes = builder.build();
            } catch (IllegalStateException e) {
                return Result.refusal(e.getMessage() + "; " + WHAT + " refused");
            }
            return new Result(null, changes, SourceText.header(source, lines, method, inside), owner.getName(),
                    target.getName(), outsideCalls, madeStatic, madePublic);
        }

        private TextLines textLines(String document) {
            return linesByUri.computeIfAbsent(document, key -> new TextLines(snapshot.result().sources().get(key)));
        }

        /** The target class, or the reason there is none. */
        private Object target() {
            List<ClassNode> found = new ArrayList<>();
            snapshot.index().classNamed(toClass).ifPresent(found::add);
            if (found.isEmpty()) {
                String simple = toClass.substring(toClass.lastIndexOf('.') + 1);
                for (ClassNode type : snapshot.index().classesWithSimpleName(simple)) {
                    String canonical = TypeText.canonical(type);
                    if (canonical.equals(toClass) || canonical.endsWith("." + toClass)) {
                        found.add(type);
                    }
                }
            }
            if (found.isEmpty()) {
                return "no workspace class is named " + toClass;
            }
            if (found.size() > 1) {
                return toClass + " is ambiguous (" + String.join(", ", found.stream().map(ClassNode::getName)
                        .sorted().toList()) + "); give the qualified name";
            }
            ClassNode target = found.get(0);
            if (target.equals(owner)) {
                return method.getName() + " is already in " + TypeText.simpleName(target);
            }
            if (target.isInterface() || target.isAnnotationDefinition() || target.isScript()) {
                return TypeText.simpleName(target) + " is " + (target.isScript() ? "a script" : "an interface")
                        + "; " + WHAT + " moves into a class";
            }
            return target;
        }

        /**
         * The moved lines as {@code [deleteStart, deleteEnd, movedStart, movedEnd]}: what the old document loses
         * (the declaration with its comments, annotations and one blank line next to it) and the text that moves; or
         * the reason the declaration cannot be cut out.
         */
        private Object span() {
            int first = SymbolReader.leadingStart(lines, declaration.line() - 1);
            int last = declaration.endLine() - 1;
            String opening = lines.line(method.getLineNumber() - 1);
            String before = opening.substring(0, Math.min(opening.length(), Math.max(0, method.getColumnNumber() - 1)));
            String closing = lines.line(last);
            String after = closing.substring(Math.min(closing.length(), Math.max(0, method.getLastColumnNumber() - 1)))
                    .strip();
            if (!before.isBlank() || !after.isEmpty() && !after.startsWith("//")) {
                return method.getName() + " shares a line with other code; put it on lines of its own first";
            }
            int count = lineCount(lines);
            int movedStart = lineStart(lines, first);
            int movedEnd = contentEnd(lines, last);
            int deleteStart = movedStart;
            int deleteEnd = last + 1 < lines.lineCount() ? lineStart(lines, last + 1) : source.length();
            if (last + 1 < count && lines.line(last + 1).isBlank()) {
                deleteEnd = last + 2 < lines.lineCount() ? lineStart(lines, last + 2) : source.length();
            } else if (first > 0 && lines.line(first - 1).isBlank()) {
                deleteStart = lineStart(lines, first - 1);
            }
            return new int[] {deleteStart, deleteEnd, movedStart, movedEnd};
        }

        /**
         * The edit that makes one call go through the target class ({@code written}), {@code null} when it needs none
         * (a recursive call inside the moved method), or the reason it cannot.
         */
        private Object callEdit(Usages.Call call, String text, TextLines callLines, String written, boolean within) {
            if (call.call() instanceof StaticMethodCallExpression invocation) {
                if (within) {
                    return null;
                }
                ModuleNode callModule = snapshot.result().modules().get(call.uri());
                if (staticallyImported(callModule)) {
                    return call.where() + " calls " + method.getName() + " through a static import of "
                            + TypeText.simpleName(owner) + "; import it from the new class first";
                }
                int at = SourceText.callName(text, callLines.offsetOf(call.entry().range().start()),
                        callLines.offsetOf(call.entry().range().end()), invocation.getMethod());
                if (at < 0) {
                    return "the call at " + call.where() + " was not found in the source; " + WHAT + " refused";
                }
                return new Changes.Span(at, at, written + ".");
            }
            MethodCallExpression invocation = (MethodCallExpression) call.call();
            if (invocation.isImplicitThis()) {
                if (within) {
                    return null;
                }
                int at = callLines.offsetOf(call.entry().range().start());
                return new Changes.Span(at, at, written + ".");
            }
            Expression receiver = invocation.getObjectExpression();
            boolean self = receiver instanceof VariableExpression variable
                    && ("this".equals(variable.getName()) || "super".equals(variable.getName()));
            if (!(receiver instanceof ClassExpression) && !self) {
                return method.getName() + " is called through an instance at " + call.where() + " (`"
                        + SourceText.of(callLines, invocation) + "`); call it through its class first";
            }
            int start = SourceText.start(callLines, receiver);
            int end = SourceText.end(callLines, receiver);
            if (start < 0 || end <= start) {
                return "the receiver of the call at " + call.where() + " has no position; " + WHAT + " refused";
            }
            return new Changes.Span(start, end, written);
        }

        /** Whether {@code callModule} imports the moved method statically from its old class. */
        private boolean staticallyImported(ModuleNode callModule) {
            if (callModule == null) {
                return false;
            }
            for (ImportNode node : callModule.getStaticImports().values()) {
                if (node.getType() != null && node.getType().equals(owner)
                        && method.getName().equals(node.getFieldName())) {
                    return true;
                }
            }
            for (ImportNode node : callModule.getStaticStarImports().values()) {
                if (node.getType() != null && node.getType().equals(owner)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * How {@code document} writes {@code type}: its simple name, with an import noted when the document needs
         * one, or its qualified name when another class of that name is imported there.
         */
        private String nameIn(ClassNode type, ModuleNode document, String documentUri,
                Map<String, Set<String>> imports) {
            if (TypeText.visible(type, document)) {
                return TypeText.simpleName(type);
            }
            String statement = TypeText.importFor(type, document);
            if (statement == null) {
                return TypeText.canonical(type);
            }
            imports.computeIfAbsent(documentUri, key -> new LinkedHashSet<>()).add(statement);
            return TypeText.simpleName(type);
        }

        /**
         * Qualifies the moved method's uses of its old class's static members ({@code Old.LIMIT},
         * {@code Old.helper()}), adding the spans to {@code inside}: {@code true} when one was qualified, or the
         * reason a member cannot be reached from the new class.
         */
        private Object qualifyOwnMembers(String name, List<Changes.Span> inside) {
            String qualifier = TypeText.simpleName(owner) + ".";
            Set<String> problems = new LinkedHashSet<>();
            boolean[] any = new boolean[1];
            CodeVisitorSupport visitor = new CodeVisitorSupport() {
                @Override
                public void visitVariableExpression(VariableExpression expression) {
                    Variable accessed = expression.getAccessedVariable();
                    if (accessed instanceof FieldNode field && owner.equals(field.getOwner())) {
                        if (field.isPrivate() && owner.getProperty(field.getName()) == null) {
                            problems.add("the private field " + field.getName());
                        } else {
                            qualify(SourceText.start(lines, expression));
                        }
                    } else if (accessed instanceof PropertyNode property
                            && owner.equals(property.getDeclaringClass())) {
                        qualify(SourceText.start(lines, expression));
                    }
                }

                @Override
                public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                    if (owner.equals(call.getOwnerType()) && !call.getMethod().equals(name)) {
                        List<MethodNode> targets = owner.getMethods(call.getMethod());
                        if (!targets.isEmpty() && targets.stream().allMatch(MethodNode::isPrivate)) {
                            problems.add("the private method " + call.getMethod());
                        } else {
                            qualify(SourceText.start(lines, call));
                        }
                    } else if (owner.equals(call.getOwnerType()) && owner.getMethods(name).size() > 1) {
                        problems.add("another overload of " + name);
                    }
                    super.visitStaticMethodCallExpression(call);
                }

                @Override
                public void visitMethodCallExpression(MethodCallExpression call) {
                    if (call.isImplicitThis() && !name.equals(call.getMethodAsString())
                            && !owner.getMethods(call.getMethodAsString()).isEmpty()) {
                        qualify(SourceText.start(lines, call.getMethod()));
                    }
                    super.visitMethodCallExpression(call);
                }

                private void qualify(int at) {
                    if (at >= 0) {
                        inside.add(new Changes.Span(at, at, qualifier));
                        any[0] = true;
                    }
                }
            };
            for (Parameter parameter : method.getParameters()) {
                if (parameter.hasInitialExpression()) {
                    parameter.getInitialExpression().visit(visitor);
                }
            }
            if (method.getCode() != null) {
                method.getCode().visit(visitor);
            }
            if (!problems.isEmpty()) {
                return method.getName() + " uses " + String.join(", ", problems) + " of " + TypeText.simpleName(owner)
                        + ", which the new class cannot reach; make it reachable or move it too";
            }
            return any[0];
        }

        /** The insertion at the end of the target's body, or the reason there is none. */
        private Object insertion(ClassNode target, String targetSource, TextLines targetLines, String moved) {
            int end = SourceText.end(targetLines, target);
            int close = end > 0 ? targetSource.lastIndexOf('}', end - 1) : -1;
            int open = SymbolEditor.openBrace(targetSource, Math.max(0, SourceText.start(targetLines, target)), end);
            if (close < 0 || open < 0 || close <= open) {
                return "the body of " + TypeText.simpleName(target) + " was not found";
            }
            int closeLine = targetLines.positionOf(close).line();
            int openLine = targetLines.positionOf(open).line();
            String beforeBrace = targetLines.line(closeLine).substring(0, targetLines.positionOf(close).column());
            if (openLine == closeLine || !beforeBrace.isBlank()) {
                return "the closing brace of " + TypeText.simpleName(target) + " shares a line with code; put it on "
                        + "a line of its own first";
            }
            String indent = SourceText.indent(targetLines.line(target.getLineNumber() - 1));
            String unit = SymbolEditor.indentUnit(targetLines, lineCount(targetLines));
            String targetEol = targetSource.contains("\r\n") ? "\r\n" : eol;
            List<String> block = new ArrayList<>();
            if (closeLine - 1 != openLine && !targetLines.line(closeLine - 1).isBlank()) {
                block.add("");
            }
            block.addAll(SymbolEditor.reindent(moved, indent + unit));
            int at = lineStart(targetLines, closeLine);
            return new Changes.Span(at, at, String.join(targetEol, block) + targetEol);
        }

        /** The imports the target needs for the types the moved method names. */
        private Set<String> typeImports(ModuleNode targetModule) {
            Set<ClassNode> types = new LinkedHashSet<>();
            collect(method.getReturnType(), types);
            for (ClassNode thrown : method.getExceptions()) {
                collect(thrown, types);
            }
            for (AnnotationNode annotation : method.getAnnotations()) {
                collect(annotation.getClassNode(), types);
            }
            for (Parameter parameter : method.getParameters()) {
                collect(parameter.getOriginType(), types);
                for (AnnotationNode annotation : parameter.getAnnotations()) {
                    collect(annotation.getClassNode(), types);
                }
            }
            if (method.getCode() != null) {
                method.getCode().visit(new CodeVisitorSupport() {
                    @Override
                    public void visitDeclarationExpression(DeclarationExpression expression) {
                        if (!expression.isMultipleAssignmentDeclaration()) {
                            collect(expression.getVariableExpression().getOriginType(), types);
                        }
                        super.visitDeclarationExpression(expression);
                    }

                    @Override
                    public void visitClassExpression(ClassExpression expression) {
                        collect(expression.getType(), types);
                    }

                    @Override
                    public void visitConstructorCallExpression(ConstructorCallExpression call) {
                        collect(call.getType(), types);
                        super.visitConstructorCallExpression(call);
                    }

                    @Override
                    public void visitCastExpression(CastExpression expression) {
                        collect(expression.getType(), types);
                        super.visitCastExpression(expression);
                    }

                    @Override
                    public void visitCatchStatement(CatchStatement statement) {
                        collect(statement.getVariable().getOriginType(), types);
                        super.visitCatchStatement(statement);
                    }

                    @Override
                    public void visitClosureExpression(ClosureExpression closure) {
                        if (closure.getParameters() != null) {
                            for (Parameter parameter : closure.getParameters()) {
                                collect(parameter.getOriginType(), types);
                            }
                        }
                        super.visitClosureExpression(closure);
                    }
                });
            }
            Set<String> needed = new LinkedHashSet<>();
            for (ClassNode type : types) {
                String statement = importOf(type, targetModule);
                if (statement != null) {
                    needed.add(statement);
                }
            }
            return needed;
        }

        /** The import that lets the target name {@code type} as the old document did, or {@code null}. */
        private String importOf(ClassNode type, ModuleNode targetModule) {
            if (type.equals(owner)) {
                return null; // qualifying the old class's members is handled on its own
            }
            if (!type.getName().contains(".") && !ClassHelper.isPrimitiveType(type)) {
                ImportNode imported = module.getImport(type.getName()); // a type the compile did not resolve
                return imported == null || targetModule.getImport(type.getName()) != null ? null
                        : "import " + imported.getClassName();
            }
            return TypeText.importFor(type, targetModule);
        }

        /** The static imports of the old document the moved method's calls rely on, as the target lacks them. */
        private Set<String> staticImports(ModuleNode targetModule) {
            Set<String> needed = new LinkedHashSet<>();
            if (method.getCode() == null) {
                return needed;
            }
            method.getCode().visit(new CodeVisitorSupport() {
                @Override
                public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                    ClassNode from = call.getOwnerType();
                    if (from != null && !from.equals(owner) && !owner.isDerivedFrom(from)
                            && !targetModule.getStaticImports().containsKey(call.getMethod())) {
                        needed.add("import static " + TypeText.canonical(from) + "." + call.getMethod());
                    }
                    super.visitStaticMethodCallExpression(call);
                }
            });
            return needed;
        }

        /** Adds {@code type}, its array component and its generics to {@code types}. */
        private static void collect(ClassNode type, Set<ClassNode> types) {
            if (type == null) {
                return;
            }
            if (type.isArray()) {
                collect(type.getComponentType(), types);
                return;
            }
            if (!type.isGenericsPlaceHolder() && !ClassHelper.isDynamicTyped(type)
                    && !ClassHelper.isPrimitiveType(type)) {
                types.add(type.redirect());
            }
            GenericsType[] generics = type.getGenericsTypes();
            if (generics != null) {
                for (GenericsType generic : generics) {
                    if (!generic.isPlaceholder() && !generic.isWildcard()) {
                        collect(generic.getType(), types);
                    }
                }
            }
        }
    }

    private static int lineCount(TextLines lines) {
        String text = lines.text();
        return text.endsWith("\n") ? lines.lineCount() - 1 : lines.lineCount();
    }

    private static int lineStart(TextLines lines, int line) {
        return lines.offsetOf(new Position(line, 0));
    }

    private static int contentEnd(TextLines lines, int line) {
        return lineStart(lines, line) + lines.line(line).length();
    }
}
