package shapes

import org.junit.jupiter.params.ParameterizedTest

class CircleTest {
    void circleArea() {
        assert new Circle(radius: 1).area() > 3
    }
}
