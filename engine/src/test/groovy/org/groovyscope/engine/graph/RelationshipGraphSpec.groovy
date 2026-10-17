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

import java.nio.file.Path
import org.groovyscope.engine.callgraph.CallGraph
import org.groovyscope.engine.search.SearchFixtures
import org.groovyscope.engine.services.Snapshot
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** Class relationships: inheritance, composition, use and calls, scoped and capped. */
class RelationshipGraphSpec extends Specification {

    @Shared @TempDir Path root
    @Shared Snapshot snapshot
    @Shared CallGraph graph

    static final String MAIN = '''\
package app

interface Report { String render() }

abstract class Base { }

class Repository {
    List<Item> items = []
    Item find(String id) { items.find { it.id == id } }
}

class Item {
    String id
}

class Service extends Base implements Report {
    private final Repository repository = new Repository()
    String render() { repository.find('x')?.id }
    Item pick(Item item) { item }
}
'''
    static final String TEST = '''\
package app

class ServiceSpec {
    void run() { new Service().render() }
}
'''

    def setupSpec() {
        snapshot = SearchFixtures.compile(root, ['src/main/groovy/app/Main.groovy': MAIN,
                                                 'src/test/groovy/app/ServiceSpec.groovy': TEST])
        graph = CallGraph.build(snapshot.index(), snapshot.resolver(), snapshot.symbols())
    }

    def 'the whole workspace graph has every declared relation and aggregated calls'() {
        when:
        def built = RelationshipGraph.build(snapshot.index(), graph, [], [], 1, 60)
        def edges = built.edges.collect { "${it.from.replace('app.', '')} ${it.kind} ${it.to.replace('app.', '')}" as String }

        then:
        built.nodes*.name.sort() == ['Base', 'Item', 'Report', 'Repository', 'Service', 'ServiceSpec']
        built.nodes.find { it.name == 'ServiceSpec' }.test
        built.nodes.find { it.name == 'Report' }.kind == 'interface'
        built.nodes.find { it.name == 'Service' }.members == 3
        edges.containsAll(['Service extends Base', 'Service implements Report', 'Service has Repository',
                           'Repository has Item', 'Repository uses Item', 'Service uses Item',
                           'Service calls Repository', 'ServiceSpec calls Service'])
        built.edges.find { it.kind == 'calls' && it.from == 'app.Service' }.confidence == 'STATIC'
        !built.truncated
        built.totalClasses == 6
    }

    def 'a scoped graph keeps the seeds and their neighbours only, and the cap keeps the best connected'() {
        when:
        def seed = snapshot.index().classNamed('app.Repository').get()
        def near = RelationshipGraph.build(snapshot.index(), graph, [seed], ['has', 'calls'], 1, 60)
        def capped = RelationshipGraph.build(snapshot.index(), graph, [], [], 1, 2)
        def none = RelationshipGraph.build(snapshot.index(), graph, [seed], ['extends'], 1, 60)

        then:
        near.nodes*.name.sort() == ['Item', 'Repository', 'Service']
        near.edges.every { it.kind in ['has', 'calls'] }
        capped.nodes.size() == 2 && capped.truncated
        none.nodes*.name == ['Repository']
    }

    def 'the method level graph shows the methods of one class and collapses the rest'() {
        when:
        def seed = snapshot.index().classNamed('app.Service').get()
        def built = RelationshipGraph.methods(snapshot.index(), graph, [seed], 60)

        then:
        built.nodes.find { it.id == 'app.Service#render' }.kind == 'method'
        built.nodes.any { it.id == 'app.Repository' && it.kind == 'class' }
        built.edges.any { it.from == 'app.Service#render' && it.to == 'app.Repository' && it.kind == 'calls' }
        built.edges.any { it.from == 'app.ServiceSpec' && it.to == 'app.Service#render' }
    }
}
