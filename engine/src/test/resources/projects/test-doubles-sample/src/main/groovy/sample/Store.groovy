package sample

import groovy.transform.CompileStatic

@CompileStatic
class Store {

    String load(String key) {
        fetch(key) + format(key)
    }

    private String fetch(String key) {
        key
    }

    String format(String key) {
        key.toUpperCase()
    }
}
