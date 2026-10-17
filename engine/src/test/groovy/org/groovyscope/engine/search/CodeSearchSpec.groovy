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
package org.groovyscope.engine.search

import java.nio.file.Path
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.groovyscope.engine.services.Snapshot
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** Structural search over scripts and classes, beyond the fixture project the request spec uses. */
class CodeSearchSpec extends Specification {

    @Shared @TempDir Path root
    @Shared Snapshot snapshot

    static final String SCRIPT = '''\
def (a, b) = [1, 2]
String typed = 'x'
for (int i = 0; i < 2; i++) {
}
while (a > 5) {
}
do {
} while (a > 5)
try {
    println 'x'
} finally {
}
synchronized (this) {
}
def task = new Runnable() {
    void run() {
        println "running ${typed}"
    }
}
println Math.max(a, b)
println "$typed".size()
println((typed as String).trim())
def ref = this.&println
throw task
'''

    static final String TYPES = '''\
package demo

class Shapes {
    static int twice(int x) { x * 2 }
    int use() { twice(3) }
    Shapes() {
    }
    enum Colour { RED, GREEN }
}
'''

    def setupSpec() {
        snapshot = SearchFixtures.compile(root, ['tool.groovy': SCRIPT, 'demo/Shapes.groovy': TYPES])
    }

    List<CodeSearch.Match> find(CodeSearch.Query query, Collection<String> uris = []) {
        CodeSearch.find(snapshot.result().modules(), snapshot.index()::lines, uris, query)
    }

    def 'empty blocks of every statement kind are found in a script'() {
        when:
        def found = find(CodeSearch.Query.of('empty-block'), [SearchFixtures.uri(root, 'tool.groovy')])

        then:
        found*.detail == ['empty for block', 'empty while block', 'empty do-while block', 'empty finally block',
                          'empty synchronized block']
        found.every { it.enclosing == 'tool' }
    }

    def 'multiple-assignment and script declarations count as dynamic'() {
        expect:
        find(CodeSearch.Query.of('def'), [SearchFixtures.uri(root, 'tool.groovy')])*.detail == ['def (a, b)', 'def task', 'def ref']
    }

    def 'constructor calls of anonymous classes report the implemented type'() {
        expect:
        find(new CodeSearch.Query('new', null, 'Runnable', null, [], null, null, null, null))*.detail == ['new Runnable']
    }

    def 'calls on classes, GStrings and casts have a receiver type'() {
        expect:
        find(new CodeSearch.Query('call', 'max', 'Math', null, [], null, null, null, null)).size() == 1
        find(new CodeSearch.Query('call', 'size', 'GString', null, [], null, null, null, null)).size() == 1
        find(new CodeSearch.Query('call', 'trim', 'String', null, [], null, null, null, null)).size() == 1
        find(new CodeSearch.Query('call', 'println', null, null, [], null, null, null, null))*.detail.every { it == 'call println' }
    }

    def 'throws of expressions describe the expression'() {
        expect:
        find(CodeSearch.Query.of('throw'))*.detail == ['throw task']
        find(new CodeSearch.Query('throw', null, 'Exception', null, [], null, null, null, null)).isEmpty()
    }

    def 'constructors and static calls are found; enum constants are not fields'() {
        when:
        def methods = find(CodeSearch.Query.of('method'), [SearchFixtures.uri(root, 'demo/Shapes.groovy')])
        def fields = find(CodeSearch.Query.of('field'), [SearchFixtures.uri(root, 'demo/Shapes.groovy')])
        def calls = find(new CodeSearch.Query('call', 'twice', null, null, [], null, null, null, null))
        def empty = find(CodeSearch.Query.of('empty-block'), [SearchFixtures.uri(root, 'demo/Shapes.groovy')])

        then:
        methods*.detail.containsAll(['method twice(int) 1 line', 'constructor Shapes() 2 lines'])
        fields.isEmpty()
        calls.size() == 1
        calls[0].enclosing == 'Shapes.use'
        empty*.detail == ['empty constructor body Shapes()']
        empty[0].enclosing == 'Shapes.<init>'
    }

    def 'type names match by simple or qualified name, nested with dots or dollars'() {
        given:
        ClassNode nested = ClassHelper.make('demo.Outer$Inner')

        expect:
        CodeSearch.typeMatches(nested, 'Inner')
        CodeSearch.typeMatches(nested, 'Outer.Inner')
        CodeSearch.typeMatches(nested, 'demo.Outer$Inner')
        !CodeSearch.typeMatches(nested, 'Outer')
        !CodeSearch.typeMatches(null, 'Outer')
        CodeSearch.typeMatches(null, ' ')
    }

    def 'receiver types are known only for declared receivers'() {
        given:
        ClassNode current = ClassHelper.make('demo.Current')

        expect:
        CodeSearch.receiverType(VariableExpression.THIS_EXPRESSION, current) == current
        CodeSearch.receiverType(VariableExpression.SUPER_EXPRESSION, null) == null
        CodeSearch.receiverType(new VariableExpression('x'), current) == null
        CodeSearch.receiverType(new ConstantExpression('s'), current) == ClassHelper.STRING_TYPE
        CodeSearch.receiverType(new ConstantExpression(1), current) == null
        CodeSearch.receiverType(new CastExpression(ClassHelper.Integer_TYPE, new ConstantExpression(1)), current) == ClassHelper.Integer_TYPE
        CodeSearch.receiverType(new GStringExpression('$x'), current) == ClassHelper.GSTRING_TYPE
    }

    def 'helpers: emptiness, shortening and modifier text'() {
        expect:
        CodeSearch.isEmpty(null)
        CodeSearch.isEmpty(new BlockStatement())
        CodeSearch.isEmpty(EmptyStatement.INSTANCE)
        !CodeSearch.containsThrow(null)
        CodeSearch.shorten(null, 10) == ''
        CodeSearch.shorten('a   b', 10) == 'a b'
        CodeSearch.shorten('abcdefghijklmnop', 10) == 'abcdefg...'
        CodeSearch.modifierText(java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC) == 'public static'
    }

    def 'unknown documents and Java documents are skipped'() {
        expect:
        CodeSearch.targets(snapshot.result().modules(), ['file:///nowhere/X.groovy']).isEmpty()
        CodeSearch.targets([(SearchFixtures.uri(root, 'A.java')): null], []).isEmpty()
    }
}
