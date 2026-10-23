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
package org.groovyscope.engine.refactor

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.groovyscope.engine.compile.GroovyCompiler
import spock.lang.Specification

/** The text scanning, instance state, type naming and import placement the refactorings share. */
class RefactorHelpersSpec extends Specification {

    static final String URI = 'file:///w/demo/Sample.groovy'

    /** The compiled tree of one document. */
    static ModuleNode module(String text) {
        def result = new GroovyCompiler().compile([(URI): text], [], false)
        try {
            return result.modules()[URI]
        } finally {
            result.close()
        }
    }

    static ClassNode type(ModuleNode module, String name) {
        module.classes.find { it.nameWithoutPackage == name }
    }

    def 'brackets match past strings, comments and nesting'() {
        expect:
        SourceText.matching(text, text.indexOf('(')) == expected

        where:
        text                               || expected
        'f(a, "x)", \'y)\', [1, (2)])'     || 25
        'f(a /* ) */, // )\n b)'           || 20
        'f("""a ) b""")'                   || 13
        'f(a'                              || -1
    }

    def 'the type before a name is found, and modifiers or annotations are not a type'() {
        expect:
        int name = text.indexOf('go(')
        int[] found = SourceText.typeBefore(text, name)
        (found == null ? null : text.substring(found[0], found[1])) == expected

        where:
        text                                       || expected
        'static Map<String, List<Integer>> go()'   || 'Map<String, List<Integer>>'
        'private String[] go()'                    || 'String[]'
        'private go()'                             || null
        '@Memoized go()'                           || null
        'def go()'                                 || 'def'
    }

    def 'identifiers, call names and blank skipping'() {
        expect:
        SourceText.identifier('total')
        !SourceText.identifier('class')
        !SourceText.identifier('2x')
        !SourceText.identifier('a-b')
        !SourceText.identifier(null)
        SourceText.callName('x = go (1)', 0, 10, 'go') == 4
        SourceText.callName('x = ago(1)', 0, 10, 'go') == -1
        SourceText.callName('go(1).size()', 2, 5, 'go') == 0
        SourceText.skipBlank('  // c\n  /* d */ x', 0) == 17
        SourceText.blankOrComment('   * more')
        !SourceText.blankOrComment('  x = 1')
        SourceText.where('file:///a/b/C.groovy', 4) == 'C.groovy:5'
    }

    def 'instance state is this, super, an instance field, an implicit property and an instance method'() {
        given:
        ModuleNode tree = module('''\
class Sample {
    int count
    static int LIMIT = 2
    Sample() {
        this(1)
    }
    Sample(int n) {
        count = n
    }
    int viaThis() { this.hashCode() }
    int viaField() { count }
    int viaCall() { viaField() }
    String viaSuper() { super.toString() }
    int none(int n) { n * LIMIT + Math.abs(n) }
    static int helper() { 1 }
    int viaStatic() { helper() }
}
''')
        ClassNode sample = type(tree, 'Sample')

        expect:
        InstanceState.in(sample.getMethods('viaThis')[0]).get() == '`this` (line 10)'
        InstanceState.in(sample.getMethods('viaField')[0]).get() == 'property count (line 11)'
        InstanceState.in(sample.getMethods('viaCall')[0]).get() == 'instance method viaField (line 12)'
        InstanceState.in(sample.getMethods('viaSuper')[0]).get() == '`super` (line 13)'
        InstanceState.in(sample.getMethods('none')[0]).isEmpty()
        InstanceState.in(sample.getMethods('helper')[0]).isEmpty()
        InstanceState.in(sample.getMethods('viaStatic')[0]).isEmpty()
        InstanceState.in(sample, [sample.declaredConstructors.find { it.parameters.length == 0 }.code]).get()
                .startsWith('`this (1)`')
    }

    def 'types are named as the module can write them, with the import a module needs'() {
        given:
        ModuleNode tree = module('''\
package demo

import java.util.concurrent.TimeUnit
import java.time.*

class Sample {
    TimeUnit unit
    LocalDate day
    BigDecimal amount
    Map<String, List<Integer>> table
    Thread.State state
}
''')
        ClassNode sample = type(tree, 'Sample')
        ModuleNode bare = module('package other\n\nclass Other {}\n')

        expect:
        TypeText.render(sample.getField('table').originType, tree) == 'Map<String, List<Integer>>'
        TypeText.render(sample.getField('unit').originType, tree) == 'TimeUnit'
        TypeText.render(sample.getField('state').originType, tree) == 'java.lang.Thread.State'
        TypeText.render(ClassHelper.int_TYPE.makeArray(), tree) == 'int[]'
        TypeText.render(null, tree) == 'def'
        TypeText.importFor(sample.getField('unit').originType, bare) == 'import java.util.concurrent.TimeUnit'
        TypeText.importFor(sample.getField('day').originType, bare) == 'import java.time.LocalDate'
        TypeText.importFor(sample.getField('amount').originType, bare) == null
        TypeText.importFor(sample, bare) == 'import demo.Sample'
        TypeText.importFor(sample, tree) == null
        TypeText.packageOf(bare) == 'other.'
    }

    def 'imports go in sorted among their kind, after the package when there are none, before the code without one'() {
        expect:
        insert(text, statements) == expected

        where:
        text                                                         | statements                                   || expected
        'package a\n\nimport b.B\nimport d.D\n\nclass X {}\n'        | ['import c.C', 'import e.E', 'import d.D']   || 'package a\n\nimport b.B\nimport c.C\nimport d.D\nimport e.E\n\nclass X {}\n'
        'package a\n\nimport b.B\n\nclass X {}\n'                    | ['import static c.C.go', 'import a.A']       || 'package a\n\nimport a.A\nimport b.B\nimport static c.C.go\n\nclass X {}\n'
        'package a\n\nimport static b.B.go\n\nclass X {}\n'          | ['import c.C']                               || 'package a\n\nimport c.C\nimport static b.B.go\n\nclass X {}\n'
        'package a\nclass X {}\n'                                    | ['import c.C']                               || 'package a\n\nimport c.C\n\nclass X {}\n'
        '// header\n\nclass X {}\n'                                  | ['import c.C']                               || '// header\n\nimport c.C\n\nclass X {}\n'
        'package a\n\nimport c.C\n\nclass X {}\n'                    | ['import c.C']                               || 'package a\n\nimport c.C\n\nclass X {}\n'
    }

    static String insert(String text, List<String> statements) {
        def builder = new Changes.Builder()
        Imports.add(builder, URI, text, module(text), statements)
        Changes changes = builder.build()
        return changes.files().isEmpty() ? text : changes.files()[0].text()
    }

    def 'overlapping edits of one document are refused and a slice applies only the spans inside it'() {
        when:
        new Changes.Builder().replace(URI, 'abcdef', 1, 4, 'x').replace(URI, 'abcdef', 3, 5, 'y').build()

        then:
        IllegalStateException e = thrown()
        e.message == 'two edits of Sample.groovy overlap'

        expect:
        Changes.slice('abcdef', 1, 5, [new Changes.Span(2, 3, 'X'), new Changes.Span(4, 6, 'Y')]) == 'bXde'
        Changes.none().files().isEmpty()
    }
}
