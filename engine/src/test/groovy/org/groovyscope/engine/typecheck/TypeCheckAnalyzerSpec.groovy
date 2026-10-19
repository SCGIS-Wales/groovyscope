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
package org.groovyscope.engine.typecheck

import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification

class TypeCheckAnalyzerSpec extends Specification {

    def 'statically typed classes get checked, dynamic ones are left alone'() {
        given:
        def model = new WorkspaceModel(Fixtures.project('lint-sample'), EngineConfig.DEFAULTS)
        String typed = Fixtures.uri('lint-sample', 'src/main/groovy/sample/Typed.groovy')
        String messy = Fixtures.uri('lint-sample', 'src/main/groovy/sample/Messy.groovy')

        when:
        def result = new TypeCheckAnalyzer().analyze(model.sources(), [], false, EngineConfig.TypeCheck.DEFAULTS)

        then:
        result[typed].any { it.message().contains('lenght') && it.source() == TypeCheckAnalyzer.SOURCE }
        result[typed].every { it.code() == 'typecheck' && it.uri() == typed }
        // groovy-typecheckers 6.0.0's FormatStringChecker throws on this workspace; that used to be dropped silently
        // and is now one warning on the first document instead of a clean-looking result.
        result[messy].every { it.code() == 'typecheck-incomplete' && it.message().contains('NullPointerException') }
    }

    def 'only sources that mention static typing can need the type check'() {
        expect:
        TypeCheckAnalyzer.mayNeedTypeCheck('@CompileStatic\nclass A {}')
        TypeCheckAnalyzer.mayNeedTypeCheck('import groovy.transform.TypeChecked\n@TypeChecked class B {}')
        !TypeCheckAnalyzer.mayNeedTypeCheck('class C { def run() { 1 } }')
        !TypeCheckAnalyzer.mayNeedTypeCheck(null)
    }

    def 'an empty workspace yields nothing'() {
        expect:
        new TypeCheckAnalyzer().analyze([:], [], false, EngineConfig.TypeCheck.DEFAULTS).isEmpty()
    }

    def 'settings choose the extensions'() {
        expect:
        TypeCheckAnalyzer.extensionsFor(new EngineConfig.TypeCheck('strict', true, true)) ==
            [TypeCheckAnalyzer.STRICT_NULL_CHECKER, TypeCheckAnalyzer.SQL_INJECTION_CHECKER, TypeCheckAnalyzer.PURITY_CHECKER,
             TypeCheckAnalyzer.MODIFIES_CHECKER, TypeCheckAnalyzer.REGEX_CHECKER, TypeCheckAnalyzer.FORMAT_STRING_CHECKER,
             TypeCheckAnalyzer.COMBINER_CHECKER]
        TypeCheckAnalyzer.extensionsFor(new EngineConfig.TypeCheck('lenient', false, false, false, false, false)) == [TypeCheckAnalyzer.NULL_CHECKER]
        TypeCheckAnalyzer.extensionsFor(new EngineConfig.TypeCheck('off', false, false, false, false, false)).empty
        TypeCheckAnalyzer.extensionsFor(EngineConfig.TypeCheck.DEFAULTS, new EngineConfig.Groovy('5.1', [])).empty
        !TypeCheckAnalyzer.extensionsFor(EngineConfig.TypeCheck.DEFAULTS, new EngineConfig.Groovy('6.0', ['5.1'])).empty
    }

    static final Map<String, String> CHECKED = [
        'file:///w/Nulls.groovy' : 'import groovy.transform.CompileStatic\n@CompileStatic\nclass Nulls {\n  int len(String t) { String u = t ? null : "x"; u.length() }\n}',
        'file:///w/Regex.groovy' : 'import groovy.transform.CompileStatic\n@CompileStatic\nclass Regex { boolean m(String s) { s ==~ /a(b/ } }',
        'file:///w/Fmt.groovy'   : 'import groovy.transform.CompileStatic\n@CompileStatic\nclass Fmt { String f() { String.format("%d items", "three") } }',
        'file:///w/Comb.groovy'  : 'import groovy.transform.CompileStatic\n@CompileStatic\nclass Comb { int c(List<Integer> n) { n.injectParallel(0) { int a, int b -> a - b } } }',
        'file:///w/SqlUse.groovy': 'import groovy.transform.CompileStatic\nimport groovy.sql.Sql\n@CompileStatic\nclass SqlUse { List q(Sql sql, String name) { sql.rows("SELECT * FROM t WHERE n = \'$name\'".toString()) } }',
    ]

