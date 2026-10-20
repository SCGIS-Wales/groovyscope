package demo

import spock.lang.Specification
import spock.lang.Unroll

class CleanSpec extends Specification {

    def 'interactions carry a cardinality'() {
        given:
        Store store = Mock()
        store.load('b') >> 'B'

        when:
        store.load('a')

        then:
        1 * store.load('a') >> 'A'
        (1.._) * store.load(_)
        0 * _
    }

    @Unroll('sum of #a and #b is #c')
    def 'named unrolling'() {
        expect:
        a + b == c

        where:
        a | b || c
        1 | 2 || 3
    }

    def 'columns used through a derived variable and a pipe'() {
        expect:
        total == expected

        where:
        x | y
        1 | 2
        total = x + y
        expected << [3]
    }

    def 'the exception is examined after thrown'() {
        when:
        new Store().save('')

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'key'
    }

    def 'no exception expected'() {
        when:
        new Store().save('k')

        then:
        noExceptionThrown()
    }

    def 'a single column table'() {
        expect:
        value > 0

        where:
        value | _
        1     | _
    }
}
