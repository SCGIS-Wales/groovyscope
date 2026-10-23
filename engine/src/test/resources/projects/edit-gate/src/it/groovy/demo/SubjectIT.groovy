package demo

import spock.lang.Specification

class SubjectIT extends Specification {

    def 'squares end to end'() {
        expect:
        new Subject().square(7) == 49
    }
}
