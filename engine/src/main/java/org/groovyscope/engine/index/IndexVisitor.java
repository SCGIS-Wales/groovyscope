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
package org.groovyscope.engine.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PackageNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.groovyscope.engine.index.NodeEntry.Role;
import org.groovyscope.engine.model.Range;

/** Walks one module and records every positioned node with its role and enclosing scope. */
final class IndexVisitor extends ClassCodeVisitorSupport {

    private final String uri;
    private final Range fullRange;
    private final List<NodeEntry> entries = new ArrayList<>();
    private final Set<ASTNode> recorded = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<ASTNode> declaredVariables = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Receivers the parser invented for calls and property reads with no explicit object. */
    private final Set<ASTNode> implicitReceivers = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private ClassNode currentClass;
    private MethodNode currentMethod;

    /**
     * @param uri the document being indexed
     * @param fullRange the whole document, used as the extent of a script class (which has no position of its own)
     */
    IndexVisitor(String uri, Range fullRange) {
        this.uri = uri;
        this.fullRange = fullRange;
    }

    /** Generated trait helper classes carry the trait's position but are not user declarations. */
    static boolean isGenerated(ClassNode classNode) {
        return classNode.getName().contains("$Trait$");
    }

    List<NodeEntry> entries() {
        return entries;
    }

    void index(ModuleNode module) {
        PackageNode packageNode = module.getPackage();
        if (packageNode != null) {
            record(packageNode, Role.DECLARATION, null);
        }
        visitImports(module);
        for (ClassNode classNode : module.getClasses()) {
            if (!isGenerated(classNode)) {
                visitClass(classNode);
            }
        }
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return null;
    }

    @Override
    public void visitImports(ModuleNode node) {
        for (ImportNode importNode : node.getImports()) {
            record(importNode, Role.DECLARATION, null);
            recordType(importNode.getType(), importNode);
        }
        for (ImportNode importNode : node.getStaticImports().values()) {
            record(importNode, Role.DECLARATION, null);
            recordType(importNode.getType(), importNode);
        }
        for (ImportNode importNode : node.getStaticStarImports().values()) {
            record(importNode, Role.DECLARATION, null);
            recordType(importNode.getType(), importNode);
        }
        for (ImportNode importNode : node.getStarImports()) {
            record(importNode, Role.DECLARATION, null);
        }
    }

    @Override
    public void visitClass(ClassNode node) {
        ClassNode previousClass = currentClass;
        MethodNode previousMethod = currentMethod;
        currentClass = node;
        currentMethod = null;
        if (node.isScript() && AstRanges.rangeOf(node) == null) {
            if (recorded.add(node)) {
                entries.add(new NodeEntry(uri, node, Role.DECLARATION, fullRange, null, null, null));
            }
        } else if (node.isScript()) {
            // A script's own position only spans its first statement; the whole file is its extent.
            if (recorded.add(node)) {
                entries.add(new NodeEntry(uri, node, Role.DECLARATION, fullRange, null, null, null));
            }
        } else {
            record(node, Role.DECLARATION, null);
        }
        recordType(node.getUnresolvedSuperClass(false), null);
        for (ClassNode iface : node.getUnresolvedInterfaces(false)) {
            recordType(iface, null);
        }
        recordGenerics(node.getGenericsTypes());
        try {
            super.visitClass(node);
        } finally {
            currentClass = previousClass;
            currentMethod = previousMethod;
        }
    }

