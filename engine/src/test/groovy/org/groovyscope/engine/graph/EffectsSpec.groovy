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
import org.groovyscope.engine.explain.CodeFacts
import org.groovyscope.engine.search.SearchFixtures
import org.groovyscope.engine.services.Snapshot
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** Read and write effects of methods and calls, propagated up the call graph. */
class EffectsSpec extends Specification {

    @Shared @TempDir Path root
    @Shared Snapshot snapshot
    @Shared CallGraph graph
    @Shared Effects effects

    static final String MAIN = '''\
package app

class Store {
    private final Map<String, String> cache = [:]
    int hits
    String title

    Store() { hits = 0 }
    String get(String key) { cache[key] }
    void put(String key, String value) { cache.put(key, value) }
    void count() { hits++ }
    void retitle(String value) { this.title = value }
    void collect(List<String> into) { into.add(title) }
    String scratch() { def item = new Item(); item.name = 'x'; item.name }
    void save(File file) { file.write(title) }
    void configure() { System.setProperty('app.mode', 'test') }
    String clean(String text) { text.replaceAll('x', '') }
    def bag = []
    void fill(item) { bag.add(item) }
    def poke(String name) { this."$name"() }
}

class Item {
    String name
}

class Loaded {
    List<String> lines = []
    Loaded(String text) { load(text) }
    private void load(String text) { lines.addAll(text.readLines()) }
    static Loaded of(String text) { new Loaded(text) }
}

class Factory {
    static Loaded from(String text) { new Loaded(text) }
}

class Service {
    Store store = new Store()
    String lookup(String key) { store.get(key) }
    void remember(String key) { store.put(key, 'v') }
    void rename(Item item) { item.setName('renamed') }
    void outer(String key) { remember(key) }
    def guess(holder) { holder.scratch() }
}
'''

    def setupSpec() {
        snapshot = SearchFixtures.compile(root, ['src/main/groovy/app/Main.groovy': MAIN])
        graph = CallGraph.build(snapshot.index(), snapshot.resolver(), snapshot.symbols())
        effects = Effects.of(RelationshipGraph.workspaceClasses(snapshot.index()), graph)
    }

    def 'a method writes when it changes a field, a parameter, a file or the environment'() {
        expect:
        effects.method("app.Store#$name" as String) == effect

        where:
        name        | effect
        'get'       | 'read'
        'put'       | 'write'
        'count'     | 'write'
        'retitle'   | 'write'
        'collect'   | 'write'
        'scratch'   | 'read'
        'save'      | 'write'
        'configure' | 'write'
        'clean'     | 'read'
        'fill'      | 'write'
        'poke'      | 'read'
        '<init>'    | 'read'
    }

    def 'state changes name what changed'() {
        when:
        def store = snapshot.index().classNamed('app.Store').get()
        def changes = { String name -> CodeFacts.stateChanges(store.getMethods(name)[0]) as List }

        then:
        changes('put') == ['field cache']
        changes('collect') == ['parameter into']
        changes('save') == ['file']
        changes('configure') == ['environment']
        changes('scratch') == []
        CodeFacts.stateChanges(snapshot.index().classNamed('app.Service').get().getMethods('rename')[0]) as List ==
                ['property name']
    }

    def 'writes propagate to callers, setters write and dynamic calls are unknown'() {
        expect:
        effects.method('app.Service#lookup') == 'read'
        effects.method('app.Service#remember') == 'write'
        effects.method('app.Service#outer') == 'write'
        effects.method('app.Service#rename') == 'write'
        effects.method('app.Service#guess') == 'unknown'
        effects.method('app.Nowhere#none') == null
    }

    def 'a constructor filling its own fields through its own methods is not a write'() {
        expect:
        effects.method('app.Loaded#load') == 'write'
        effects.method('app.Loaded#<init>') == 'read'
        effects.method('app.Factory#from') == 'read'
        effects.method('app.Loaded#of') == 'read'
    }

    def 'a call takes its callee effect'() {
        when:
        def calls = graph.edges().findAll { RelationshipGraph.ownerOf(it.caller()) == 'app.Service' }
                .collectEntries { ["${it.caller().name()}>${it.callee().name()}" as String, effects.call(it)] }

        then:
        calls['lookup>get'] == 'read'
        calls['remember>put'] == 'write'
        calls['outer>remember'] == 'write'
        calls['guess>scratch'] == 'unknown'
    }

    def 'combining keeps the strongest effect'() {
        expect:
        Effects.combine(a, b) == combined

        where:
        a         | b         | combined
        null      | 'read'    | 'read'
        'read'    | null      | 'read'
        'read'    | 'read'    | 'read'
        'read'    | 'unknown' | 'unknown'
        'unknown' | 'write'   | 'write'
        'write'   | 'read'    | 'write'
    }
}
