package shapes

import groovy.transform.CompileStatic

@CompileStatic
class Circle implements Shape {
    double radius

    double area() {
        Math.PI * radius * radius
    }
}