    @Override
    public void visitAnnotations(AnnotatedNode node) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            Range range = AstRanges.rangeOf(annotation);
            if (range != null) {
                ClassNode type = annotation.getClassNode();
                if (type != null && recorded.add(annotation)) {
                    entries.add(new NodeEntry(uri, type, Role.TYPE_USAGE, range, annotation, currentClass, currentMethod));
                }
            }
        }
        super.visitAnnotations(node);
    }

    @Override
    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        MethodNode previous = currentMethod;
        currentMethod = node;
        record(node, Role.DECLARATION, currentClass);
        if (!isConstructor) {
            recordType(node.getReturnType(), null);
        }
        recordGenerics(node.getGenericsTypes());
        for (Parameter parameter : node.getParameters()) {
            recordParameter(parameter);
        }
        try {
            super.visitConstructorOrMethod(node, isConstructor);
        } finally {
            currentMethod = previous;
        }
    }

    @Override
    public void visitField(FieldNode node) {
        if (currentClass == null || currentClass.getProperty(node.getName()) == null) {
            record(node, Role.DECLARATION, currentClass);
            recordType(node.getOriginType(), null);
        }
        super.visitField(node);
    }

    @Override
    public void visitProperty(PropertyNode node) {
        record(node, Role.DECLARATION, currentClass);
        recordType(node.getOriginType(), null);
        super.visitProperty(node);
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expression) {
        if (expression.isMultipleAssignmentDeclaration()) {
            for (Expression element : expression.getTupleExpression().getExpressions()) {
                if (element instanceof VariableExpression variable) {
                    declaredVariables.add(variable);
                    record(variable, Role.DECLARATION, expression);
                }
            }
        } else {
            VariableExpression variable = expression.getVariableExpression();
            declaredVariables.add(variable);
            record(variable, Role.DECLARATION, expression);
            if (!variable.isDynamicTyped()) {
                recordType(variable.getOriginType(), null);
            }
        }
        super.visitDeclarationExpression(expression);
    }

    @Override
    public void visitVariableExpression(VariableExpression expression) {
        if (!declaredVariables.contains(expression) && !implicitReceivers.contains(expression)) {
            record(expression, Role.VARIABLE_USAGE, null);
        }
        super.visitVariableExpression(expression);
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        if (call.isImplicitThis()) {
            implicitReceivers.add(call.getObjectExpression());
        }
        Expression method = call.getMethod();
        if (method instanceof ConstantExpression constant) {
            record(constant, Role.METHOD_NAME, call);
        }
        record(call, Role.OTHER, null);
        super.visitMethodCallExpression(call);
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        record(call, Role.STATIC_CALL, null);
        super.visitStaticMethodCallExpression(call);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        record(call, Role.CONSTRUCTOR_CALL, null);
        if (!call.isSpecialCall()) {
            recordType(call.getType(), null);
        }
        super.visitConstructorCallExpression(call);
    }

    @Override
    public void visitPropertyExpression(PropertyExpression expression) {
        if (expression.isImplicitThis()) {
            implicitReceivers.add(expression.getObjectExpression());
        }
        if (expression.getProperty() instanceof ConstantExpression constant) {
            record(constant, Role.PROPERTY_NAME, expression);
        }
        record(expression, Role.OTHER, null);
        super.visitPropertyExpression(expression);
    }

    @Override
    public void visitAttributeExpression(AttributeExpression expression) {
        if (expression.isImplicitThis()) {
            implicitReceivers.add(expression.getObjectExpression());
        }
        if (expression.getProperty() instanceof ConstantExpression constant) {
            record(constant, Role.PROPERTY_NAME, expression);
        }
        record(expression, Role.OTHER, null);
        super.visitAttributeExpression(expression);
    }

    @Override
    public void visitClassExpression(ClassExpression expression) {
        Range range = AstRanges.rangeOf(expression);
        if (range != null && recorded.add(expression)) {
            entries.add(new NodeEntry(uri, expression.getType(), Role.TYPE_USAGE, range, expression, currentClass,
                    currentMethod));
        }
        super.visitClassExpression(expression);
    }

    @Override
    public void visitCastExpression(CastExpression expression) {
        recordType(expression.getType(), null);
        super.visitCastExpression(expression);
    }

    @Override
    public void visitClosureExpression(ClosureExpression expression) {
        if (expression.getParameters() != null) {
            for (Parameter parameter : expression.getParameters()) {
                recordParameter(parameter);
            }
        }
        record(expression, Role.OTHER, null);
        super.visitClosureExpression(expression);
    }

    @Override
    public void visitCatchStatement(CatchStatement statement) {
        recordParameter(statement.getVariable());
        super.visitCatchStatement(statement);
    }

    @Override
    public void visitForLoop(ForStatement statement) {
        for (Parameter variable : new Parameter[] {statement.getIndexVariable(), statement.getValueVariable()}) {
            if (variable != null && variable.getLineNumber() > 0) {
                recordParameter(variable);
            }
        }
        super.visitForLoop(statement);
    }

    private void recordParameter(Parameter parameter) {
        record(parameter, Role.DECLARATION, currentMethod);
        if (!parameter.isDynamicTyped()) {
            recordType(parameter.getOriginType(), null);
        }
    }

    private void recordGenerics(GenericsType[] generics) {
        if (generics == null) {
            return;
        }
        for (GenericsType generic : generics) {
            if (generic.isPlaceholder()) {
                continue;
            }
            Range range = AstRanges.rangeOf(generic);
            ClassNode type = generic.getType();
            if (range != null && type != null && recorded.add(generic)) {
                entries.add(new NodeEntry(uri, type, Role.TYPE_USAGE, range, generic, currentClass, currentMethod));
            }
            recordGenerics(type == null ? null : type.getGenericsTypes());
        }
    }

    /** Records a type mention when the compiler kept a position for it and it names a real class. */
    private void recordType(ClassNode type, ASTNode positionSource) {
        if (type == null || ClassHelper.isPrimitiveType(type) || ClassHelper.isDynamicTyped(type)
                || "java.lang.Object".equals(type.getName()) && type.getLineNumber() < 1) {
            return;
        }
        ASTNode positioned = positionSource != null ? positionSource : type;
        Range range = AstRanges.rangeOf(positioned);
        if (range == null || !recorded.add(positioned == type ? type : positioned)) {
            return;
        }
        entries.add(new NodeEntry(uri, type, Role.TYPE_USAGE, range, positionSource, currentClass, currentMethod));
        recordGenerics(type.getGenericsTypes());
    }

    private void record(ASTNode node, Role role, ASTNode parent) {
        Range range = AstRanges.rangeOf(node);
        if (range == null || !recorded.add(node)) {
            return;
        }
        entries.add(new NodeEntry(uri, node, role, range, parent, currentClass, currentMethod));
    }
}
