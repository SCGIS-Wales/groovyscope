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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.model.Diagnostic.Severity;

/**
 * SQL handling: query text built so that values become part of the SQL (GS035), and a {@code Sql} connection opened
 * with {@code newInstance} and never closed (GS036). A receiver is a {@code groovy.sql.Sql} when its type says so;
 * an untyped variable named {@code sql} or {@code db} is only presumed one, so those findings are information.
 */
final class SqlFinder {

    static final String SQL = "groovy.sql.Sql";
    static final Set<String> PRESUMED_NAMES = Set.of("sql", "db");

    private SqlFinder() {
    }

    static void find(PracticeContext context) {
        new Visitor(context).visitModule();
    }

    /** Whether {@code receiver} is a {@code Sql}: proven ({@code TRUE}), presumed from its name, or neither. */
    static Boolean sqlReceiver(Expression receiver) {
        ClassNode type = PracticeFinder.typeOf(receiver);
        if (type != null && SQL.equals(type.getName())) {
            return Boolean.TRUE;
        }
        return receiver instanceof VariableExpression variable && type == null
                && PRESUMED_NAMES.contains(variable.getName()) ? Boolean.FALSE : null;
    }

    private static boolean isNewInstance(Expression expression) {
        if (expression instanceof StaticMethodCallExpression call) {
            return "newInstance".equals(call.getMethod()) && SQL.equals(call.getOwnerType().getName());
        }
        return expression instanceof MethodCallExpression call && "newInstance".equals(call.getMethodAsString())
                && call.getObjectExpression() instanceof ClassExpression owner && SQL.equals(owner.getType().getName());
    }

    /** Whether an expression is constant text: a literal, or a final field initialised with one. */
    static boolean constantText(Expression expression) {
        if (expression instanceof ConstantExpression) {
            return true;
        }
        if (expression instanceof VariableExpression variable) {
            Variable accessed = variable.getAccessedVariable();
            FieldNode field = accessed instanceof FieldNode f ? f
                    : accessed instanceof PropertyNode property ? property.getField() : null;
            return field != null && field.isFinal() && field.getInitialExpression() instanceof ConstantExpression;
        }
        if (expression instanceof BinaryExpression binary && binary.getOperation().getType() == Types.PLUS) {
            return constantText(binary.getLeftExpression()) && constantText(binary.getRightExpression());
        }
        return false;
    }

    private static final class Visitor extends ClassCodeVisitorSupport {
        private final PracticeContext context;
        /** Local variables holding query text built unsafely, and how. */
        private final Map<Variable, String> built = new IdentityHashMap<>();

        Visitor(PracticeContext context) {
            this.context = context;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return context.module().getContext();
        }

        void visitModule() {
            for (ClassNode classNode : context.module().getClasses()) {
                visitClass(classNode);
            }
        }

        /** How {@code text} builds query text from values, or {@code null} when it binds them or is constant. */
        private String unsafe(Expression text) {
            if (text instanceof BinaryExpression binary && binary.getOperation().getType() == Types.PLUS
                    && !constantText(binary) && mentionsText(binary)) {
                return "the query text is concatenated";
            }
            if (text instanceof MethodCallExpression call && "toString".equals(call.getMethodAsString())
                    && call.getObjectExpression() instanceof GStringExpression) {
                return "toString() turns the GString into plain text";
            }
            if (text instanceof VariableExpression variable && built.containsKey(variable.getAccessedVariable())) {
                return "'" + variable.getName() + "' holds " + built.get(variable.getAccessedVariable());
            }
            return null;
        }

