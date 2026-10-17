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
package org.groovyscope.engine.callgraph

import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.model.ImpactReport
import org.groovyscope.engine.model.SymbolKind
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** Call graph and impact analysis over the basic-app fixture. */
class ImpactAnalyzerSpec extends Specification {

    static final String MAIN = 'src/main/groovy/app/Main.groovy'
    static final String GREETER = 'src/main/groovy/app/Greeter.groovy'
    static final String EXTRAS = 'src/main/groovy/app/Extras.groovy'
    static final String SPEC = 'src/test/groovy/app/GreeterSpec.groovy'

    @Shared
    LanguageServices services = Fixtures.services('basic-app')

    private static String uri(String relative) {
        Fixtures.uri('basic-app', relative)
    }

    def 'the call graph records every call with its confidence and is reused per snapshot'() {
        when:
        def graph = services.callGraph()

        then:
        graph.edgeCount() > 10
        graph.dynamicEdgeCount() >= 1
        graph.edges().every { it.site() != null && it.caller() != null && it.callee() != null }
        services.callGraph().is(graph)
    }

    def 'impact of changing a method signature lists callers, tests and name-only sites'() {
        when:
        def reports = services.impactOf('app.Greeter.greet', ImpactReport.Change.SIGNATURE)
        def report = reports.find { it.symbol().detail() == 'String greet(String name)' }

        then:
        reports.size() == 2
        report.directCallers()*.site()*.uri().toSet() == [uri(MAIN), uri(GREETER), uri(SPEC)] as Set
        report.directCallers().every { it.confidence() != Confidence.DYNAMIC_UNKNOWN }
        report.dynamicUnknowns().size() == 1
        report.dynamicUnknowns()[0].site().uri() == uri(MAIN)
        report.tests()*.caller()*.container().contains('app.GreeterSpec')
        report.transitiveCallers()*.callee()*.detail().contains('String greet()')
        report.riskLevel() in ['medium', 'high']
        report.suggestedChecks().any { it.contains('matched by name only') }
        report.suggestedChecks().any { it.contains('test file') }
    }

    def 'impact of a class counts type usages and constructor calls'() {
        when:
        def report = services.impactOf('app.model.Person', ImpactReport.Change.RENAME)[0]

        then:
        report.symbol().kind() == SymbolKind.CLASS
        report.directCallers().size() >= 3
        report.directCallers()*.site()*.uri().toSet().containsAll([uri(MAIN), uri(EXTRAS)])
        report.suggestedChecks().any { it.contains('rename') }
    }

    def 'overrides are found in both directions'() {
        expect:
        services.impactOf('app.Derived.label', ImpactReport.Change.SIGNATURE)[0].overrides()*.container() == ['app.Base']
        services.impactOf('app.Base#label', ImpactReport.Change.DELETE)[0].overrides()*.container() == ['app.Derived']
        services.impactOf('Derived.base', ImpactReport.Change.BEHAVIOUR).empty
    }

    def 'impact at a cursor position and by simple name agree'() {
        given:
        def position = Fixtures.pos('basic-app', MAIN, "greeter.greet('Ada')", 1, 8)

        when:
        def atCursor = services.impact(uri(MAIN), position, ImpactReport.Change.BEHAVIOUR).get()
        def byName = services.impactOf('Greeter.greet', ImpactReport.Change.BEHAVIOUR)

        then:
        atCursor.symbol().detail() == 'String greet(String name)'
        byName*.symbol()*.detail().contains(atCursor.symbol().detail())
        atCursor.riskScore() <= services.impact(uri(MAIN), position, ImpactReport.Change.DELETE).get().riskScore()
        services.impactOf('', ImpactReport.Change.SIGNATURE).empty
        services.impactOf('no.such.Thing', ImpactReport.Change.SIGNATURE).empty
    }

    def 'change kinds parse leniently and reject nonsense'() {
        expect:
        ImpactReport.Change.parse(null) == ImpactReport.Change.SIGNATURE
        ImpactReport.Change.parse(' Rename ') == ImpactReport.Change.RENAME

        when:
        ImpactReport.Change.parse('explode')

        then:
        thrown(IllegalArgumentException)
    }

    def 'risk scoring and levels'() {
        expect:
        ImpactAnalyzer.level(0) == 'low'
        ImpactAnalyzer.level(25) == 'medium'
        ImpactAnalyzer.level(60) == 'high'
    }

    def 'call hierarchy items and incoming and outgoing calls'() {
        given:
        def position = Fixtures.pos('basic-app', GREETER, 'String greet(String name)', 1, 7)

        when:
        def item = services.callHierarchyItem(uri(GREETER), position).get()
        def incoming = services.incomingCalls(item)
        def outgoingFromMain = services.outgoingCalls(services.callHierarchyItem(uri(MAIN), Fixtures.pos('basic-app', MAIN, 'def greeter')).get())

        then:
        item.name() == 'greet'
        incoming*.site()*.uri().toSet().containsAll([uri(MAIN), uri(GREETER)])
        outgoingFromMain*.callee()*.name().containsAll(['greet', 'Person', 'area'])
        services.callHierarchyItem(uri(MAIN), new org.groovyscope.engine.model.Position(0, 0)).isEmpty()
        services.incomingCalls(new org.groovyscope.engine.model.Symbol('x', SymbolKind.METHOD, null, null, null, null, [])).empty
    }

    def 'semantic tokens cover declarations and usages on single lines'() {
        when:
        def data = services.semanticTokens(uri(GREETER))

        then:
        data.size() % 5 == 0
        data.size() >= 5 * 10
        data.every { it >= 0 }
        services.semanticTokens('file:///nowhere.groovy').empty
    }
}
