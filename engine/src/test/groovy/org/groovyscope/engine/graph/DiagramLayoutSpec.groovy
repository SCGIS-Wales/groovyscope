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
package org.groovyscope.engine.graph

import spock.lang.Specification

/** The layered layout behind draw.io diagrams: no edge through a box, no two edges on one track. */
class DiagramLayoutSpec extends Specification {

    static final List<String> WRITERS = ['Base', 'Html', 'Json', 'Text', 'Xml', 'Sortable', 'Plain', 'Sarif', 'Gitlab',
                                         'Compact', 'Ide', 'Inline']

    private static DiagramLayout.Box box(String id) {
        new DiagramLayout.Box(id, 140, 46)
    }

    private static boolean through(double[] a, double[] b, DiagramLayout.Position p, double w, double h) {
        Math.min(a[0], b[0]) < p.x() + w - 0.5 && Math.max(a[0], b[0]) > p.x() + 0.5 &&
                Math.min(a[1], b[1]) < p.y() + h - 0.5 && Math.max(a[1], b[1]) > p.y() + 0.5
    }

    def 'a hierarchy is drawn with bases above, subclasses meeting in one port and no edge through a box'() {
        given: 'Base with four subclasses, each with two of its own'
        def links = []
        ['Html', 'Json', 'Text', 'Xml'].eachWithIndex { child, i -> links << new DiagramLayout.Link("b$i", child, 'Base', true) }
        [['Sortable', 'Html'], ['Plain', 'Html'], ['Sarif', 'Json'], ['Gitlab', 'Json'], ['Compact', 'Text'],
         ['Ide', 'Text'], ['Inline', 'Xml']].eachWithIndex { pair, i ->
            links << new DiagramLayout.Link("c$i", pair[0], pair[1], true)
        }

        when:
        def result = DiagramLayout.layout(WRITERS.collect { box(it) }, links, DiagramLayout.Options.DEFAULTS)

        then: 'every base sits above its subclasses'
        links.every { result.boxes()[it.target()].y() < result.boxes()[it.source()].y() }

        and: 'each route starts at the subclass and ends at the base, and no route passes through another box'
        result.routes().size() == links.size()
        links.every { link ->
            def points = result.routes()[link.id()]
            def others = result.boxes().findAll { id, p -> id != link.source() && id != link.target() }
            [points, points.drop(1)].transpose().every { a, b -> others.every { id, p -> !through(a, b, p, 140, 46) } }
        }

        and: 'the subclasses of one base meet it at one point'
        ['b0', 'b1', 'b2', 'b3'].collect { result.routes()[it].last().toList() }.unique().size() == 1
    }

    def 'calls from one method leave on separate tracks, and a bound on boxes per layer narrows a wide drawing'() {
        given: 'one entry calling twelve helpers'
        def boxes = [box('entry')] + (1..12).collect { box("helper$it") }
        def links = (1..12).collect { new DiagramLayout.Link("e$it", 'entry', "helper$it", false) }

        when:
        def free = DiagramLayout.layout(boxes, links, DiagramLayout.Options.DEFAULTS)
        def bounded = DiagramLayout.layout(boxes, links, new DiagramLayout.Options(36, 56, 10, 0, 4))

        then: 'the vertical runs of different calls never share an x'
        def runs = free.routes().values().collect { points -> points.last()[0] }
        runs.unique().size() == 12

        and:
        bounded.width() < free.width()
        bounded.routes().size() == 12
    }
}
