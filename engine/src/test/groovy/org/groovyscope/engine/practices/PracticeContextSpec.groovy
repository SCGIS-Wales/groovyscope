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
package org.groovyscope.engine.practices

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import spock.lang.Specification

class PracticeContextSpec extends Specification {

    private static PracticeContext context(String uri, String profile = null, EngineConfig config = EngineConfig.DEFAULTS) {
        PracticeContext.of(uri, new ModuleNode((org.codehaus.groovy.control.SourceUnit) null), 'x = 1\n', config,
                profile, [])
    }

    def 'documents are classified once, from the profile or else the path'() {
        expect:
        context('file:///p/Jenkinsfile').jenkins()
        context('file:///lib/vars/deploy.groovy').jenkins()
        context('file:///p/build.gradle').gradle()
        context('file:///lib/src/org/acme/Deployer.groovy', 'jenkins').jenkins()
        !context('file:///p/src/main/groovy/A.groovy').jenkins()
        context('file:///p/src/test/groovy/ASpec.groovy').test()
        !context('file:///p/src/main/groovy/A.groovy').test()
        context('file:///p/src/main/groovy/A.groovy').profile() == PracticeContext.DEFAULT
    }

    def 'a specification is found through its superclass chain'() {
        given:
        def spec = new ClassNode('ASpec', 1, ClassHelper.make('spock.lang.Specification'))
        def nested = new ClassNode('BSpec', 1, spec)

        expect:
        PracticeContext.isSpecification(spec)
        PracticeContext.isSpecification(nested)
        !PracticeContext.isSpecification(new ClassNode('Plain', 1, ClassHelper.OBJECT_TYPE))
    }

    def 'severity ceilings and floors bend the configured severity, and off stays off'() {
        given:
        def node = new ConstantExpression(1)
        node.setSourcePosition(new ConstantExpression(2).with { lineNumber = 1; columnNumber = 1; lastLineNumber = 1; lastColumnNumber = 2; it })
        def tuned = EngineConfig.fromJson('{"practices": {"severity": {"GS001": "off"}}}')
        def ctx = context('file:///p/src/main/groovy/A.groovy')
        def silent = context('file:///p/src/main/groovy/A.groovy', null, tuned)

        when:
        ctx.reportAtMost(Diagnostic.Severity.INFORMATION, 'GS002', node, 'm')
        ctx.reportAtLeast(Diagnostic.Severity.ERROR, 'GS002', node, 'm')
        ctx.report('GS002', node, 'm')
        silent.report('GS001', node, 'm')
        silent.reportAtLeast(Diagnostic.Severity.ERROR, 'GS001', node, 'm')

        then:
        ctx.out()*.diagnostic()*.severity() == [Diagnostic.Severity.INFORMATION, Diagnostic.Severity.ERROR,
                                                 Diagnostic.Severity.WARNING]
        ctx.out().every { it.diagnostic().message().startsWith('Elvis on a value') && it.diagnostic().source() == 'groovyscope' }
        silent.out().empty
        ctx.source(node) == 'x'
    }
}
