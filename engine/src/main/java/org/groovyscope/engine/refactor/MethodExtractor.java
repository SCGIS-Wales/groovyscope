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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.BreakStatement;
import org.codehaus.groovy.ast.stmt.CaseStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ContinueStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.LoopingStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.SwitchStatement;
import org.codehaus.groovy.ast.stmt.SynchronizedStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.search.SymbolEditor;
import org.groovyscope.engine.services.Snapshot;

/**
 * Turns whole statements of one block into a private method placed after the enclosing one, and replaces them by a
 * call. Parameters and the returned value come from the data flow: the locals the statements use that are declared
 * before them, and the one local they write that is read after them. Refuses what a call cannot reproduce: part of a
 * statement, two values read after the selection, a return from its middle, a jump out of a loop around it.
 */
public final class MethodExtractor {

    /** The name a request without one gets (with a number when the class has a method of that name). */
    public static final String DEFAULT_NAME = "extracted";

    private MethodExtractor() {
    }

    /**
     * An extraction.
     *
     * @param startLine first selected line, 1-based
     * @param endLine last selected line, 1-based and inclusive
     * @param name the new method's name, or {@code null} for {@value #DEFAULT_NAME} (numbered when taken)
     */
    public record Request(int startLine, int endLine, String name) {
    }

    /**
     * One parameter of the new method.
     *
     * @param name its name, the local's
     * @param type its type as written ({@code def} when unknown)
     */
    public record NewParameter(String name, String type) {
    }

    /**
     * What the extraction does, or why it is refused.
     *
     * @param refused the reason nothing is changed, or {@code null}
     * @param changes the edits, all in the one document
     * @param method the new method's signature
     * @param parameters its parameters, in order of first use
     * @param returns the local it returns, or {@code null}
     * @param isStatic whether it is static
     */
    public record Result(String refused, Changes changes, String method, List<NewParameter> parameters,
            String returns, boolean isStatic) {

        public Result {
            parameters = List.copyOf(parameters);
        }

        static Result refusal(String reason) {
            return new Result(reason, Changes.none(), null, List.of(), null, false);
        }
    }

    /**
     * Computes the extraction of {@code request}'s lines of the document {@code uri} of {@code snapshot}.
     *
     * @throws IllegalArgumentException when the document is not compiled, the lines are out of range or the name
     *                                  is not an identifier
     */
    public static Result extract(Snapshot snapshot, String uri, Request request) {
        ModuleNode module = snapshot.result().modules().get(uri);
        String source = snapshot.result().sources().get(uri);
        if (module == null || source == null) {
            throw new IllegalArgumentException(uri + " is not a compiled Groovy document of the workspace");
        }
        if (request.name() != null && !SourceText.identifier(request.name())) {
            throw new IllegalArgumentException("method name " + request.name() + " is not an identifier");
        }
        TextLines lines = new TextLines(source);
        int count = source.endsWith("\n") ? lines.lineCount() - 1 : lines.lineCount();
        if (request.startLine() < 1 || request.endLine() < request.startLine() || request.endLine() > count) {
            throw new IllegalArgumentException("startLine and endLine must be 1-based lines of the document (1 to "
                    + count + "), startLine not after endLine");
        }
        return new Work(snapshot, uri, module, source, lines, request).run();
    }

    /** The selected statements, the statements of the block they belong to, and whether it is the method body. */
    private record Selection(List<Statement> statements, List<Statement> block, boolean methodBody) {

        Statement last() {
            return statements.get(statements.size() - 1);
        }
    }

    /** One use of a local: where, and whether it reads it, writes it or declares it. */
    private record Use(Variable variable, int offset, boolean read, boolean write) {
    }

    /** One extraction being computed. */
    private static final class Work {

        private final Snapshot snapshot;
        private final String uri;
        private final ModuleNode module;
        private final String source;
        private final TextLines lines;
        private final Request request;
        private final int start;
        private final int end;
        private final String eol;

        Work(Snapshot snapshot, String uri, ModuleNode module, String source, TextLines lines, Request request) {
            this.snapshot = snapshot;
            this.uri = uri;
            this.module = module;
            this.source = source;
            this.lines = lines;
            this.request = request;
            this.start = request.startLine();
            this.end = request.endLine();
            this.eol = source.contains("\r\n") ? "\r\n" : "\n";
        }

