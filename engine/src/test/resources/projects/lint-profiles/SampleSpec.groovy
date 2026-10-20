package sample

import spock.lang.Specification

class SampleSpec extends Specification {

    def 'adds two numbers'() {
        expect:
        1 + 1 == 2
    }

    def 'reads the list'() {
        given:
        List<Integer> values = [1, 2]

        when:
        int first = values.first()

        then:
        first == 1
    }
}
