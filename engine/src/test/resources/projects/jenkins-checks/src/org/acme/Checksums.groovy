package org.acme

import groovy.transform.CompileStatic

@CompileStatic
class Checksums {
    static String reversed(String text) {
        return text.reverse()
    }
}