        Result run() {
            MethodNode enclosing = enclosingMethod();
            if (enclosing == null) {
                return Result.refusal("lines " + start + " to " + end + " are not inside a method body; extract "
                        + "method takes statements of a method");
            }
            Object found = select(((BlockStatement) enclosing.getCode()).getStatements(), true);
            if (found instanceof String reason) {
                return Result.refusal(reason);
            }
            Selection selection = (Selection) found;
            Flow flow = new Flow();
            for (Statement statement : selection.statements()) {
                statement.visit(flow);
            }
            boolean endsMethod = selection.methodBody()
                    && selection.last() == selection.block().get(selection.block().size() - 1);
            for (ReturnStatement returned : flow.returns) {
                if (returned != selection.last() || !endsMethod) {
                    return Result.refusal("line " + returned.getLineNumber() + " returns from inside the selection; "
                            + "a return is extracted only as the last statement of a selection that ends the method");
                }
            }
            if (!flow.jumps.isEmpty()) {
                return Result.refusal("line " + flow.jumps.get(0).getLineNumber() + " leaves a loop or switch "
                        + "around the selection (break or continue); extract method refuses it");
            }
            String enclosingType = returnType(enclosing);
            boolean returning = endsMethod && !"void".equals(enclosingType);
            int from = lines.offsetOf(new Position(start - 1, 0));
            int to = contentEnd(end - 1);
            Uses uses = new Uses();
            enclosing.getCode().visit(uses);
            List<Variable> inputs = new ArrayList<>();
            List<Variable> written = new ArrayList<>();
            for (Use use : uses.list) {
                if (use.offset() < from || use.offset() >= to) {
                    continue;
                }
                if (declaredBefore(use.variable(), enclosing, from) && !inputs.contains(use.variable())) {
                    inputs.add(use.variable());
                }
                if (use.write() && !written.contains(use.variable())) {
                    written.add(use.variable());
                }
            }
            List<Variable> outputs = new ArrayList<>();
            for (Variable variable : written) {
                if (readAfter(variable, uses, enclosing, from, to)) {
                    outputs.add(variable);
                }
            }
            if (outputs.size() > 1) {
                return Result.refusal("the selection writes " + String.join(", ", outputs.stream()
                        .map(Variable::getName).toList()) + ", which are read after it; extract method returns one "
                        + "value");
            }
            Variable output = outputs.isEmpty() ? null : outputs.get(0);
            ClassNode owner = enclosing.getDeclaringClass();
            boolean isStatic = enclosing.isStatic() || InstanceState.in(owner, selection.statements()).isEmpty();
            String name = request.name() != null ? request.name() : freeName(owner);
            for (MethodNode existing : owner.getMethods(name)) {
                if (existing.getParameters().length == inputs.size()) {
                    return Result.refusal(TypeText.simpleName(owner) + " already has a method " + name + " with "
                            + inputs.size() + " parameter(s) (line " + existing.getLineNumber() + "); choose another "
                            + "name");
                }
            }
            List<NewParameter> parameters = new ArrayList<>();
            for (Variable input : inputs) {
                parameters.add(new NewParameter(input.getName(), typeOf(input, uses)));
            }
            String type = returning ? enclosingType : output != null ? typeOf(output, uses) : "void";
            String signature = "private " + (isStatic ? "static " : "") + type + " " + name + "("
                    + String.join(", ", parameters.stream().map(p -> p.type() + " " + p.name()).toList()) + ")";
            String call = name + "(" + String.join(", ", inputs.stream().map(Variable::getName).toList()) + ")";
            if (returning) {
                call = "return " + call;
            } else if (output != null && !declaredBefore(output, enclosing, from)) {
                call = declarationPrefix(output, uses) + " " + output.getName() + " = " + call;
            } else if (output != null) {
                call = output.getName() + " = " + call;
            }
            int last = enclosing.getLastLineNumber() - 1;
            String after = lines.line(last).substring(Math.min(lines.line(last).length(),
                    Math.max(0, enclosing.getLastColumnNumber() - 1))).strip();
            if (!after.isEmpty() && !after.startsWith("//")) {
                return Result.refusal("the method " + enclosing.getName() + " shares its last line with other code; "
                        + "put its closing brace on a line of its own first");
            }
            String indent = SourceText.indent(lines.line(enclosing.getLineNumber() - 1));
            String unit = SymbolEditor.indentUnit(lines, lineCount());
            List<String> method = new ArrayList<>();
            method.add(indent + signature + " {");
            method.addAll(SymbolEditor.reindent(source.substring(from, to), indent + unit));
            if (output != null && !returning) {
                method.add(indent + unit + "return " + output.getName());
            }
            method.add(indent + "}");
            String next = last + 1 < lineCount() ? lines.line(last + 1).strip() : "}";
            String inserted = eol + eol + String.join(eol, method) + (next.isEmpty() || next.startsWith("}") ? ""
                    : eol);
            String callIndent = SourceText.indent(lines.line(selection.statements().get(0).getLineNumber() - 1));
            Changes changes = new Changes.Builder()
                    .replace(uri, source, from, to, callIndent + call)
                    .insert(uri, source, contentEnd(last), inserted)
                    .build();
            return new Result(null, changes, signature, parameters, output == null ? null : output.getName(),
                    isStatic);
        }

