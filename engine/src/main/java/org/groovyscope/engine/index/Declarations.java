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
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;

/**
 * The declarations of one syntax tree, flat and in source order: classes (inner ones, enums, interfaces, traits and
 * records included), methods, constructors, fields and properties. It needs only a parsed tree, not a workspace
 * snapshot, so it serves texts that never become workspace documents (candidate resolutions of a merge conflict).
 * Synthetic members, members Groovy generates without a source position (a script's {@code run} and {@code main},
 * an enum's helpers) and anonymous classes with everything in them are left out.
 */
public final class Declarations {

    /** What a declaration is; a property counts as a field. */
    public enum Kind {
        CLASS,
        METHOD,
        CONSTRUCTOR,
        FIELD;

        /** The kind as the requests spell it ({@code class}, {@code method}, ...). */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One declaration.
     *
     * @param name {@code Outer.Inner.member} with simple class names; a constructor is {@code Class.<init>}
     * @param kind what it is
     * @param line first line (1-based), its annotations included
     * @param endLine last line (1-based, inclusive)
     * @param column first column (1-based), to order declarations that start on the same line
     */
    public record Declaration(String name, Kind kind, int line, int endLine, int column) {
    }

    private Declarations() {
    }

    /**
     * One declaration with the syntax tree node it was read from, for callers that need more than its line range
     * (the braces of a method body or of a class).
     *
     * @param declaration the declaration
     * @param node its class, method, constructor, field or property node
     */
    public record Located(Declaration declaration, AnnotatedNode node) {
    }

    /** The declarations of {@code module} in source order; empty for a null module. */
    public static List<Declaration> of(ModuleNode module) {
        List<Declaration> declarations = new ArrayList<>();
        for (Located located : located(module)) {
            declarations.add(located.declaration());
        }
        return declarations;
    }

    /** The declarations of {@code module} in source order, each with its node; empty for a null module. */
    public static List<Located> located(ModuleNode module) {
        if (module == null) {
            return List.of();
        }
        List<Located> found = new ArrayList<>();
        for (ClassNode classNode : module.getClasses()) {
            if (anonymous(classNode)) {
                continue;
            }
            String className = qualifiedName(classNode);
            boolean placed = add(found, className, Kind.CLASS, classNode);
            int membersFrom = found.size();
            for (ConstructorNode constructor : classNode.getDeclaredConstructors()) {
                add(found, className + ".<init>", Kind.CONSTRUCTOR, constructor);
            }
            for (MethodNode method : classNode.getMethods()) {
                add(found, className + "." + method.getName(), Kind.METHOD, method);
            }
            Set<FieldNode> backing = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PropertyNode property : classNode.getProperties()) {
                backing.add(property.getField());
                add(found, className + "." + property.getName(), Kind.FIELD, property);
            }
            for (FieldNode field : classNode.getFields()) {
                if (!backing.contains(field)) {
                    add(found, className + "." + field.getName(), Kind.FIELD, field);
                }
            }
            if (placed && classNode.isScript()) {
                // Groovy gives a script's class the extent of its statements; it is the whole script, methods
                // included.
                Located script = found.get(membersFrom - 1);
                found.set(membersFrom - 1, new Located(spanning(script.declaration(),
                        found.subList(membersFrom, found.size())), script.node()));
            }
        }
        found.sort(Comparator.comparingInt((Located located) -> located.declaration().line())
                .thenComparingInt(located -> located.declaration().column()));
        return found;
    }

    /** Adds the declaration unless it is synthetic or has no position; true when it was added. */
    private static boolean add(List<Located> found, String name, Kind kind, AnnotatedNode node) {
        if (node.isSynthetic() || node.getLineNumber() < 1) {
            return false;
        }
        int line = node.getLineNumber();
        for (AnnotationNode annotation : node.getAnnotations()) {
            if (annotation.getLineNumber() >= 1) {
                line = Math.min(line, annotation.getLineNumber());
            }
        }
        int endLine = Math.max(line, node.getLastLineNumber());
        found.add(new Located(new Declaration(name, kind, line, endLine, Math.max(1, node.getColumnNumber())), node));
        return true;
    }

    /** {@code declaration} widened to cover {@code members} as well. */
    private static Declaration spanning(Declaration declaration, List<Located> members) {
        int line = declaration.line();
        int endLine = declaration.endLine();
        int column = declaration.column();
        for (Located located : members) {
            Declaration member = located.declaration();
            if (member.line() < line) {
                line = member.line();
                column = member.column();
            }
            endLine = Math.max(endLine, member.endLine());
        }
        return new Declaration(declaration.name(), declaration.kind(), line, endLine, column);
    }

    /** True for an anonymous class or any class declared inside one. */
    private static boolean anonymous(ClassNode classNode) {
        for (ClassNode current = classNode; current != null; current = current.getOuterClass()) {
            if (current instanceof InnerClassNode inner && inner.isAnonymous()) {
                return true;
            }
        }
        return false;
    }

    /** {@code Outer.Inner} from simple names: the package is dropped and each nesting level joined by a dot. */
    static String qualifiedName(ClassNode classNode) {
        ClassNode outer = classNode.getOuterClass();
        if (outer == null) {
            return classNode.getNameWithoutPackage();
        }
        String prefix = outer.getName() + "$";
        String name = classNode.getName();
        String simple = name.startsWith(prefix) ? name.substring(prefix.length())
                : name.substring(name.lastIndexOf('$') + 1);
        return qualifiedName(outer) + "." + simple;
    }
}
