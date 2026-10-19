package sample

class Outer {
    Runnable task() {
        new Runnable() {
            void run() {
                Object o = 'x'
                o.bar()
            }
        }
    }
}
