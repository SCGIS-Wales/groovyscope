package dsl

import groovy.transform.CompileDynamic

@CompileDynamic
class Builder {
    def methodMissing(String name, args) {
        "missing ${name}"
    }

    def propertyMissing(String name) {
        "prop ${name}"
    }

    void configure(Closure body) {
        body.delegate = this
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
    }
}
