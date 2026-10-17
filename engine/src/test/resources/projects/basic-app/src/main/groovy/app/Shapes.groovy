package app

interface Shape {
    double area()
}

trait Named {
    String name
}

class Circle implements Shape, Named {
    double r

    double area() {
        Math.PI * r * r
    }
}
