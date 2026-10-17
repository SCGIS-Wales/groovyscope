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
        result[messy].empty
    }

    def 'an empty workspace yields nothing'() {
        expect:
        new TypeCheckAnalyzer().analyze([:], [], false, EngineConfig.TypeCheck.DEFAULTS).isEmpty()
    }

    def 'settings choose the extensions'() {
        expect:
        TypeCheckAnalyzer.extensionsFor(new EngineConfig.TypeCheck('strict', true, true)) ==
            [TypeCheckAnalyzer.NULL_CHECKER, TypeCheckAnalyzer.SQL_INJECTION_CHECKER, TypeCheckAnalyzer.PURITY_CHECKER, TypeCheckAnalyzer.MODIFIES_CHECKER]
        TypeCheckAnalyzer.extensionsFor(new EngineConfig.TypeCheck('off', false, false)).empty
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
