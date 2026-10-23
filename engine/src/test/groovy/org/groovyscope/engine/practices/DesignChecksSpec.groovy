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

import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import spock.lang.Shared
import spock.lang.Specification

/** GS090 to GS097: design findings on main sources, each once per class or member. */
class DesignChecksSpec extends Specification {

    static final String BASE = 'file:///w/src/main/groovy/app/'

    @Shared
    GroovyCompiler compiler = new GroovyCompiler()

    /** The GS09x findings of the first file, compiled together with the others ({@code Name: text}). */
    private List<PracticeFinder.Practice> design(Map<String, String> files, EngineConfig config = EngineConfig.DEFAULTS,
            String base = BASE) {
        Map<String, String> sources = files.collectEntries { name, text -> [(base + name + '.groovy'): text] }
        String uri = sources.keySet().first()
        def module = compiler.compile(sources, [], false).modules()[uri]
        PracticeFinder.find(uri, module, sources[uri], config).findAll { it.diagnostic().code().startsWith('GS09') }
    }

    private List<PracticeFinder.Practice> design(String text, EngineConfig config = EngineConfig.DEFAULTS) {
        design([Job: text], config)
    }

    private static EngineConfig with(EngineConfig.Design limits, Map<String, String> severities = [:]) {
        EngineConfig.DEFAULTS.toBuilder().practices(new EngineConfig.Practices(true, severities,
                EngineConfig.Naming.DEFAULTS, [], EngineConfig.Comments.DEFAULTS, EngineConfig.Reuse.DEFAULTS,
                EngineConfig.Tests.DEFAULTS, EngineConfig.Types.DEFAULTS, limits)).build()
    }

    private static List<String> codes(List<PracticeFinder.Practice> found) {
        found*.diagnostic()*.code()
    }

    private static PracticeFinder.Practice only(List<PracticeFinder.Practice> found, String code) {
        def matching = found.findAll { it.diagnostic().code() == code }
        assert matching.size() == 1 : "expected one ${code}, found ${found*.diagnostic()*.message()}"
        matching[0]
    }

    /** The text a finding's range covers. */
    private static String at(String text, PracticeFinder.Practice practice) {
        def range = practice.diagnostic().range()
        def lines = text.readLines()
        assert range.start().line() == range.end().line()
        lines[range.start().line()].substring(range.start().column(), range.end().column())
    }

    def 'GS090: a class over a size limit is reported once, at its name, naming each limit it passes'() {
        given:
        String methods = (1..4).collect { "    int m${it}() { count + ${it} }" }.join('\n')
        String fields = (1..3).collect { "    int f${it}" }.join('\n')
        String text = "class Job {\n    int count\n${fields}\n${methods}\n}\n"

        when:
        def found = design(text, with(new EngineConfig.Design(3, 3, 600, 4, 3)))
        def god = only(found, 'GS090')

        then:
        at(text, god) == 'Job'
        god.diagnostic().severity() == Diagnostic.Severity.INFORMATION
        god.diagnostic().message() == ('God class: Job has 4 methods (more than 3), 4 fields (more than 3): split it by'
                + ' responsibility (class_design lists its groups of methods)')

        when: 'within the limits, and lines counted too'
        def small = design(text, with(new EngineConfig.Design(4, 4, 600, 4, 3)))
        def tall = design(text, with(new EngineConfig.Design(25, 12, 5, 4, 3)))

        then:
        !codes(small).contains('GS090')
        only(tall, 'GS090').diagnostic().message().contains('Job has 10 lines (more than 5)')
    }

    def 'GS090: static final constants are not state and the defaults leave an ordinary class alone'() {
        given:
        String constants = (1..20).collect { "    static final int C${it} = ${it}" }.join('\n')

        expect:
        design("class Job {\n${constants}\n    int run() { C1 + C2 }\n}\n").isEmpty()
    }