    def 'each Groovy 6 checker reports under its own code, and strict null checking needs no annotations'() {
        when:
        def strict = new TypeCheckAnalyzer().analyze(CHECKED, [], false, EngineConfig.TypeCheck.DEFAULTS)
        def lenient = new TypeCheckAnalyzer().analyze(CHECKED, [], false, new EngineConfig.TypeCheck('lenient', true, false))

        then:
        strict['file:///w/Nulls.groovy']*.code() == ['NullChecker']
        strict['file:///w/Nulls.groovy'][0].message().startsWith('Potential null dereference')
        lenient['file:///w/Nulls.groovy'].empty
        strict['file:///w/Regex.groovy']*.code() == ['RegexChecker']
        strict['file:///w/Fmt.groovy']*.code() == ['FormatStringChecker']
        strict['file:///w/Comb.groovy']*.code() == ['CombinerChecker']
        strict['file:///w/SqlUse.groovy']*.code() == ['SqlInjectionChecker']
        strict.values().flatten().every { !it.message().startsWith('[Static type checking]') }
    }

    def 'a file that does not resolve no longer keeps the rest from being checked'() {
        given:
        def sources = CHECKED + [
            'file:///w/Broken.groovy': 'import does.not.Exist\nclass Broken { Exist e }',
            'file:///w/UsesBroken.groovy': 'class UsesBroken { Broken b }',
        ]

        when:
        def skipping = new TypeCheckAnalyzer().analyze(sources, [], false, EngineConfig.TypeCheck.DEFAULTS,
                EngineConfig.Groovy.DEFAULTS, ['file:///w/Broken.groovy'] as Set)
        def retrying = new TypeCheckAnalyzer().analyze(sources, [], false, EngineConfig.TypeCheck.DEFAULTS,
                EngineConfig.Groovy.DEFAULTS, [] as Set)
        def groovy5 = new TypeCheckAnalyzer().analyze(CHECKED, [], false, EngineConfig.TypeCheck.DEFAULTS,
                new EngineConfig.Groovy('5.1', []), [] as Set)

        then: 'checking still runs on the healthy files, whether the broken one is skipped up front or dropped on retry'
        skipping['file:///w/Nulls.groovy']*.code() == ['NullChecker']
        retrying['file:///w/Nulls.groovy']*.code() == ['NullChecker']
        retrying['file:///w/Broken.groovy'].empty

        and: 'a Groovy 5 target gets the core type checker only'
        groovy5.values().flatten().every { it.code() == 'typecheck' }
    }

    def 'the customizer extends existing annotations without duplicates'() {
        given:
        def customizer = new TypeCheckExtensionCustomizer(['a.B', 'c.D'])
        def classNode = new ClassNode('p.C', 1, ClassHelper.OBJECT_TYPE)
        def annotation = new AnnotationNode(ClassHelper.make('groovy.transform.CompileStatic'))
        annotation.setMember('extensions', new ConstantExpression('c.D'))
        classNode.addAnnotation(annotation)
        def unrelated = new AnnotationNode(ClassHelper.make('java.lang.Deprecated'))
        classNode.addAnnotation(unrelated)

        when:
        customizer.call(null, null, classNode)

        then:
        (annotation.getMember('extensions') as ListExpression).expressions*.value == ['c.D', 'a.B']
        unrelated.getMember('extensions') == null

        and: 'nothing happens without extensions'
        new TypeCheckExtensionCustomizer([]).call(null, null, classNode)
        TypeCheckExtensionCustomizer.existing(null).empty
    }
}
