package demo

class Builder {
    void step(String name) {
    }

    void shared() {
    }
}

class Pipeline {
    void configure(@DelegatesTo(Builder) Closure body) {
        body.delegate = new Builder()
        body()
    }

    void shared() {
    }

    void helper(int x) {
    }

    void run() {
        configure {
            step 'compile'
            shared()
        }
        [1, 2].each { helper(it) }
        helper(3)
    }
}
