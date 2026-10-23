package demo

import spock.lang.Specification

class SubjectSpec extends Specification {

    def test1() {
        expect:
        new Subject().square(2) == 4
    }

    def 'squares three'() {
        expect:
        new Subject().square(3) == 9
    }

    def 'squares four'() {
        expect:
        new Subject().square(4) == 16
    }

    def 'squares two again'() {
        expect:
        new Subject().square(2) == 4
    }

    def 'creates a subject'() {
        when:
        new Subject()

        then:
        noExceptionThrown()
    }

    def shouldWork() {
        expect:
        new Subject().square(5) == 25
    }
}
