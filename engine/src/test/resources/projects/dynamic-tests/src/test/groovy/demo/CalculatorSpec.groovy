package demo

import spock.lang.Specification

class CalculatorSpec extends Specification {

    def calculator

    def setup() {
        calculator = new Calculator()
    }

    def 'adds two numbers'() {
        expect:
        calculator.add(1, 2) == 3
    }

    def 'sums a list'() {
        expect:
        calculator.sum([1, 2]) == 3
    }
}