        private int lineCount() {
            return source.endsWith("\n") ? lines.lineCount() - 1 : lines.lineCount();
        }

        private int contentEnd(int line) {
            return lines.offsetOf(new Position(line, 0)) + lines.line(line).length();
        }

        /** The innermost method or constructor whose body holds the selected lines, or {@code null}. */
        private MethodNode enclosingMethod() {
            MethodNode best = null;
            for (ClassNode type : module.getClasses()) {
                List<MethodNode> methods = new ArrayList<>(type.getMethods());
                methods.addAll(type.getDeclaredConstructors());
                for (MethodNode method : methods) {
                    Statement code = method.getCode();
                    if (method.getLineNumber() < 1 || !(code instanceof BlockStatement) || code.getLineNumber() < 1
                            || code.getLineNumber() > start || end > code.getLastLineNumber()) {
                        continue;
                    }
                    if (best == null || code.getLastLineNumber() - code.getLineNumber()
                            < best.getCode().getLastLineNumber() - best.getCode().getLineNumber()) {
                        best = method;
                    }
                }
            }
            return best;
        }

        /** The selection within {@code block}, or the reason the lines are not whole statements of one block. */
        private Object select(List<Statement> block, boolean methodBody) {
            List<Statement> hit = new ArrayList<>();
            for (Statement statement : block) {
                if (statement.getLineNumber() >= 1 && statement.getLineNumber() <= end
                        && statement.getLastLineNumber() >= start) {
                    hit.add(statement);
                }
            }
            if (hit.isEmpty()) {
                return "lines " + start + " to " + end + " hold no statement";
            }
            List<Statement> partial = hit.stream()
                    .filter(statement -> statement.getLineNumber() < start || statement.getLastLineNumber() > end)
                    .toList();
            if (partial.isEmpty()) {
                return whole(hit, block, methodBody);
            }
            Statement cut = partial.get(0);
            if (hit.size() == 1) {
                ClosureExpression closure = closureAround(cut);
                if (closure != null) {
                    return "lines " + start + " to " + end + " are inside a closure (line " + closure.getLineNumber()
                            + "); extract method takes whole statements of a method body";
                }
                for (Statement child : children(cut)) {
                    boolean braces = child instanceof BlockStatement; // the lines of its braces are not its statements
                    if (child.getLineNumber() >= 1 && (braces ? child.getLineNumber() < start
                            && end < child.getLastLineNumber() : child.getLineNumber() <= start
                            && end <= child.getLastLineNumber())) {
                        return select(child instanceof BlockStatement inner ? inner.getStatements() : List.of(child),
                                false);
                    }
                }
            }
            return "lines " + start + " to " + end + " cut the statement at lines " + cut.getLineNumber() + " to "
                    + cut.getLastLineNumber() + "; select whole statements of one block";
        }

