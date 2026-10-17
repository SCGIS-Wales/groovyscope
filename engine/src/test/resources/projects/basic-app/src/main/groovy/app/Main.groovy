package app

import app.model.Person

def greeter = new Greeter()
Greeter typed = new Greeter(greeting: 'Hi')
println greeter.greet('Ada')
println typed.greet()
Person p = new Person('Ada', 36)
if (p.adult) {
    println p.name
}
def circle = new Circle(r: 2)
println circle.area()
unknownThing.greet('x')
