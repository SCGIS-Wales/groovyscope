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
package org.groovyscope.engine.semantics;

import java.lang.reflect.Modifier;
import java.util.StringJoiner;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.transform.trait.Traits;
import org.groovyscope.engine.model.SymbolKind;

/** Human-readable names and one-line signatures for AST declarations. */
public final class TypeNames {

    private TypeNames() {
    }

    /** Simple name with generics, {@code def} for dynamic types, {@code Foo[]} for arrays. */
    public static String simple(ClassNode type) {
        if (type == null || ClassHelper.isDynamicTyped(type)) {
            return "def";
        }
        if (type.isArray()) {
            return simple(type.getComponentType()) + "[]";
        }
        String name = type.getNameWithoutPackage();
        int dollar = name.lastIndexOf('$');
        if (dollar >= 0 && !type.isPrimaryClassNode()) {
            name = name.substring(dollar + 1);
        }
        GenericsType[] generics = type.getGenericsTypes();
        if (generics == null || generics.length == 0 || !type.isUsingGenerics()) {
            return name;
        }
        StringJoiner joiner = new StringJoiner(", ", "<", ">");
        for (GenericsType generic : generics) {
            joiner.add(generic.isPlaceholder() ? generic.getName() : simple(generic.getType()));
        }
        return name + joiner;
    }

    public static SymbolKind kindOf(ClassNode classNode) {
        if (classNode.isScript()) {
            return SymbolKind.SCRIPT;
        }
        if (classNode.isAnnotationDefinition()) {
            return SymbolKind.ANNOTATION;
        }
        if (classNode.isInterface()) {
            return Traits.isTrait(classNode) ? SymbolKind.TRAIT : SymbolKind.INTERFACE;
        }
        if (classNode.isEnum()) {
            return SymbolKind.ENUM;
        }
        if (classNode.isRecord()) {
            return SymbolKind.RECORD;
        }
        return SymbolKind.CLASS;
    }

    public static String keywordOf(ClassNode classNode) {
        return switch (kindOf(classNode)) {
            case SCRIPT -> "script";
            case ANNOTATION -> "@interface";
            case TRAIT -> "trait";
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            default -> "class";
        };
    }

    public static String signature(ClassNode classNode) {
        StringBuilder builder = new StringBuilder();
        if (classNode.isScript()) {
            return "script " + classNode.getNameWithoutPackage();
        }
        if (Modifier.isAbstract(classNode.getModifiers()) && !classNode.isInterface()) {
            builder.append("abstract ");
        }
        builder.append(keywordOf(classNode)).append(' ').append(simple(classNode));
        ClassNode superClass = classNode.getUnresolvedSuperClass(false);
        if (superClass != null && !classNode.isInterface() && !classNode.isEnum() && !classNode.isRecord()
                && !ClassHelper.isObjectType(superClass) && !"groovy.lang.Script".equals(superClass.getName())) {
            builder.append(" extends ").append(simple(superClass));
        }
        ClassNode[] interfaces = classNode.getUnresolvedInterfaces(false);
        if (interfaces != null && interfaces.length > 0) {
            StringJoiner joiner = new StringJoiner(", ");
            for (ClassNode iface : interfaces) {
                if (!"groovy.lang.GroovyObject".equals(iface.getName())) {
                    joiner.add(simple(iface));
                }
            }
            if (joiner.length() > 0) {
                builder.append(classNode.isInterface() ? " extends " : " implements ").append(joiner);
            }
        }
        return builder.toString();
    }

    public static String signature(MethodNode method) {
        StringBuilder builder = new StringBuilder();
        if (method.isStatic()) {
            builder.append("static ");
        }
        if (method.isAbstract() && !method.getDeclaringClass().isInterface()) {
            builder.append("abstract ");
        }
        if (!(method instanceof ConstructorNode)) {
            builder.append(simple(method.getReturnType())).append(' ').append(method.getName());
        } else {
            builder.append(method.getDeclaringClass().getNameWithoutPackage());
        }
        builder.append('(').append(parameters(method.getParameters())).append(')');
        return builder.toString();
    }

    public static String parameters(Parameter[] parameters) {
        if (parameters == null) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (Parameter parameter : parameters) {
            joiner.add(parameter(parameter));
        }
        return joiner.toString();
    }

    public static String parameter(Parameter parameter) {
        String type = parameter.isDynamicTyped() ? "def" : simple(parameter.getOriginType());
        String text = type + " " + parameter.getName();
        if (parameter.hasInitialExpression()) {
            text += " = " + parameter.getInitialExpression().getText();
        }
        return text;
    }

    public static String signature(FieldNode field) {
        return (field.isStatic() ? "static " : "") + simple(field.getOriginType()) + " " + field.getName();
    }

    public static String signature(PropertyNode property) {
        return (property.isStatic() ? "static " : "") + simple(property.getOriginType()) + " " + property.getName();
    }

    public static String signature(VariableExpression variable) {
        String type = variable.isDynamicTyped() ? "def" : simple(variable.getOriginType());
        return type + " " + variable.getName();
    }
}
