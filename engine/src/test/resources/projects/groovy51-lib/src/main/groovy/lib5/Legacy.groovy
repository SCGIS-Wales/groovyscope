package lib5

import groovy.transform.CompileStatic

@CompileStatic
class Legacy {
    private final List<String> items = []

    Legacy add(String item) {
        items << item
        this
    }

    int size() {
        items.size()
    }
}
