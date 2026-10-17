package sample

import java.util.List;
import java.util.ArrayList

class Messy {
    def untyped = 1;
    String name = "plain";   

    def process(List items) {
        def result = new ArrayList()
        for (item in items) {
            if (item == null) {
                return null
            }
            result.add(item);
        }


        return result
    }

    String greet() {
        return "hello " + name;
    }
}
