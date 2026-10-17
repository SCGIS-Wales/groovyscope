package sample

import groovy.transform.CompileStatic

@CompileStatic
class Tidy {
    private final String label

    Tidy(String label) {
        this.label = label
    }

    String describe() {
        "tidy ${label}"
    }
}
