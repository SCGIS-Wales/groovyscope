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
package org.groovyscope.engine.index

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.groovyscope.engine.model.Range
import spock.lang.Specification

class AstRangesSpec extends Specification {

    static final String TEXT = '''\
import a.b.Thing as Alias
class Foo extends Bar {
    String name = other.name
    Foo(String name) { }
    String greet(String name) { name }
}
'''
    TextLines lines = new TextLines(TEXT)
    ClassNode foo = new ClassNode('p.Foo', 1, ClassHelper.OBJECT_TYPE)

    def 'synthetic nodes have no range and inverted positions are repaired'() {
        given:
        def node = new VariableExpression('x')

        expect:
        AstRanges.rangeOf(node) == null
        AstRanges.rangeOf(null) == null

        when:
        node.setLineNumber(3); node.setColumnNumber(5); node.setLastLineNumber(3); node.setLastColumnNumber(2)

        then:
        AstRanges.rangeOf(node) == Range.of(2, 4, 2, 5)
    }

    def 'name ranges are located inside the declaration text'() {
        given:
        def method = new MethodNode('greet', 1, ClassHelper.STRING_TYPE, [new Parameter(ClassHelper.STRING_TYPE, 'name')] as Parameter[], ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE)
        method.declaringClass = foo
        def constructor = new ConstructorNode(1, [new Parameter(ClassHelper.STRING_TYPE, 'name')] as Parameter[], ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE)
        constructor.declaringClass = foo
        def field = new FieldNode('name', 1, ClassHelper.STRING_TYPE, foo, null)
        def parameter = new Parameter(ClassHelper.STRING_TYPE, 'name')
        def importNode = new ImportNode(ClassHelper.make('a.b.Thing'), 'Alias')

        expect:
        AstRanges.nameRange(foo, Range.of(1, 0, 5, 1), lines) == Range.of(1, 6, 1, 9)
        AstRanges.nameRange(method, Range.of(4, 4, 4, 38), lines) == Range.of(4, 11, 4, 16)
        AstRanges.nameRange(constructor, Range.of(3, 4, 3, 24), lines) == Range.of(3, 4, 3, 7)
        AstRanges.nameRange(field, Range.of(2, 4, 2, 28), lines) == Range.of(2, 11, 2, 15)
        AstRanges.nameRange(parameter, Range.of(4, 17, 4, 28), lines) == Range.of(4, 24, 4, 28)
        AstRanges.nameRange(importNode, Range.of(0, 0, 0, 25), lines) == Range.of(0, 20, 0, 25)
        AstRanges.nameRange(new VariableExpression('v'), Range.of(0, 0, 0, 1), lines) == Range.of(0, 0, 0, 1)
        AstRanges.nameRange(foo, null, lines) == null
    }

    def 'a name only found after a dot falls back to the whole range'() {
        given:
        def field = new FieldNode('name', 1, ClassHelper.STRING_TYPE, foo, null)

        expect: 'the only "name" inside "other.name" follows a dot, so no name token is claimed'
        AstRanges.nameRange(field, Range.of(2, 18, 2, 28), lines) == Range.of(2, 18, 2, 28)
    }

    def 'nameWithin picks the last whole-word occurrence'() {
        expect:
        AstRanges.nameWithin(Range.of(2, 4, 2, 28), 'name', lines) == Range.of(2, 24, 2, 28)
        AstRanges.nameWithin(Range.of(2, 4, 2, 28), 'missing', lines) == Range.of(2, 4, 2, 28)
        AstRanges.nameWithin(null, 'x', lines) == null
    }

    def 'text lines convert offsets and positions both ways'() {
        expect:
        lines.lineCount() == 7
        lines.line(1) == 'class Foo extends Bar {'
        lines.line(99) == ''
        lines.positionOf(lines.offsetOf(new org.groovyscope.engine.model.Position(2, 4))) == new org.groovyscope.engine.model.Position(2, 4)
        lines.positionOf(-5) == new org.groovyscope.engine.model.Position(0, 0)
        lines.slice(Range.of(1, 6, 1, 9)) == 'Foo'
        lines.slice(Range.of(1, 9, 1, 6)) == ''
        new TextLines(null).text() == ''
        new TextLines('a\r\nb').line(0) == 'a'
    }
}
