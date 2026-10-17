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
package org.groovyscope.engine.semantics

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.groovyscope.engine.model.SymbolKind
import spock.lang.Specification

import java.lang.reflect.Modifier

class TypeNamesSpec extends Specification {

    def 'simple names handle dynamic types, arrays and generics'() {
        given:
        def list = ClassHelper.make(List).plainNodeReference
        list.setGenericsTypes([new GenericsType(ClassHelper.STRING_TYPE)] as GenericsType[])
        def placeholder = ClassHelper.make(List).plainNodeReference
        // A fresh node: GenericsType.setPlaceholder mutates its ClassNode, and OBJECT_TYPE is shared JVM-wide.
        def t = new GenericsType(ClassHelper.OBJECT_TYPE.plainNodeReference, null, null)
        t.placeholder = true
        t.name = 'T'
        placeholder.setGenericsTypes([t] as GenericsType[])

        expect:
        TypeNames.simple(null) == 'def'
        TypeNames.signature(new VariableExpression('x')) == 'def x'
        TypeNames.simple(ClassHelper.STRING_TYPE.makeArray()) == 'String[]'
        TypeNames.simple(list) == 'List<String>'
        TypeNames.simple(placeholder) == 'List<T>'
        TypeNames.simple(ClassHelper.make('a.b.Outer$Inner')) == 'Inner'
    }

    def 'class kinds and keywords'() {
        given:
        def iface = new ClassNode('p.I', Modifier.INTERFACE | Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE)
        def enumeration = new ClassNode('p.E', Modifier.PUBLIC | 0x4000, ClassHelper.make(Enum))
        def annotation = new ClassNode('p.A', Modifier.INTERFACE | 0x2000, ClassHelper.OBJECT_TYPE)
        def script = new ClassNode('p.S', Modifier.PUBLIC, ClassHelper.make(groovy.lang.Script))
        script.script = true
        def abstractClass = new ClassNode('p.B', Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE)

        expect:
        TypeNames.kindOf(iface) == SymbolKind.INTERFACE
        TypeNames.kindOf(enumeration) == SymbolKind.ENUM
        TypeNames.kindOf(annotation) == SymbolKind.ANNOTATION
        TypeNames.kindOf(script) == SymbolKind.SCRIPT
        TypeNames.keywordOf(iface) == 'interface'
        TypeNames.keywordOf(enumeration) == 'enum'
        TypeNames.keywordOf(annotation) == '@interface'
        TypeNames.keywordOf(script) == 'script'
        TypeNames.signature(script) == 'script S'
        TypeNames.signature(abstractClass) == 'abstract class B'
        TypeNames.signature(enumeration) == 'enum E'
    }

    def 'interface and implementation lists'() {
        given:
        def iface = new ClassNode('p.I', Modifier.INTERFACE | Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE)
        def other = new ClassNode('p.J', Modifier.INTERFACE | Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE)
        iface.setInterfaces([other, ClassHelper.make(groovy.lang.GroovyObject)] as ClassNode[])
        def impl = new ClassNode('p.C', Modifier.PUBLIC, ClassHelper.make('p.Base'), [iface] as ClassNode[], null)

        expect:
        TypeNames.signature(iface) == 'interface I extends J'
        TypeNames.signature(impl) == 'class C extends Base implements I'
    }

    def 'member signatures'() {
        given:
        def owner = new ClassNode('p.C', Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        def withDefault = new Parameter(ClassHelper.int_TYPE, 'n', new ConstantExpression(1))
        def method = new MethodNode('m', Modifier.STATIC | Modifier.PUBLIC, ClassHelper.VOID_TYPE, [withDefault] as Parameter[], ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE)
        method.declaringClass = owner
        def abstractMethod = new MethodNode('a', Modifier.ABSTRACT | Modifier.PUBLIC, ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null)
        abstractMethod.declaringClass = owner
        def field = new FieldNode('f', Modifier.STATIC, ClassHelper.STRING_TYPE, owner, null)
        def property = new PropertyNode('p', Modifier.PUBLIC, ClassHelper.int_TYPE, owner, null, null, null)
        def variable = new VariableExpression('v', ClassHelper.STRING_TYPE)

        expect:
        TypeNames.signature(method) == 'static void m(int n = 1)'
        TypeNames.signature(abstractMethod) == 'abstract void a()'
        TypeNames.signature(field) == 'static String f'
        TypeNames.signature(property) == 'int p'
        TypeNames.signature(variable) == 'String v'
        TypeNames.parameters(null) == ''
    }

    def 'hover markdown cleans groovydoc comments'() {
        expect:
        HoverRenderer.cleanGroovydoc('/**\n * Line one.\n *\n * Line two.\n */') == 'Line one.\nLine two.'
        HoverRenderer.cleanGroovydoc('/** single */') == 'single'
    }
}