        /** The selection of whole statements, or the reason other code shares the selected lines. */
        private Object whole(List<Statement> hit, List<Statement> block, boolean methodBody) {
            Statement first = hit.get(0);
            Statement last = hit.get(hit.size() - 1);
            for (int line = start; line < first.getLineNumber(); line++) {
                if (!SourceText.blankOrComment(lines.line(line - 1))) {
                    return "line " + line + " holds code that is not a whole statement; select whole statements of "
                            + "one block";
                }
            }
            for (int line = last.getLastLineNumber() + 1; line <= end; line++) {
                if (!SourceText.blankOrComment(lines.line(line - 1))) {
                    return "line " + line + " holds code that is not a whole statement; select whole statements of "
                            + "one block";
                }
            }
            String before = lines.line(first.getLineNumber() - 1);
            before = before.substring(0, Math.min(before.length(), Math.max(0, first.getColumnNumber() - 1)));
            String rest = lines.line(last.getLastLineNumber() - 1);
            rest = rest.substring(Math.min(rest.length(), Math.max(0, last.getLastColumnNumber() - 1))).strip();
            if (rest.startsWith(";")) {
                rest = rest.substring(1).strip();
            }
            if (!before.isBlank() || !rest.isEmpty() && !rest.startsWith("//")) {
                return "line " + (before.isBlank() ? last.getLastLineNumber() : first.getLineNumber())
                        + " holds code besides the selected statements; select whole statements of one block";
            }
            return new Selection(hit, block, methodBody);
        }

        /** The closure inside {@code statement} whose body holds the selected lines, or {@code null}. */
        private ClosureExpression closureAround(Statement statement) {
            ClosureExpression[] found = new ClosureExpression[1];
            statement.visit(new CodeVisitorSupport() {
                @Override
                public void visitClosureExpression(ClosureExpression closure) {
                    if (found[0] == null && closure.getLineNumber() >= 1 && closure.getLineNumber() <= start
                            && end <= closure.getLastLineNumber()) {
                        found[0] = closure;
                    }
                }
            });
            return found[0];
        }

        /** The type the method declares it returns, as written ({@code def} when only modifiers stand there). */
        private String returnType(MethodNode method) {
            if (method instanceof ConstructorNode) {
                return "void";
            }
            if (method.isVoidMethod()) {
                return "void";
            }
            int at = SourceText.start(lines, method);
            int open = method.getCode() == null ? -1 : SourceText.start(lines, method.getCode());
            if (at >= 0 && open > at) {
                java.util.regex.Matcher matcher = Pattern.compile("\\b" + Pattern.quote(method.getName()) + "\\s*\\(")
                        .matcher(source.substring(at, open));
                if (matcher.find()) {
                    int[] type = SourceText.typeBefore(source, at + matcher.start());
                    if (type != null) {
                        return source.substring(type[0], type[1]);
                    }
                }
            }
            return "def";
        }

