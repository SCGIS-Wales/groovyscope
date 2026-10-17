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

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.groovyscope.engine.model.Range;

/**
 * One positioned AST node in a document.
 *
 * @param uri the document
 * @param node the node itself
 * @param role what the node means at that position
 * @param range zero-based half-open range
 * @param parent the syntactic parent that gives the node meaning (a call for its method name, for example)
 * @param enclosingClass the class whose body contains the node, or {@code null}
 * @param enclosingMethod the method or constructor whose body contains the node, or {@code null}
 */
public record NodeEntry(String uri, ASTNode node, Role role, Range range, ASTNode parent, ClassNode enclosingClass,
        MethodNode enclosingMethod) {

    /** What an indexed node stands for. */
    public enum Role {
        /** A declaration: class, method, field, property, parameter, local variable, import. */
        DECLARATION,
        /** A type mentioned by name (extends, parameter type, cast, generics, annotation). */
        TYPE_USAGE,
        /** A variable, field or property read or write through a {@code VariableExpression}. */
        VARIABLE_USAGE,
        /** The name token of a method call. */
        METHOD_NAME,
        /** The name token of a property access. */
        PROPERTY_NAME,
        /** A constructor call. */
        CONSTRUCTOR_CALL,
        /** A static method call written as {@code Type.method(...)} after resolution. */
        STATIC_CALL,
        /** Any other expression or statement, kept so enclosing lookups work. */
        OTHER
    }

    public boolean isDeclaration() {
        return role == Role.DECLARATION;
    }
}
