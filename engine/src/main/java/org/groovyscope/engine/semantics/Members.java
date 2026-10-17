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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;

/** Member lookup over a class and everything it inherits, in resolution order. */
public final class Members {

    private Members() {
    }

    /** The class, its superclasses, interfaces and outer classes, each once, nearest first. */
    public static List<ClassNode> hierarchy(ClassNode start) {
        List<ClassNode> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collect(start, ordered, seen);
        ClassNode outer = start == null ? null : start.getOuterClass();
        while (outer != null) {
            collect(outer, ordered, seen);
            outer = outer.getOuterClass();
        }
        return ordered;
    }

    private static void collect(ClassNode node, List<ClassNode> ordered, Set<String> seen) {
        ClassNode current = node == null ? null : node.redirect();
        while (current != null && isKnown(current) && seen.add(current.getName())) {
            ordered.add(current);
            for (ClassNode iface : current.getInterfaces()) {
                collect(iface, ordered, seen);
            }
            current = current.getSuperClass() == null ? null : current.getSuperClass().redirect();
        }
    }

    /** True for classes declared in the workspace or loaded from the classpath; false for unresolved names. */
    public static boolean isKnown(ClassNode node) {
        return node != null && (node.isPrimaryClassNode() || node.isResolved());
    }

    /** Methods with that name, nearest declaration first; an override hides the methods it overrides. */
    public static List<MethodNode> methodsNamed(ClassNode type, String name) {
        List<MethodNode> methods = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClassNode classNode : hierarchy(type)) {
            for (MethodNode method : classNode.getDeclaredMethods(name)) {
                if (!method.isSynthetic() && seen.add(method.getTypeDescriptor())) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    public static List<MethodNode> allMethods(ClassNode type) {
        List<MethodNode> methods = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClassNode classNode : hierarchy(type)) {
            for (MethodNode method : classNode.getMethods()) {
                if (!method.isSynthetic() && seen.add(method.getTypeDescriptor())) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    /** The property, field or getter-backed member named {@code name}, nearest first; empty when none. */
    public static List<ASTNode> membersNamed(ClassNode type, String name) {
        List<ASTNode> members = new ArrayList<>();
        for (ClassNode classNode : hierarchy(type)) {
            PropertyNode property = classNode.getProperty(name);
            if (property != null) {
                members.add(property);
                continue;
            }
            FieldNode field = classNode.getDeclaredField(name);
            if (field != null) {
                members.add(field);
                continue;
            }
            String capitalised = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (String getter : List.of("get" + capitalised, "is" + capitalised)) {
                for (MethodNode method : classNode.getDeclaredMethods(getter)) {
                    if (method.getParameters().length == 0) {
                        members.add(method);
                    }
                }
            }
        }
        return members;
    }

    /** Properties and fields visible on a type, nearest first, without duplicates by name. */
    public static List<ASTNode> allProperties(ClassNode type) {
        List<ASTNode> members = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClassNode classNode : hierarchy(type)) {
            for (PropertyNode property : classNode.getProperties()) {
                if (seen.add(property.getName())) {
                    members.add(property);
                }
            }
            for (FieldNode field : classNode.getFields()) {
                if (!field.isSynthetic() && seen.add(field.getName())) {
                    members.add(field);
                }
            }
        }
        return members;
    }

    /** The number of arguments in a call, or -1 when spreads make it unknowable. */
    public static int argumentCount(Expression arguments) {
        if (arguments instanceof ArgumentListExpression list) {
            return countWithoutSpread(list.getExpressions());
        }
        if (arguments instanceof TupleExpression tuple) {
            return countWithoutSpread(tuple.getExpressions());
        }
        return 1;
    }

    private static int countWithoutSpread(List<Expression> expressions) {
        for (Expression expression : expressions) {
            if (expression instanceof SpreadExpression) {
                return -1;
            }
        }
        return expressions.size();
    }

    /** True when {@code count} arguments can call {@code method}, honouring defaults and varargs. */
    public static boolean accepts(MethodNode method, int count) {
        if (count < 0) {
            return true;
        }
        Parameter[] parameters = method.getParameters();
        int required = 0;
        boolean varargs = parameters.length > 0 && parameters[parameters.length - 1].getType().isArray();
        for (Parameter parameter : parameters) {
            if (!parameter.hasInitialExpression()) {
                required++;
            }
        }
        if (varargs) {
            return count >= required - 1;
        }
        return count >= required && count <= parameters.length;
    }

    public static List<MethodNode> accepting(List<MethodNode> methods, int count) {
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : methods) {
            if (accepts(method, count)) {
                matches.add(method);
            }
        }
        return Collections.unmodifiableList(matches);
    }
}
