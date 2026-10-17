package sample

import spock.lang.Specification

@Grab('org.example:only-in-tests:1.0')
class ThingSpec extends Specification {

    def 'with inside then is not checked'() {
        when:
        Map thing = [name: 'x']

        then:
        thing.with { name == 'x' }
        with(thing) { name == 'x' }
    }
}
