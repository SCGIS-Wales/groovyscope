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
package org.groovyscope.engine.lsp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.config.EngineConfig
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** {@code groovyscope/design}: how a class is designed, with a refactor and its tool call per finding. */
@Timeout(120)
class DesignRequestSpec extends Specification {

    static final String JOB = '''\
package app

class Job {
    int count
    String label

    void increment() { count++ }
    int doubled() { count * 2 }
    void rename(String text) { label = text }
    String shout() { label.toUpperCase() }
}
'''
    static final String LEDGER = '''\
package app

class Ledger {
    private final List<String> names = []

    List<String> getNames() { names }
    String describe(Order order) { order.price + ' x ' + order.quantity + ' - ' + order.discount }
}
'''
    static final String ORDER = '''\
package app

class Order {
    BigDecimal price
    int quantity
    BigDecimal discount
}
'''

    @Shared @TempDir Path root
    @Shared GroovyscopeLanguageServer server

    def setupSpec() {
        Path app = Files.createDirectories(root.resolve('src/main/groovy/app'))
        Files.writeString(app.resolve('Job.groovy'), JOB)
        Files.writeString(app.resolve('Ledger.groovy'), LEDGER)
        Files.writeString(app.resolve('Order.groovy'), ORDER)
        server = new GroovyscopeLanguageServer({ int code -> } as IntConsumer, root, EngineConfig.DEFAULTS)
    }

    Map design(Map params) {
        server.design([root: root.toString()] + params).get(60, TimeUnit.SECONDS) as Map
    }

    String uri(String name) {
        root.resolve("src/main/groovy/app/${name}.groovy").toUri().toString()
    }

    def 'a class with two unrelated groups of methods reports LCOM4 2 with both groups'() {
        when:
        Map result = design(symbol: 'app.Job')
        Map job = result.items[0]

        then:
        result.found
        result.items.size() == 1
        result.elapsedMs instanceof Number
        job.class == 'app.Job'
        job.simpleName == 'Job'
        job.uri == uri('Job')
        job.path == 'src/main/groovy/app/Job.groovy'
        job.line == 3
        job.size == [methods: 4, fields: 2, lines: 9]
        job.responsibilities.collect { it.verb }.sort() == ['doubled', 'increment', 'rename', 'shout']
        job.cohesion.lcom4 == 2
        job.cohesion.groups == [[methods: ['increment', 'doubled'], fields: ['count']],
                                [methods: ['rename', 'shout'], fields: ['label']]]
        job.cohesion.byFields == job.cohesion.groups
        job.cohesion.separable == job.cohesion.groups
        job.suggestions == [[kind: 'split', message: 'rename, shout share no field and no call with increment,'
                + ' doubled: move them to a class of their own, with label',
                tool: 'move_member(symbol: "app.Job.rename", toClass: "app.<NewClass>")']]
        [job.fieldsUsedByOneMethod, job.featureEnvy, job.sharedParameterLists, job.typeSwitches,
         job.publicMutableFields, job.staticMutableState, job.longParameterLists, job.primitiveObsession,
         job.statelessMethods].every { it == [] }
        job.dataOwner == null
    }

    def 'feature envy, a collection handed out and a stateless method, each with its tool call'() {
        when:
        Map ledger = design(symbol: 'Ledger').items[0]

        then:
        ledger.featureEnvy == [[method: 'describe', line: 7, target: 'app.Order', targetUses: 3, ownUses: 0]]
        ledger.publicMutableFields == [[name: 'getNames', type: 'List<String>', line: 6, kind: 'getter', field: 'names']]
        ledger.statelessMethods == [[method: 'describe', line: 7, target: 'app.Order', callers: 0]]
        ledger.suggestions*.tool == [
                'move_member(symbol: "app.Ledger.describe", toClass: "app.Order")',
                'edit_symbol(path: "src/main/groovy/app/Ledger.groovy", symbol: "Ledger.getNames", operation:'
                        + ' "replace_body", text: "return names.asImmutable()")',
                'move_member(symbol: "app.Ledger.describe", toClass: "app.Order")']
    }

    def 'a member or a document finds its class; unknown names find nothing'() {
        expect:
        design(symbol: 'app.Job.shout').items*.class == ['app.Job']
        design(symbol: 'app.Job.label').items*.class == ['app.Job']
        design(symbol: 'app.Ledger.names').items*.class == ['app.Ledger']
        design(uri: uri('Order')).items*.class == ['app.Order']
        !design(symbol: 'Missing').found
        design(uri: 'file:///elsewhere/Job.groovy').items == []
    }

    def 'a request without a symbol or a uri is refused'() {
        when:
        server.design([root: root.toString()]).get(60, TimeUnit.SECONDS)

        then:
        ExecutionException e = thrown()
        (e.cause as ResponseErrorException).responseError.code == ResponseErrorCode.InvalidParams.value
    }
}
