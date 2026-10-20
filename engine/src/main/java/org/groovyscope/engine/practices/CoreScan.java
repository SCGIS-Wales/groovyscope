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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

/**
 * One walk of a module for GS001 to GS012, in source order. The walk keeps what several checks need to know (the
 * enclosing class and method, the Spock block, which expressions are narrowed, parsed or caught) and hands each node
 * to the group that checks it: {@link NumberChecks}, {@link TruthChecks}, {@link LoggingChecks},
 * {@link SecurityChecks}, {@link VersionChecks} and {@link SpockWithCheck}.
 */
final class CoreScan extends ClassCodeVisitorSupport {

    final PracticeContext context;
    /** Elvis expressions passed straight to an empty-rejecting parser (identity, not equality). */
    final Set<Expression> parsed = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Divisions whose result is narrowed to an integral type (truncated anyway): not GS001 findings. */
    final Set<Expression> narrowed = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Catch parameters, which may hold any Throwable. */
    final Set<Object> caught = Collections.newSetFromMap(new IdentityHashMap<>());
    MethodNode currentMethod;
    ClassNode currentClass;
    boolean inSpock;
    String block = "";

    CoreScan(PracticeContext context) {
        this.context = context;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return context.module().getContext();
    }

    void visitModule() {
        for (ImportNode importNode : context.module().getImports()) {
            visitImport(importNode);
        }
        for (ImportNode importNode : context.module().getStarImports()) {
            visitImport(importNode);
        }
        for (ClassNode classNode : context.module().getClasses()) {
            inSpock = PracticeContext.isSpecification(classNode);
            currentClass = classNode;
            visitClass(classNode);
        }
        currentClass = null;
    }

    private void visitImport(ImportNode importNode) {
        visitAnnotations(importNode);
        VersionChecks.importNode(this, importNode);
    }

    @Override
    public void visitAnnotations(AnnotatedNode node) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            String name = annotation.getClassNode().getNameWithoutPackage();
            SecurityChecks.annotation(this, annotation, name);
            VersionChecks.annotation(this, annotation, name);
        }
        super.visitAnnotations(node);
    }

    @Override
    public void visitBlockStatement(BlockStatement statements) {
        String saved = this.block;
        for (Statement statement : statements.getStatements()) {
            List<String> labels = statement.getStatementLabels();
            if (inSpock && labels != null && !labels.isEmpty() && !labels.contains("and")) {
                this.block = labels.get(0);
            }
            statement.visit(this);
        }
        this.block = saved;
    }

    @Override
    public void visitExpressionStatement(ExpressionStatement statement) {
        SpockWithCheck.expressionStatement(this, statement);
        super.visitExpressionStatement(statement);
    }

    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        int op = expression.getOperation().getType();
        NumberChecks.binary(this, expression);
        if (op == Types.ASSIGN && !(expression instanceof DeclarationExpression)) {
            TruthChecks.primitiveSafeNavigation(this, expression.getLeftExpression(), expression.getRightExpression());
            SecurityChecks.docTypeAssignment(this, expression);
        }
        super.visitBinaryExpression(expression);
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expression) {
        NumberChecks.declaration(this, expression);
        if (!expression.isMultipleAssignmentDeclaration()) {
            TruthChecks.primitiveSafeNavigation(this, expression.getVariableExpression(),
                    expression.getRightExpression());
            VersionChecks.declaration(this, expression);
        }
        super.visitDeclarationExpression(expression);
    }

    @Override
    public void visitCastExpression(CastExpression expression) {
        NumberChecks.cast(this, expression);
        super.visitCastExpression(expression);
    }

    @Override
    public void visitReturnStatement(ReturnStatement statement) {
        NumberChecks.returned(this, statement);
        TruthChecks.returned(this, statement);
        super.visitReturnStatement(statement);
    }

    @Override
    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        MethodNode saved = currentMethod;
        currentMethod = node;
        if (!isConstructor) {
            TruthChecks.implicitReturn(this, node);
        }
        super.visitConstructorOrMethod(node, isConstructor);
        currentMethod = saved;
    }

    @Override
    public void visitTernaryExpression(TernaryExpression expression) {
        TruthChecks.ternary(this, expression);
        super.visitTernaryExpression(expression);
    }

    @Override
    public void visitIfElse(IfStatement statement) {
        TruthChecks.truthiness(this, statement.getBooleanExpression());
        super.visitIfElse(statement);
    }

    @Override
    public void visitWhileLoop(WhileStatement statement) {
        TruthChecks.truthiness(this, statement.getBooleanExpression());
        super.visitWhileLoop(statement);
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        String name = call.getMethodAsString();
        if (name != null && TruthChecks.EMPTY_REJECTING_PARSERS.contains(name)) {
            TruthChecks.parserArguments(this, call.getArguments());
        }
        if (name != null) {
            LoggingChecks.call(this, call, name);
            SecurityChecks.evaluation(this, call, name);
            SecurityChecks.execute(this, call, name);
            SecurityChecks.sql(this, call, name);
            SecurityChecks.xmlFeature(this, call, name);
            VersionChecks.copyWith(this, call, name);
        }
        super.visitMethodCallExpression(call);
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        if (TruthChecks.EMPTY_REJECTING_PARSERS.contains(call.getMethod())) {
            TruthChecks.parserArguments(this, call.getArguments());
        }
        SecurityChecks.staticEvaluation(this, call);
        super.visitStaticMethodCallExpression(call);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        SecurityChecks.xmlParser(this, call);
        super.visitConstructorCallExpression(call);
    }

    @Override
    public void visitCatchStatement(CatchStatement statement) {
        caught.add(statement.getVariable());
        super.visitCatchStatement(statement);
    }
}
