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
package org.groovyscope.engine.typecheck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * The parameters, return types and fields a piece of new text declares without a type ({@code def}, or nothing),
 * with the type Groovy's static type checker infers for each from the code when it can tell: what an edit is asked
 * to declare when {@code types.declareOnEdit} is on.
 */
public final class UntypedDeclarations {

    private UntypedDeclarations() {
    }

    /**
     * One declaration without a type.
     *
     * @param line where it is (1-based)
     * @param what {@code parameter x}, {@code return type of m} or {@code field f}
     * @param inferred the type the checker infers, or {@code null} when it cannot tell
     */
    public record Untyped(int line, String what, String inferred) {
    }

    /** Where an untyped declaration is, so the checked tree's node can be found again. */
    private record Found(int line, String what, String owner, String method, String name, Kind kind) {
    }

    private enum Kind { PARAMETER, RETURN, FIELD }

    /**
     * The untyped parameters, return types and fields declared on lines {@code from} to {@code to} (1-based,
     * inclusive) of a parsed text, in source order; a method whose declaration starts outside them (a body that was
     * replaced) is not counted.
     */
    public static List<Untyped> declared(ModuleNode module, int from, int to) {
        List<Untyped> untyped = new ArrayList<>();
        for (Found found : find(module, from, to)) {
            untyped.add(new Untyped(found.line(), found.what(), null));
        }
        return untyped;
    }

    /** The classes of a parsed text that hold an untyped declaration on lines {@code from} to {@code to}. */
    public static Set<String> owners(ModuleNode module, int from, int to) {
        Set<String> owners = new LinkedHashSet<>();
        for (Found found : find(module, from, to)) {
            owners.add(found.owner());
        }
        return owners;
    }

    /**
     * {@link #declared}, each with the type the checker inferred in {@code checked}, the same text's tree after a
     * type preview ({@link TypePreview#check}); {@code null} where it cannot tell (a parameter has nothing to infer
     * from but its default value).
     */
    public static List<Untyped> inferred(ModuleNode parsed, ModuleNode checked, int from, int to) {
        List<Untyped> untyped = new ArrayList<>();
        for (Found found : find(parsed, from, to)) {
            untyped.add(new Untyped(found.line(), found.what(), checked == null ? null : infer(checked, found)));
        }
        return untyped;
    }

    private static List<Found> find(ModuleNode module, int from, int to) {
        List<Found> found = new ArrayList<>();
        if (module == null) {
            return found;
        }
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.isSynthetic()) {
                continue;
            }
            for (FieldNode field : classNode.getFields()) {
                if ((!field.isSynthetic() || classNode.getProperty(field.getName()) != null)
                        && !field.getName().contains("$") && field.isDynamicTyped()
                        && inside(field.getLineNumber(), from, to)) {
                    found.add(new Found(field.getLineNumber(), "field " + field.getName(), classNode.getName(), null,
                            field.getName(), Kind.FIELD));
                }
            }
            List<MethodNode> methods = new ArrayList<>(classNode.getMethods());
            methods.addAll(classNode.getDeclaredConstructors());
            for (MethodNode method : methods) {
                if (method.isSynthetic() || method.isScriptBody() || !inside(method.getLineNumber(), from, to)) {
                    continue;
                }
                if (!(method instanceof ConstructorNode) && method.isDynamicReturnType()) {
                    found.add(new Found(method.getLineNumber(), "return type of " + method.getName(),
                            classNode.getName(), method.getName(), null, Kind.RETURN));
                }
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isDynamicTyped()) {
                        int line = parameter.getLineNumber() > 0 ? parameter.getLineNumber() : method.getLineNumber();
                        found.add(new Found(line, "parameter " + parameter.getName(), classNode.getName(),
                                method.getName(), parameter.getName(), Kind.PARAMETER));
                    }
                }
            }
        }
        found.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return found;
    }

    private static boolean inside(int line, int from, int to) {
        return line >= from && line <= to;
    }

    private static String infer(ModuleNode checked, Found found) {
        ClassNode owner = null;
        for (ClassNode classNode : checked.getClasses()) {
            if (classNode.getName().equals(found.owner())) {
                owner = classNode;
            }
        }
        if (owner == null) {
            return null;
        }
        if (found.kind() == Kind.FIELD) {
            FieldNode field = owner.getDeclaredField(found.name());
            return field == null ? null : shown(typeOf(field.getInitialExpression()));
        }
        List<MethodNode> methods = new ArrayList<>(owner.getMethods(found.method()));
        if (found.method().equals("<init>")) {
            methods.addAll(owner.getDeclaredConstructors());
        }
        for (MethodNode method : methods) {
            if (method.getLineNumber() != found.line() && found.kind() == Kind.RETURN) {
                continue;
            }
            if (found.kind() == Kind.RETURN) {
                return shown(returned(method));
            }
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getName().equals(found.name()) && parameter.hasInitialExpression()) {
                    return shown(typeOf(parameter.getInitialExpression()));
                }
            }
        }
        return null;
    }

    /** The one type every value a method returns has, {@code void} when it returns none, else {@code null}. */
    static ClassNode returned(MethodNode method) {
        List<Expression> values = new ArrayList<>();
        if (method.getCode() == null) {
            return null;
        }
        method.getCode().visit(new CodeVisitorSupport() {
            @Override
            public void visitReturnStatement(ReturnStatement statement) {
                values.add(statement.getExpression());
                super.visitReturnStatement(statement);
            }

            @Override
            public void visitClosureExpression(ClosureExpression expression) {
                // a closure's returns are its own
            }
        });
        Statement last = method.getCode();
        while (last instanceof BlockStatement block && !block.getStatements().isEmpty()) {
            last = block.getStatements().get(block.getStatements().size() - 1);
        }
        if (last instanceof ExpressionStatement statement && !(statement.getExpression()
                instanceof DeclarationExpression)) {
            values.add(statement.getExpression());
        }
        ClassNode type = null;
        boolean onlyNull = !values.isEmpty();
        for (Expression value : values) {
            if (value instanceof ConstantExpression constant && constant.getValue() == null) {
                continue;
            }
            onlyNull = false;
            ClassNode one = typeOf(value);
            if (one == null || type != null && !type.equals(one)) {
                return null;
            }
            type = one;
        }
        if (type != null || onlyNull) {
            return type;
        }
        return ClassHelper.VOID_TYPE;
    }

    /** The type the checker gave an expression, or a literal's or a constructor's own; {@code null} if unknown. */
    static ClassNode typeOf(Expression expression) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof ConstantExpression constant) {
            return constant.getValue() == null ? null : constant.getType();
        }
        if (expression instanceof ConstructorCallExpression constructor) {
            return constructor.getType();
        }
        Object inferred = expression.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
        if (inferred instanceof ClassNode type) {
            return type;
        }
        if (expression instanceof VariableExpression variable && variable.getAccessedVariable() != null
                && !variable.getAccessedVariable().isDynamicTyped()) {
            return variable.getAccessedVariable().getOriginType();
        }
        return null;
    }

    /** How a type is written in a declaration; {@code null} for {@code Object} and unknown. */
    private static String shown(ClassNode type) {
        if (type == null || ClassHelper.isObjectType(type) || ClassHelper.isDynamicTyped(type)) {
            return null;
        }
        return TypeNames.simple(type);
    }
}
