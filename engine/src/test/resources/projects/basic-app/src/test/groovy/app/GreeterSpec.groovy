package app

import spock.lang.Specification

class GreeterSpec extends Specification {

    def 'greets by name'() {
        given:
        Greeter greeter = new Greeter()

        expect:
        greeter.greet('Ada') == 'Hello, Ada!'
    }
}
