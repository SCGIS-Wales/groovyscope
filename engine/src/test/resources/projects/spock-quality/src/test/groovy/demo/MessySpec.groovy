package demo

import spock.lang.Specification
import spock.lang.Unroll

class MessySpec extends Specification {

    def 'stubs and mocks in then'() {
        given:
        Store store = Mock()

        when:
        store.load('a')

        then:
        store.load('a') >> 'A'
        Store late = Mock()
    }

    @Unroll
    def 'adds #a and #b'() {
        expect:
        a + b == c

        where:
        a | b | c | d
        1 | 2 | 3 | 9
    }

    def 'twice the same column'() {
        expect:
        a == b

        where:
        a | b | a
        1 | 1 | 1
    }

    def 'exception condition in expect'() {
        expect:
        new Store().save('')
        thrown(IllegalArgumentException)
    }

    def 'two exception conditions'() {
        when:
        new Store().save('')

        then:
        thrown(IllegalArgumentException)
        notThrown(IllegalStateException)
    }
}