        private static boolean mentionsText(Expression expression) {
            if (expression instanceof ConstantExpression constant) {
                return constant.getValue() instanceof String;
            }
            if (expression instanceof GStringExpression) {
                return true;
            }
            return expression instanceof BinaryExpression binary && binary.getOperation().getType() == Types.PLUS
                    && (mentionsText(binary.getLeftExpression()) || mentionsText(binary.getRightExpression()));
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression declaration) {
            if (!declaration.isMultipleAssignmentDeclaration()) {
                VariableExpression target = declaration.getVariableExpression();
                Expression value = declaration.getRightExpression();
                String how = unsafe(value);
                if (how == null && value instanceof GStringExpression && !target.isDynamicTyped()
                        && "java.lang.String".equals(target.getOriginType().getName())) {
                    how = "an interpolated GString made plain text by its String type";
                }
                if (how != null) {
                    built.put(target, how.startsWith("the query text is ") ? "concatenated text" : how);
                }
            }
            super.visitDeclarationExpression(declaration);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            if (name != null && SecurityChecks.SQL_METHODS.contains(name)
                    && call.getArguments() instanceof TupleExpression args && !args.getExpressions().isEmpty()) {
                Boolean sql = sqlReceiver(call.getObjectExpression());
                String how = sql == null ? null : unsafe(args.getExpression(0));
                if (how != null) {
                    String message = how + ", so values become part of the SQL instead of bound parameters: pass a "
                            + "GString with unquoted ${value} placeholders, or ? placeholders and a parameter list";
                    if (sql) {
                        context.report("GS035", args.getExpression(0), message);
                    } else {
                        context.reportAtMost(Severity.INFORMATION, "GS035", args.getExpression(0),
                                message + " (the receiver is presumed a Sql from its name)");
                    }
                }
            }
            if (isNewInstance(call.getObjectExpression())) {
                context.report("GS036", call.getObjectExpression(), "the connection is used once and never closed; "
                        + "use Sql.withInstance(...) { sql -> ... }, or close it in a finally block");
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            built.clear();
            if (node.getCode() != null) {
                checkConnections(node);
            }
            super.visitConstructorOrMethod(node, isConstructor);
        }

        /** GS036: a local assigned {@code Sql.newInstance(...)} that is neither closed nor handed on. */
        private void checkConnections(MethodNode method) {
            Map<Variable, DeclarationExpression> opened = new IdentityHashMap<>();
            method.getCode().visit(new CodeVisitorSupport() {
                @Override
                public void visitDeclarationExpression(DeclarationExpression declaration) {
                    if (!declaration.isMultipleAssignmentDeclaration() && isNewInstance(declaration.getRightExpression())) {
                        opened.put(declaration.getVariableExpression(), declaration);
                    }
                    super.visitDeclarationExpression(declaration);
                }

                @Override
                public void visitClosureExpression(ClosureExpression closure) {
                    // a closure's own locals are checked with the closure's code, not here
                }
            });
            for (Map.Entry<Variable, DeclarationExpression> entry : opened.entrySet()) {
                if (!handled(method, entry.getKey())) {
                    context.report("GS036", entry.getValue().getRightExpression(), "'" + entry.getKey().getName()
                            + "' is opened here and never closed; use Sql.withInstance(...) { sql -> ... }, or close "
                            + "it in a finally block");
                }
            }
        }

        /** Whether a connection is closed, returned, passed on or stored anywhere in the method. */
        private static boolean handled(MethodNode method, Variable connection) {
            boolean[] handled = {false};
            method.getCode().visit(new CodeVisitorSupport() {
                private boolean refersTo(Expression expression) {
                    return expression instanceof VariableExpression variable
                            && variable.getAccessedVariable() == connection;
                }

                @Override
                public void visitMethodCallExpression(MethodCallExpression call) {
                    if (refersTo(call.getObjectExpression()) && "close".equals(call.getMethodAsString())) {
                        handled[0] = true;
                    }
                    if (call.getArguments() instanceof ArgumentListExpression args
                            && args.getExpressions().stream().anyMatch(this::refersTo)) {
                        handled[0] = true;
                    }
                    super.visitMethodCallExpression(call);
                }

                @Override
                public void visitReturnStatement(ReturnStatement statement) {
                    handled[0] |= refersTo(statement.getExpression());
                    super.visitReturnStatement(statement);
                }

                @Override
                public void visitBinaryExpression(BinaryExpression expression) {
                    handled[0] |= expression.getOperation().getType() == Types.ASSIGN
                            && refersTo(expression.getRightExpression());
                    super.visitBinaryExpression(expression);
                }
            });
            List<org.codehaus.groovy.ast.stmt.Statement> statements =
                    method.getCode() instanceof org.codehaus.groovy.ast.stmt.BlockStatement block
                            ? block.getStatements() : List.of(method.getCode());
            if (!statements.isEmpty() && statements.get(statements.size() - 1) instanceof ExpressionStatement last
                    && last.getExpression() instanceof VariableExpression variable
                    && variable.getAccessedVariable() == connection) {
                handled[0] = true; // the implicit return hands it to the caller
            }
            return handled[0];
        }
    }
}
