package app.model

class Person {
    String name
    int age

    Person(String name, int age) {
        this.name = name
        this.age = age
    }

    boolean isAdult() {
        age >= 18
    }
}