        /** Whether {@code variable} is a local or parameter declared before {@code from} in the method. */
        private boolean declaredBefore(Variable variable, MethodNode method, int from) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter == variable) {
                    return true;
                }
            }
            int at = declarationOffset(variable);
            return at >= 0 && at < from;
        }

        private int declarationOffset(Variable variable) {
            if (variable instanceof Parameter parameter) {
                return SourceText.start(lines, parameter);
            }
            if (variable instanceof VariableExpression declared) {
                return SourceText.start(lines, declared);
            }
            return -1;
        }

        /**
         * Whether {@code variable} is read after the selection: later in the method, or anywhere in a loop around the
         * selection that it is declared outside of (the next iteration reads it).
         */
        private boolean readAfter(Variable variable, Uses uses, MethodNode method, int from, int to) {
            int declared = declarationOffset(variable);
            for (Use use : uses.list) {
                if (use.variable() == variable && use.read() && use.offset() >= to) {
                    return true;
                }
            }
            for (LoopingStatement loop : uses.loops) {
                int loopStart = SourceText.start(lines, (Statement) loop);
                int loopEnd = SourceText.end(lines, (Statement) loop);
                if (loopStart < 0 || loopStart > from || loopEnd < to || declared >= loopStart && declared < loopEnd
                        || declared < 0 && !isParameter(variable, method)) {
                    continue;
                }
                for (Use use : uses.list) {
                    if (use.variable() == variable && use.read() && use.offset() >= loopStart
                            && use.offset() < loopEnd) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean isParameter(Variable variable, MethodNode method) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter == variable) {
                    return true;
                }
            }
            return false;
        }

        /** The type of a local as written where it is declared, else as inferred, else {@code def}. */
        private String typeOf(Variable variable, Uses uses) {
            String written = null;
            if (variable instanceof Parameter parameter) {
                String text = SourceText.of(lines, parameter);
                if (text != null) {
                    int equals = parameter.hasInitialExpression() ? text.indexOf('=') : -1;
                    String head = (equals >= 0 ? text.substring(0, equals) : text).strip();
                    if (head.endsWith(parameter.getName())) {
                        written = clean(head.substring(0, head.length() - parameter.getName().length()));
                    }
                }
                if (blankType(written) && !parameter.isDynamicTyped()) {
                    return TypeText.render(parameter.getOriginType(), module);
                }
            } else if (variable instanceof VariableExpression declared) {
                String prefix = declarationPrefix(declared, uses);
                written = clean(prefix);
                if (blankType(written)) {
                    ClassNode inferred = snapshot.resolver().typeOfVariable(declared);
                    if (inferred != null && !ClassHelper.isObjectType(inferred)) {
                        return TypeText.render(inferred, module);
                    }
                }
            }
            return blankType(written) ? "def" : written;
        }

        /**
         * What stands before a local's name where it is declared ({@code final List<String>}, {@code def}), or
         * {@code def} when it is declared in a multiple assignment or not found.
         */
        private String declarationPrefix(Variable variable, Uses uses) {
            DeclarationExpression declaration = uses.declarations.get(variable);
            if (declaration == null || declaration.isMultipleAssignmentDeclaration()
                    || !(variable instanceof VariableExpression declared)) {
                return "def";
            }
            int at = SourceText.start(lines, declaration);
            int name = SourceText.start(lines, declared);
            if (at < 0 || name <= at) {
                return "def";
            }
            String prefix = source.substring(at, name).strip();
            return prefix.isEmpty() ? "def" : prefix;
        }

        private static String clean(String written) {
            String text = SourceText.LEADING_ANNOTATIONS.matcher(written.strip()).replaceFirst("").strip();
            while (text.startsWith("final ")) {
                text = text.substring("final ".length()).strip();
            }
            return text.equals("final") ? "" : text;
        }

        private static boolean blankType(String written) {
            return written == null || written.isEmpty() || written.equals("def") || written.equals("var");
        }

        /** {@value #DEFAULT_NAME}, numbered from 2 while the class already has a method of that name. */
        private static String freeName(ClassNode owner) {
            String name = DEFAULT_NAME;
            for (int i = 2; !owner.getMethods(name).isEmpty(); i++) {
                name = DEFAULT_NAME + i;
            }
            return name;
        }

        /** Every local use in the method, in source order, with the loops and declarations met. */
        private final class Uses extends CodeVisitorSupport {

            private final List<Use> list = new ArrayList<>();
            private final List<LoopingStatement> loops = new ArrayList<>();
            private final Map<Variable, DeclarationExpression> declarations = new IdentityHashMap<>();

            private void add(VariableExpression expression, boolean read, boolean write) {
                Variable variable = expression.getAccessedVariable() != null ? expression.getAccessedVariable()
                        : expression;
                if (!(variable instanceof Parameter) && !(variable instanceof VariableExpression)) {
                    return; // fields, properties and dynamic names are not locals
                }
                if ("this".equals(expression.getName()) || "super".equals(expression.getName())) {
                    return;
                }
                int offset = SourceText.start(lines, expression);
                if (offset >= 0) {
                    list.add(new Use(variable, offset, read, write));
                }
            }

            @Override
            public void visitDeclarationExpression(DeclarationExpression expression) {
                if (expression.isMultipleAssignmentDeclaration()) {
                    for (Expression element : expression.getTupleExpression().getExpressions()) {
                        if (element instanceof VariableExpression variable) {
                            declarations.put(variable, expression);
                            add(variable, false, true);
                        }
                    }
                } else {
                    VariableExpression variable = expression.getVariableExpression();
                    declarations.put(variable, expression);
                    add(variable, false, true);
                }
                expression.getRightExpression().visit(this);
            }

            @Override
            public void visitBinaryExpression(BinaryExpression expression) {
                int type = expression.getOperation().getType();
                if (!Types.ofType(type, Types.ASSIGNMENT_OPERATOR)) {
                    super.visitBinaryExpression(expression);
                    return;
                }
                boolean compound = type != Types.ASSIGN;
                expression.getRightExpression().visit(this);
                Expression left = expression.getLeftExpression();
                if (left instanceof VariableExpression variable) {
                    add(variable, compound, true);
                } else if (left instanceof TupleExpression tuple) {
                    for (Expression element : tuple.getExpressions()) {
                        if (element instanceof VariableExpression variable) {
                            add(variable, false, true);
                        } else {
                            element.visit(this);
                        }
                    }
                } else {
                    left.visit(this);
                }
            }

            @Override
            public void visitPostfixExpression(PostfixExpression expression) {
                if (expression.getExpression() instanceof VariableExpression variable) {
                    add(variable, true, true);
                } else {
                    super.visitPostfixExpression(expression);
                }
            }

            @Override
            public void visitPrefixExpression(PrefixExpression expression) {
                if (expression.getExpression() instanceof VariableExpression variable) {
                    add(variable, true, true);
                } else {
                    super.visitPrefixExpression(expression);
                }
            }

            @Override
            public void visitVariableExpression(VariableExpression expression) {
                add(expression, true, false);
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (call.isImplicitThis()) {
                    call.getMethod().visit(this);
                    call.getArguments().visit(this);
                } else {
                    super.visitMethodCallExpression(call);
                }
            }

            @Override
            public void visitPropertyExpression(PropertyExpression expression) {
                if (expression.isImplicitThis()) {
                    expression.getProperty().visit(this);
                } else {
                    super.visitPropertyExpression(expression);
                }
            }

            @Override
            public void visitForLoop(ForStatement loop) {
                loops.add(loop);
                super.visitForLoop(loop);
            }

            @Override
            public void visitWhileLoop(WhileStatement loop) {
                loops.add(loop);
                super.visitWhileLoop(loop);
            }

            @Override
            public void visitDoWhileLoop(DoWhileStatement loop) {
                loops.add(loop);
                super.visitDoWhileLoop(loop);
            }
        }
    }

    /** The returns and the jumps out of the selection's statements, closures left out. */
    private static final class Flow extends CodeVisitorSupport {

        private final List<ReturnStatement> returns = new ArrayList<>();
        private final List<Statement> jumps = new ArrayList<>();
        private int loops;
        private int switches;

        @Override
        public void visitClosureExpression(ClosureExpression closure) {
            // a return in a closure returns from the closure, and a jump cannot leave it
        }

        @Override
        public void visitReturnStatement(ReturnStatement statement) {
            returns.add(statement);
            super.visitReturnStatement(statement);
        }

        @Override
        public void visitBreakStatement(BreakStatement statement) {
            if (statement.getLabel() != null || loops == 0 && switches == 0) {
                jumps.add(statement);
            }
        }

        @Override
        public void visitContinueStatement(ContinueStatement statement) {
            if (statement.getLabel() != null || loops == 0) {
                jumps.add(statement);
            }
        }

        @Override
        public void visitForLoop(ForStatement loop) {
            loops++;
            super.visitForLoop(loop);
            loops--;
        }

        @Override
        public void visitWhileLoop(WhileStatement loop) {
            loops++;
            super.visitWhileLoop(loop);
            loops--;
        }

        @Override
        public void visitDoWhileLoop(DoWhileStatement loop) {
            loops++;
            super.visitDoWhileLoop(loop);
            loops--;
        }

        @Override
        public void visitSwitch(SwitchStatement statement) {
            switches++;
            super.visitSwitch(statement);
            switches--;
        }
    }

    /** The statements nested directly in {@code statement}: the blocks a selection may lie in. */
    static List<Statement> children(Statement statement) {
        List<Statement> children = new ArrayList<>();
        if (statement instanceof BlockStatement block) {
            children.add(block);
        } else if (statement instanceof IfStatement branch) {
            children.add(branch.getIfBlock());
            children.add(branch.getElseBlock());
        } else if (statement instanceof LoopingStatement loop) {
            children.add(loop.getLoopBlock());
        } else if (statement instanceof TryCatchStatement attempt) {
            children.add(attempt.getTryStatement());
            for (CatchStatement handler : attempt.getCatchStatements()) {
                children.add(handler.getCode());
            }
            children.add(attempt.getFinallyStatement());
        } else if (statement instanceof SwitchStatement choice) {
            for (CaseStatement option : choice.getCaseStatements()) {
                children.add(option.getCode());
            }
            children.add(choice.getDefaultStatement());
        } else if (statement instanceof SynchronizedStatement guarded) {
            children.add(guarded.getCode());
        }
        children.removeIf(child -> child == null || child instanceof EmptyStatement);
        return children;
    }
}
