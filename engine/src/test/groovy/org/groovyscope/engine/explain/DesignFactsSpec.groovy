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

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CompileUnit
import org.codehaus.groovy.ast.ModuleNode
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import spock.lang.Shared
import spock.lang.Specification

/** The design facts of a class: cohesion, locals in disguise, shared parameters, state and suggestions. */
class DesignFactsSpec extends Specification {

    @Shared
    GroovyCompiler compiler = new GroovyCompiler()

    /** The design of class {@code name}, its file compiled with the others ({@code Name: text}). */
    private DesignFacts.ClassDesign design(Map<String, String> files, String name = null,
            EngineConfig.Design limits = EngineConfig.Design.DEFAULTS) {
        Map<String, String> sources = files.collectEntries { file, text ->
            [("file:///w/src/main/groovy/${file}.groovy" as String): text]
        }
        def modules = compiler.compile(sources, [], false).modules()
        ModuleNode module = modules[sources.keySet().first()]
        ClassNode classNode = module.classes.find { it.nameWithoutPackage == (name ?: files.keySet().first()) }
        DesignFacts.of(classNode, DesignIndex.of(module), limits)
    }

    private DesignFacts.ClassDesign design(String text, String name = null) {
        design([Job: text], name)
    }

    def 'a class with two unrelated groups of methods has LCOM4 2, and both groups are named with their fields'() {
        when:
        def facts = design('''\
class Job {
    int count
    String label
    void increment() { count++ }
    int doubled() { count * 2 }
    boolean isBig() { doubled() > 10 }
    void rename(String text) { label = text }
    String shout() { label.toUpperCase() }
}
''')

        then:
        facts.cohesion().lcom4() == 2
        facts.cohesion().groups()*.methods() == [['increment', 'doubled', 'isBig'], ['rename', 'shout']]
        facts.cohesion().groups()*.fields() == [['count'], ['label']]
        facts.size() == new DesignFacts.Size(5, 2, 9)
    }

    def 'a method calling both groups joins them for LCOM4, while byFields still shows the two jobs'() {
        when:
        def facts = design('''\
class Job {
    int count
    String label
    void increment() { count++ }
    void rename(String text) { label = text }
    void run() {
        increment()
        rename('x')
    }
    int twice(int a) { a * 2 }
    static int half(int a) { a / 2 }
}
''')

        then:
        facts.cohesion().lcom4() == 2
        facts.cohesion().groups()*.methods() == [['increment', 'rename', 'run'], ['twice']]
        facts.cohesion().byFields()*.methods() == [['increment'], ['rename']]
        facts.statelessMethods()*.method() == ['twice']
    }

    def 'a field group that only others call into is separable, and the split suggestion moves it out'() {
        when:
        def facts = design('''\
class Runner {
    List<String> items = []
    String name
    void add(String item) { items << item }
    int count() { items.size() }
    void run() {
        println name
        add(name)
    }
    String title() { name.toUpperCase() }
}
''', 'Runner')

        then:
        facts.cohesion().lcom4() == 1
        facts.cohesion().byFields()*.methods() == [['add', 'count'], ['run', 'title']]
        facts.cohesion().separable()*.methods() == [['add', 'count']]
        DesignSuggestions.of(facts, 'Runner.groovy').collect { [it.kind(), it.tool()] } == [
                ['split', 'move_member(symbol: "Runner.add", toClass: "<NewClass>")']]
    }

    def 'a private field one method writes and reads is a local in disguise; shared, set up or initialised ones are not'() {
        when:
        def facts = design('''\
class Job {
    private int scratch
    private int shared
    private int injected
    private int ready = 0
    private int inner
    int count

    Job(int injected) { this.injected = injected }

    int sum(List<Integer> values) {
        scratch = 0
        values.each { scratch += it }
        shared = scratch
        scratch + injected + ready
    }
    int last() { shared }
    Runnable task() {
        new Runnable() {
            void run() { inner++ }
        }
    }
    void setInner() { inner = 1 }
}
''')

        then:
        facts.fieldsUsedByOneMethod()*.field() == ['scratch']
        facts.fieldsUsedByOneMethod()[0].method() == 'sum'
        facts.fieldsUsedByOneMethod()[0].line() == 11
    }

    def 'three methods sharing three parameters make one parameter list, its subsets left out'() {
        when:
        def facts = design('''\
class Client {
    void get(String host, int port, String path) { println host }
    void put(String host, int port, String path, String body) { println host }
    void delete(String host, int port, String path) { println host }
    void ping(String host, int port) { println host }
}
''', 'Client')

        then:
        facts.sharedParameterLists().size() == 1
        facts.sharedParameterLists()[0].parameters() == ['String host', 'int port', 'String path']
        facts.sharedParameterLists()[0].methods() == ['get', 'put', 'delete']
    }

    def 'static mutable state: fields that are not final and collections anyone may change; loggers and constants left out'() {
        when:
        def facts = design('''\
class Job {
    static int counter
    static final List<String> NAMES = []
    static final List<String> FIXED = ['a'].asImmutable()
    static final int LIMIT = 3
    static java.util.logging.Logger LOG = java.util.logging.Logger.getLogger('x')
}
''')

        then:
        facts.staticMutableState().collect { [it.name(), it.kind(), it.type()] } == [['counter', 'field', 'int'],
                ['NAMES', 'collection', 'List<String>']]
    }

    def 'overloads are labelled with their parameter types'() {
        when:
        def facts = design('''\
class Job {
    void send(String a, String b, String c, String d, String e) { println a }
    void send(int a) { println a }
}
''')

        then:
        facts.longParameterLists()*.method() == ['send(String, String, String, String, String)']
        facts.statelessMethods()*.method() == ['send(String, String, String, String, String)', 'send(int)']
    }

