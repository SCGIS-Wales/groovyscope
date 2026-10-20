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
package org.groovyscope.engine.services

import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification

/** A joint Groovy/Java project: Java classes resolve from Groovy, but Java files are never reported on. */
class JointJavaSpec extends Specification {

    static final String SHAPE = 'src/main/java/shapes/Shape.java'
    static final String GEOMETRY = 'src/main/java/shapes/Geometry.java'
    static final String CIRCLE = 'src/main/groovy/shapes/Circle.groovy'
    static final String REPORT = 'src/main/groovy/shapes/Report.groovy'
    static final String TEST = 'src/test/groovy/shapes/CircleTest.groovy'

    private static String uri(String relative) {
        Fixtures.uri('joint-java', relative)
    }

    def 'Java sources join the compile and Groovy code resolves them'() {
        given:
        def services = Fixtures.services('joint-java')
        def interfacePosition = Fixtures.pos('joint-java', CIRCLE, 'Shape', 1)
        def totalPosition = Fixtures.pos('joint-java', REPORT, 'total(', 1)

        when:
        def shape = services.definition(uri(CIRCLE), interfacePosition)
        def total = services.definition(uri(REPORT), totalPosition)

        then:
        services.workspace().compileSources().keySet().containsAll([uri(SHAPE), uri(GEOMETRY)])
        !services.workspace().sources().containsKey(uri(SHAPE))
        shape.present && shape.get().symbol().location().uri() == uri(SHAPE)
        total.present && total.get().symbol().location().uri() == uri(GEOMETRY)
        total.get().confidence() != Confidence.DYNAMIC_UNKNOWN
    }

    def 'only Groovy files get diagnostics, lint, formatting and dynamic-code findings'() {
        given:
        def services = Fixtures.services('joint-java')

        when:
        def diagnostics = services.diagnostics()
        def check = services.quality().check([], null, [] as Set)

        then: 'the genuinely broken test file is reported; the Java files are not, whatever Groovy thinks of them'
        !diagnostics[uri(TEST)].empty
        !diagnostics.containsKey(uri(SHAPE)) && !diagnostics.containsKey(uri(GEOMETRY))
        diagnostics[uri(CIRCLE)].empty && diagnostics[uri(REPORT)].empty
        !check.diagnostics().keySet().any { it.endsWith('.java') }
        services.quality().lint(uri(GEOMETRY), 'class X { ; }', null).empty
        services.quality().format(uri(GEOMETRY)).empty
        !services.dynamicCode([]).any { it.location().uri().endsWith('.java') }
    }

    def 'workspace.javaSources: false keeps Java out of the compile'() {
        given:
        def base = EngineConfig.DEFAULTS
        def config = base.toBuilder().javaSources(false).build()
        def services = new LanguageServices(new WorkspaceModel(Fixtures.project('joint-java'), config), new GroovyCompiler())

        when:
        def sources = services.workspace().compileSources()
        def diagnostics = services.diagnostics()

        then:
        !sources.keySet().any { it.endsWith('.java') }
        diagnostics[uri(CIRCLE)]*.message().any { it.contains('Shape') }
    }

    def 'the javaSources setting is read from the configuration JSON'() {
        expect:
        EngineConfig.fromJson('{"workspace": {"javaSources": false}}').javaSources() == false
        EngineConfig.fromJson('{}').javaSources()
        EngineConfig.DEFAULTS.sourceRoots().containsAll(['src/main/java', 'src/test/java'])
        EngineConfig.isJavaFileName('Shape.java') && !EngineConfig.isJavaFileName('.java') && !EngineConfig.isJavaFileName('Shape.groovy')
        WorkspaceModel.isJavaUri('file:///w/Shape.java') && !WorkspaceModel.isJavaUri('file:///w/Shape.groovy')
    }
}