    def 'GS091: a method reading another class more than its own is reported at its name, naming the class'() {
        given:
        String text = '''\
class Billing {
    BigDecimal rate
    BigDecimal total(Order order) {
        order.price * order.quantity - order.getDiscount()
    }
    BigDecimal fee(Order order) {
        rate * order.price
    }
}
'''
        String order = 'class Order {\n    BigDecimal price\n    int quantity\n    BigDecimal discount\n}\n'

        when:
        def found = design([Billing: text, Order: order])
        def envy = only(found, 'GS091')

        then:
        at(text, envy) == 'total'
        envy.diagnostic().message() == ("Feature envy: total uses Order's fields and getters 3 times and its own class's"
                + ' 0: move it to Order (move_member)')

        when: 'a higher threshold'
        def strict = design([Billing: text, Order: order], with(new EngineConfig.Design(25, 12, 600, 4, 4)))

        then:
        !codes(strict).contains('GS091')
    }

    def 'GS091: writes, overrides, related classes and data many classes read are not envy'() {
        given:
        String order = 'class Order {\n    BigDecimal price\n    int quantity\n    BigDecimal discount\n}\n'
        String builder = '''\
class Builder {
    Order build() {
        Order order = new Order()
        order.price = 1
        order.quantity = 2
        order.discount = 0
        order
    }
}
'''
        String overriding = '''\
class Pricing implements Comparable<Order> {
    int compareTo(Order order) {
        order.price + order.quantity + order.discount
    }
}
'''
        String sub = '''\
class Special extends Order {
    BigDecimal sum(Order other) { other.price + other.quantity + other.discount }
}
'''
        String reader = 'class R%d {\n    def read(Order o) { [o.price, o.quantity, o.discount] }\n}\n'

        expect:
        !codes(design([Builder: builder, Order: order])).contains('GS091')
        design([Pricing: overriding, Order: order]).isEmpty()
        !codes(design([Special: sub, Order: order])).contains('GS091')
        !codes(design([R1: String.format(reader, 1), Order: order, R2: String.format(reader, 2),
                       R3: String.format(reader, 3)])).contains('GS091')
        codes(design([R1: String.format(reader, 1), Order: order, R2: String.format(reader, 2)])) == ['GS091', 'GS097']
    }

    def 'GS092: more parameters than design.maxParameters, at the method or constructor; overrides left alone'() {
        given:
        String text = '''\
class Job {
    Job(int a, int b, int c, int d, int e) { }
    void book(String name, String email, Date from, Date to, int guests) { println name }
    void fine(int a, int b, int c, int d) { println a }
}
'''
        String overriding = '''\
abstract class Base { abstract void hook(int a, int b, int c, int d, int e) }
class Job extends Base {
    void hook(int a, int b, int c, int d, int e) { println a }
}
'''

        when:
        def found = design(text).findAll { it.diagnostic().code() == 'GS092' }

        then:
        found*.diagnostic()*.message() == [
                'Long parameter list: the constructor of Job takes 5 parameters (more than 4): pass the ones that'
                        + ' travel together as one object (change_signature)',
                'Long parameter list: book takes 5 parameters (more than 4): pass the ones that travel together as'
                        + ' one object (change_signature)']
        at(text, found[1]) == 'book'
        design(overriding).findAll { it.diagnostic().code() == 'GS092' }*.diagnostic()*.range()*.start()*.line() == [0]
        !codes(design(text, with(new EngineConfig.Design(25, 12, 600, 5, 3)))).contains('GS092')
    }

    def 'GS093: three instanceof branches on one value, as else if, early returns or class cases, once per method'() {
        given:
        String text = '''\
class Shapes {
    double area(Shape shape) {
        if (shape instanceof Circle) {
            return 3
        } else if (shape instanceof Square) {
            return 4
        } else if (shape instanceof Triangle && shape != null) {
            return 5
        }
        if (shape instanceof Circle) { return 1 }
        return 0
    }
    String name(Shape shape) {
        if (shape instanceof Circle) {
            return 'circle'
        }
        if (shape instanceof Square) {
            return 'square'
        }
        if (shape instanceof Triangle) {
            return 'triangle'
        }
        'shape'
    }
    String kind(Object value) {
        switch (value) {
            case String: return 'text'
            case Integer: return 'number'
            case List: return 'list'
            default: return 'other'
        }
    }
}
interface Shape { }
class Circle implements Shape { }
class Square implements Shape { }
class Triangle implements Shape { }
'''

        when:
        def found = design(text).findAll { it.diagnostic().code() == 'GS093' }

        then:
        found.collect { at(text, it) } == ['area', 'name', 'kind']
        found[0].diagnostic().message() == ('Chain of type tests: area branches on the type of shape 3 times (Circle,'
                + ' Square, Triangle): give the types one method of their own and call it')
        found[2].diagnostic().message() == ('Chain of type tests: kind branches on the type of value 3 times (String,'
                + ' Integer, List): a visitor, or a map from type to handler, keeps each case with the others of its type')
    }

