package app

/**
 * Greets people politely.
 */
class Greeter {
    String greeting = 'Hello'
    private int count = 0
    static final String DEFAULT_NAME = 'world'

    /** Builds one greeting line. */
    String greet(String name) {
        count++
        return "${greeting}, ${name}!"
    }

    String greet() {
        greet(DEFAULT_NAME)
    }

    int getCount() {
        count
    }
}
