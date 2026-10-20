package demo

class StringExtensions {
    static String shout(String self) {
        self.toUpperCase() + '!'
    }
}

@Category(Integer)
class Doubling {
    Integer twice() {
        this * 2
    }
}

class Greeter {
    String greet(String name) {
        name.shout()
    }

    Integer doubled(Integer n) {
        use(Doubling) {
            n.twice()
        }
    }

    String missing(String name) {
        name.whisper()
    }
}