    def 'GS093: two tests, tests on different values, and cases falling through to one branch are not a chain'() {
        expect:
        design('''\
class Job {
    boolean pair(Object a) {
        if (a instanceof String) { return true } else if (a instanceof Integer) { return false }
        false
    }
    boolean mixed(Object a, Object b, Object c) {
        if (a instanceof String) { return true } else if (b instanceof String) { return true }
        else if (c instanceof String) { return true }
        false
    }
    boolean member(Object value) {
        switch (value) {
            case String:
            case Integer:
            case List:
                return true
            default:
                return false
        }
    }
}
''').findAll { it.diagnostic().code() == 'GS093' }.isEmpty()
    }

    def 'GS094: a public mutable field and a getter handing out a new mutable collection field, as warnings'() {
        given:
        String text = '''\
class Job {
    public String label
    public final String fixed = 'x'
    public static final int LIMIT = 3
    String property
    private final List<String> names = []
    private final Map<String, Integer> counts
    private List<String> safe = []
    private List<String> given

    Job(List<String> given) {
        this.counts = new HashMap<>()
        this.given = given
        this.safe = safe.asImmutable()
    }

    List<String> getNames() { names }
    Map<String, Integer> getCounts() { return counts }
    List<String> getSafe() { safe }
    List<String> getGiven() { given }
    List<String> copy() { new ArrayList<>(names) }
}
'''

        when:
        def found = design(text).findAll { it.diagnostic().code() == 'GS094' }

        then:
        found.collect { at(text, it) } == ['label', 'getNames', 'getCounts']
        found.every { it.diagnostic().severity() == Diagnostic.Severity.WARNING }
        found[0].diagnostic().message() == ('State anyone may change: public field label can be changed by any caller:'
                + ' make it final, or a property (drop public)')
        found[1].diagnostic().message() == ('State anyone may change: getNames returns the mutable List<String> field'
                + ' names itself: return a copy or an unmodifiable view (names.asImmutable())')
    }

    def 'GS094: immutable classes and records are left alone'() {
        expect:
        design('''\
@groovy.transform.Immutable
class Point {
    int x
    int y
}
''').isEmpty()
        design('record Pair(String left, String right) { }\n').isEmpty()
    }

    def 'GS095: a data class whose fields one other class reads and writes, at its name, naming that class'() {
        given:
        String account = '''\
class Account {
    BigDecimal balance
    String owner
    String toString() { owner }
}
'''
        String service = '''\
class AccountService {
    void withdraw(Account account, BigDecimal amount) {
        account.balance = account.balance - amount
    }
}
'''

        when:
        def found = design([Account: account, AccountService: service])
        def anemic = only(found, 'GS095')

        then:
        at(account, anemic) == 'Account'
        anemic.diagnostic().message() == ('Anemic data class: Account only holds data: AccountService reads and writes'
                + ' its fields and holds the logic over them; move that logic into Account (move_member)')
    }

    def 'GS095: values only built and read, data two classes write, classes with logic and nested holders are not reported'() {
        given:
        String account = 'class Account {\n    BigDecimal balance\n    String owner\n}\n'
        String builder = 'class Opening {\n    Account open() { new Account(balance: 0, owner: "me") }\n}\n'
        String reader = 'class Report {\n    String line(Account a) { a.owner + a.balance }\n}\n'
        String writer = 'class W%d {\n    void w(Account a) { a.balance = a.balance + 1 }\n}\n'
        String logic = '''\
class Wallet {
    BigDecimal balance
    String owner
    boolean isEmpty() { balance == 0 }
    void add(BigDecimal amount) { balance += amount }
}
'''
        String walletService = 'class WalletService {\n    void w(Wallet a) { a.balance = a.balance + 1 }\n}\n'
        String nested = '''\
class Parser {
    Result parse(String text) {
        Result result = new Result()
        result.source = text
        result.count = result.source.size()
        result
    }
    static class Result {
        String source
        int count
    }
}
'''

        expect:
        design([Account: account, Opening: builder, Report: reader]).isEmpty()
        design([Account: account, W1: String.format(writer, 1), W2: String.format(writer, 2)]).isEmpty()
        !codes(design([Wallet: logic, WalletService: walletService])).contains('GS095')
        !codes(design([Parser: nested])).contains('GS095')
    }

