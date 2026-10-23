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

import java.util.List;
import java.util.Optional;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.Statement;

/**
 * The first use of instance state in code: {@code this} or {@code super}, an instance field or property, a call to an
 * instance method of the class through the implicit {@code this}, or a name the class resolves at run time. Code
 * without any can live in a static method.
 */
final class InstanceState extends CodeVisitorSupport {

    private final ClassNode owner;
    private String found;

    private InstanceState(ClassNode owner) {
        this.owner = owner;
    }

    /** The first use of instance state in {@code statements} of a method of {@code owner}, described. */
    static Optional<String> in(ClassNode owner, List<Statement> statements) {
        InstanceState visitor = new InstanceState(owner);
        for (Statement statement : statements) {
            statement.visit(visitor);
        }
        return Optional.ofNullable(visitor.found);
    }

    /** The first use of instance state in a method's body and parameter defaults; empty for a static method. */
    static Optional<String> in(MethodNode method) {
        if (method.isStatic()) {
            return Optional.empty();
        }
        InstanceState visitor = new InstanceState(method.getDeclaringClass());
        for (Parameter parameter : method.getParameters()) {
            if (parameter.hasInitialExpression()) {
                parameter.getInitialExpression().visit(visitor);
            }
        }
        if (method.getCode() != null) {
            method.getCode().visit(visitor);
        }
        return Optional.ofNullable(visitor.found);
    }

    private void note(String what, int line) {
        if (found == null) {
            found = what + (line > 0 ? " (line " + line + ")" : "");
        }
    }

    @Override
    public void visitVariableExpression(VariableExpression expression) {
        String name = expression.getName();
        if ("this".equals(name) || "super".equals(name)) {
            note("`" + name + "`", expression.getLineNumber());
            return;
        }
        Variable accessed = expression.getAccessedVariable();
        if (accessed instanceof FieldNode field && !field.isStatic()) {
            note((owner.getProperty(name) != null ? "property " : "field ") + name, expression.getLineNumber());
        } else if (accessed instanceof PropertyNode property && !property.isStatic()) {
            note("property " + name, expression.getLineNumber());
        } else if (accessed instanceof DynamicVariable dynamic && !dynamic.isInStaticContext()) {
            note("property " + name + " of the instance", expression.getLineNumber());
        }
    }

    @Override
    public void visitFieldExpression(FieldExpression expression) {
        if (!expression.getField().isStatic()) {
            note("field " + expression.getFieldName(), expression.getLineNumber());
        }
    }

    @Override
    public void visitPropertyExpression(PropertyExpression expression) {
        if (expression.isImplicitThis()) {
            note("property " + expression.getPropertyAsString(), expression.getLineNumber());
            expression.getProperty().visit(this);
            return;
        }
        super.visitPropertyExpression(expression);
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        if (call.isImplicitThis()) {
            if (instanceMethod(call.getMethodAsString())) {
                note("instance method " + call.getMethodAsString(), call.getLineNumber());
            }
            call.getMethod().visit(this);
            call.getArguments().visit(this);
            return;
        }
        super.visitMethodCallExpression(call);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        if (call.isSpecialCall()) {
            note("`" + call.getText() + "`", call.getLineNumber());
        }
        super.visitConstructorCallExpression(call);
    }

    /** Whether the class or a superclass declares an instance method named {@code name}. */
    private boolean instanceMethod(String name) {
        if (name == null) {
            return true; // a computed name: anything may be called
        }
        for (ClassNode type = owner; type != null; type = type.getSuperClass()) {
            for (MethodNode method : type.getMethods(name)) {
                if (!method.isStatic()) {
                    return true;
                }
            }
        }
        return false;
    }
}
