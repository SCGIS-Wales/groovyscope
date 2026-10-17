package sample

import groovy.transform.CompileStatic

@CompileStatic
class Typed {
    int length(String text) {
        text.lenght()
    }

    String maybe(String text) {
        text == null ? null : text.trim()
    }
}
