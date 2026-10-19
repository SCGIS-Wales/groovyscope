package sample

import spock.lang.Specification

class CacheSpec extends Specification {

    def 'a Spock spy cannot stub a private method'() {
        given:
        Cache cache = Spy()
        cache.evict() >> null
        cache.entries() >> ['a']

        expect:
        cache.size() == 1
    }

    def helper() {
        new Cache()
    }
}