    def 'the data owner of an anemic class names the method of the owner that uses it most'() {
        when:
        def facts = design([Account: 'class Account {\n    BigDecimal balance\n    String owner\n}\n',
                            Bank   : '''\
class Bank {
    void withdraw(Account account, BigDecimal amount) {
        account.balance = account.balance - amount
        account.setOwner(account.getOwner().trim())
    }
    String show(Account account) { account.owner }
}
'''])

        then:
        facts.dataOwner() == new DesignFacts.DataOwner('Bank', 'withdraw', 3, 2)
    }

    def 'suggestions name the edit tool call for each finding'() {
        given:
        def facts = design([Shop: '''\
package app

class Shop {
    public int visits
    static int opened
    private final List<String> items = []
    int count
    String label
    private int scratch

    void increment() { count++ }
    int doubled() { count * 2 }
    void rename(String text) { label = text }
    String shout() { label.toUpperCase() }
    List<String> getItems() { items }
    int sum(List<Integer> values) {
        scratch = 0
        values.each { scratch += it }
        scratch
    }
    BigDecimal total(Order order) { order.price * order.quantity - order.discount }
    String code(Order order) { order.describe() + order.describe() }
    void move(String fromId, String toId, String currencyCode) { println fromId }
    void book(String host, int port, String path, int retries, int timeout) { println host }
    void get(String host, int port, String path) { println host }
    void put(String host, int port, String path) { println host }
    String kind(Object value) {
        switch (value) {
            case String: return 'text'
            case Integer: return 'number'
            case List: return 'list'
        }
        'other'
    }
    String size(Shape shape) {
        if (shape instanceof Circle) {
            return 'round'
        } else if (shape instanceof Square) {
            return 'square'
        } else if (shape instanceof Triangle) {
            return 'pointed'
        }
        'none'
    }
}
''', Order: '''\
package app

class Order {
    BigDecimal price
    int quantity
    BigDecimal discount
    String describe() { 'order' }
}
''', Shapes: '''\
package app

interface Shape { }
class Circle implements Shape { }
class Square implements Shape { }
class Triangle implements Shape { }
'''], 'Shop')

        when:
        Map<String, List<String>> tools = DesignSuggestions.of(facts, 'src/main/groovy/app/Shop.groovy')
                .groupBy { it.kind() }.collectEntries { kind, list -> [kind, list*.tool()] }

        then:
        tools.split == ['move_member(symbol: "app.Shop.rename", toClass: "app.<NewClass>")']
        tools.move.contains('move_member(symbol: "app.Shop.total", toClass: "app.Order")')
        tools.move.contains('move_member(symbol: "app.Shop.code", toClass: "app.Order")')
        tools.local == (['edit_symbol(path: "src/main/groovy/app/Shop.groovy", symbol: "Shop.sum", operation: "replace_body",'
                + ' text: "<the body, declaring scratch as a local>"), then edit_symbol(path:'
                + ' "src/main/groovy/app/Shop.groovy", symbol: "Shop.scratch", operation: "delete")'])
        tools.parameterObject == [
                'change_signature(symbol: "app.Shop.book", parameters: [{name: "<object>", type: "<NewType>"},'
                        + ' {name: "retries", type: "int"}, {name: "timeout", type: "int"}])',
                'change_signature(symbol: "app.Shop.book", parameters: [{name: "host", type: "String"}, {name: "port",'
                        + ' type: "int"}, {name: "<object>", type: "<NewType>"}])']
        tools.valueType == (['change_signature(symbol: "app.Shop.move", parameters: [{name: "fromId", type: "FromId"},'
                + ' {name: "toId", type: "ToId"}, {name: "currencyCode", type: "CurrencyCode"}])'])
        tools.polymorphism == [
                'extract_method(path: "src/main/groovy/app/Shop.groovy", startLine: 29, endLine: 29, name: "kindForString")',
                'extract_method(path: "src/main/groovy/app/Shop.groovy", startLine: 37, endLine: 37, name: "sizeForCircle")']
        tools.encapsulate == [
                'edit_symbol(path: "src/main/groovy/app/Shop.groovy", symbol: "Shop.visits", operation: "replace", text:'
                        + ' "int visits")',
                'edit_symbol(path: "src/main/groovy/app/Shop.groovy", symbol: "Shop.getItems", operation: "replace_body",'
                        + ' text: "return items.asImmutable()")']
        tools.immutable == (['edit_symbol(path: "src/main/groovy/app/Shop.groovy", symbol: "Shop.opened", operation:'
                + ' "replace", text: "<static final, unmodifiable declaration of opened>")'])
        tools.static.contains('edit_symbol(path: "src/main/groovy/app/Shop.groovy", symbol: "Shop.kind", operation:'
                + ' "replace", text: "<the method, declared static>")')
        DesignSuggestions.of(facts, 'x').find { it.kind() == 'polymorphism' && it.tool().contains('kindFor') }.message()
                .endsWith('call it from a visitor or a map from type to handler')
    }

    def 'the data owner suggestion moves the owner method into the data class'() {
        when:
        def facts = design([Account: 'class Account {\n    BigDecimal balance\n    String owner\n}\n',
                            Bank   : 'class Bank {\n    void w(Account a) { a.balance = a.balance - 1 }\n}\n'])

        then:
        DesignSuggestions.of(facts, 'Account.groovy')*.tool() == ['move_member(symbol: "Bank.w", toClass: "Account")']
    }

    def 'an index of a module outside any compilation knows only that module'() {
        given:
        def index = DesignIndex.of(new ModuleNode((CompileUnit) null))

        expect:
        index.fieldUsers('Anything').isEmpty()
        !index.redeclaredBelow(ClassHelper.OBJECT_TYPE, 'run', 0)
    }
}
