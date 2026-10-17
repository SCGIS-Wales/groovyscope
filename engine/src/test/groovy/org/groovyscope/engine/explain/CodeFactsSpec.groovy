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
package org.groovyscope.engine.explain

import java.nio.file.Path
import org.groovyscope.engine.callgraph.CallGraph
import org.groovyscope.engine.search.SearchFixtures
import org.groovyscope.engine.services.Snapshot
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** Facts about classes and methods: members, calls, callers, field writes, exceptions, externals, branches. */
class CodeFactsSpec extends Specification {

    @Shared @TempDir Path root
    @Shared Snapshot snapshot
    @Shared CallGraph graph

    static final String SOURCE = '''\
package shop

import groovy.sql.Sql

interface Priced {
    BigDecimal price()
}

abstract class Base {
    protected int counter = 0
}

@Deprecated
class OrderService extends Base implements Priced {
    private final Sql sql
    List<String> names = []
    static final String PREFIX = 'ORD-'

    OrderService(Sql sql) {
        this.sql = sql
    }

    BigDecimal price() {
        return 1.0
    }

    BigDecimal total(List<BigDecimal> prices, boolean round) {
        BigDecimal sum = 0
        for (BigDecimal p : prices) {
            if (p != null && p > 0) {
                sum += p
            }
        }
        counter++
        this.names << 'x'
        return round ? sum.setScale(2) : sum
    }

    void store(String path) {
        try {
            new File(path).text = total([], false).toString()
            sql.rows('select 1')
            System.getenv('HOME')
        } catch (IOException e) {
            throw new IllegalStateException('cannot store', e)
        }
    }

    void loadAll() { total([1.0], true) }
    void fetchAll() { total([2.0], true) }
    private void helper() { }
}

class Caller {
    void run(OrderService service) { service.total([], false) }
}
'''

    def setupSpec() {
        snapshot = SearchFixtures.compile(root, ['shop/OrderService.groovy': SOURCE])
        graph = CallGraph.build(snapshot.index(), snapshot.resolver(), snapshot.symbols())
    }

    CodeFacts.ClassFacts classFacts(String name) {
        CodeFacts.ofClass(snapshot.index().classNamed(name).get(), snapshot.index(), graph)
    }

    def 'class facts list members, inheritance, responsibilities and the classes it exchanges with'() {
        when:
        def facts = classFacts('shop.OrderService')

        then:
        facts.kind == 'class'
        facts.simpleName == 'OrderService'
        facts.packageName == 'shop'
        facts.superClass == 'shop.Base'
        facts.interfaces == ['shop.Priced']
        facts.annotations == ['Deprecated']
        facts.fields*.name == ['names', 'PREFIX', 'sql']
        facts.fields.find { it.name == 'sql' }.with { isFinal && visibility == 'private' && !isProperty }
        facts.fields.find { it.name == 'names' }.isProperty
        facts.fields.find { it.name == 'PREFIX' }.with { isStatic && isFinal && isProperty }
        facts.methods.find { it.isConstructor }.name == '<init>'
        facts.methods*.name.containsAll(['price', 'total', 'store', 'loadAll', 'fetchAll', 'helper'])
        facts.responsibilities*.verb == ['fetch', 'load', 'price', 'store', 'total']
        facts.usedBy == ['shop.Caller']
        facts.uses.containsAll(['groovy.sql.Sql', 'java.io.File'])
        !facts.uses.contains('shop.OrderService')
        facts.callerCount >= 3
        facts.endLine > facts.startLine
    }

    def 'method facts carry calls, callers, reads, writes, branches, exceptions and externals'() {
        when:
        def total = classFacts('shop.OrderService').methods.find { it.name == 'total' }
        def store = classFacts('shop.OrderService').methods.find { it.name == 'store' }

        then:
        total.visibility == 'public'
        total.returns == 'BigDecimal'
        total.parameters*.name == ['prices', 'round']
        total.parameters*.type == ['List<BigDecimal>', 'boolean']
        total.branches == 4   // for, if, &&, ternary
        total.writes.containsAll(['counter'])
        total.reads.containsAll(['counter', 'names'])
        total.callerCount == 4
        total.callers.containsAll(['shop.OrderService.loadAll', 'shop.OrderService.fetchAll', 'shop.Caller.run'])
        store.throwsTypes == ['IllegalStateException']
        store.catches == ['IOException']
        store.externals.containsAll(['file', 'sql', 'environment'])
        store.calls.any { it.target == 'shop.OrderService.total' && it.confidence == 'STATIC' }
        store.branches == 1
    }

    def 'helpers split identifiers and categorise external calls'() {
        expect:
        CodeFacts.leadingWord('resolveApiAssignments') == 'resolve'
        CodeFacts.leadingWord('X') == 'x'
        CodeFacts.leadingWord('') == ''
        CodeFacts.externalOf('java.net.http.HttpClient', 'send', null) == 'http'
        CodeFacts.externalOf('java.lang.System', 'currentTimeMillis', null) == 'clock'
        CodeFacts.externalOf('java.lang.System', 'exit', null) == 'exit'
        CodeFacts.externalOf('java.util.UUID', 'randomUUID', null) == 'random'
        CodeFacts.externalOf('java.lang.String', 'execute', null) == 'process'
        CodeFacts.externalOf(null, 'warn', 'log') == 'logging'
        CodeFacts.externalOf('java.lang.String', 'trim', null) == null
        CodeFacts.weaker('STATIC', 'DYNAMIC_UNKNOWN') == 'DYNAMIC_UNKNOWN'
        CodeFacts.ownerOf('shop.OrderService.total') == 'shop.OrderService'
    }
}