    def 'GS096: three String parameters named as identifiers, paths or codes, at the method'() {
        given:
        String text = '''\
class Transfers {
    void move(String fromId, String toId, String currencyCode, BigDecimal amount) { println amount }
    void label(String title, String body, String footer) { println title }
    void two(String id, String path, int code) { println id }
}
'''

        when:
        def found = design(text).findAll { it.diagnostic().code() == 'GS096' }

        then:
        found.size() == 1
        at(text, found[0]) == 'move'
        found[0].diagnostic().message() == ('Primitive obsession: move takes 3 String parameters named as identifiers'
                + ' (fromId, toId, currencyCode): give them types of their own, so they cannot be swapped')
    }

    def 'GS097: a method using no instance state, naming the class most of its calls go to'() {
        given:
        String text = '''\
class Invoices {
    BigDecimal rate = 0.2
    BigDecimal vat(BigDecimal net) {
        net * 0.2
    }
    String describe(Order order) {
        order.summary() + order.code()
    }
    BigDecimal taxed(BigDecimal net) {
        net * rate
    }
    BigDecimal twice(BigDecimal net) {
        vat(net) * 2
    }
    static BigDecimal half(BigDecimal net) {
        net / 2
    }
    BigDecimal quarter(BigDecimal net) {
        half(net) / 2
    }
    void log(String text) {
        println text
    }
}
'''
        String order = 'class Order {\n    String summary() { "s" }\n    String code() { "c" }\n}\n'

        when:
        def found = design([Invoices: text, Order: order]).findAll { it.diagnostic().code() == 'GS097' }

        then:
        found.collect { at(text, it) } == ['vat', 'describe', 'quarter', 'log']
        found[0].diagnostic().message() == ('Method uses no instance state: vat reads no field, calls no instance method'
                + ' and does not use this: make it static')
        found[1].diagnostic().message().endsWith('make it static, or move it to Order, whose objects it works on'
                + ' (move_member)')
    }

    def 'GS097: overrides, redeclared, accessors, annotated, empty, abstract and dynamic methods are left alone'() {
        given:
        String text = '''\
abstract class Base {
    abstract int size()
    int hook(int a) { a }
    String toString() { 'base' }
    int getAnswer() { 42 }
    void setAnswer(int value) { }
    @Deprecated
    int old(int a) { a }
    void nothing() { }
    void fail() { throw new UnsupportedOperationException() }
    def call(int a) { a }
    def dynamic() { unknownThing() }
    def viaThis() { this.hashCode() }
    def viaSuper() { super.hashCode() }
    def closure() { [1].collect { it + size() } }
    def property() { delegateValue }
}
class Child extends Base {
    int size() { 1 }
    int hook(int a) { a + 1 }
}
'''

        expect:
        design([Base: text]).findAll { it.diagnostic().code() == 'GS097' }.isEmpty()
    }

    def 'tests, specifications, scripts, interfaces, anonymous classes and switched off codes report nothing'() {
        given:
        String stateless = 'class %s {\n    int twice(int a) { a * 2 }\n}\n'

        expect:
        design([Job: String.format(stateless, 'Job')], EngineConfig.DEFAULTS, 'file:///w/src/test/groovy/app/').isEmpty()
        design([JobSpec: String.format(stateless, 'JobSpec')]).isEmpty()
        design([Job: 'int twice(int a) { a * 2 }\nprintln twice(2)\n']).isEmpty()
        design([Job: 'interface Job {\n    int twice(int a)\n}\n']).isEmpty()
        design([Job: 'class Job {\n    Runnable r = new Runnable() { void run() { } \n int twice(int a) { a * 2 } }\n}\n'])
                .isEmpty()
        design([Job: String.format(stateless, 'Job')], with(EngineConfig.Design.DEFAULTS, [GS097: 'off'])).isEmpty()
        codes(design([Job: String.format(stateless, 'Job')])) == ['GS097']
    }
}
