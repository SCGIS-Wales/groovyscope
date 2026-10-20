package org.acme

class Cache {
    static Map<String, String> entries = [:]
    static final List<String> SEEN = []
    static final String NAME = 'cache'
    static final List<String> FIXED = Collections.unmodifiableList(['a'])
    private static final int LIMIT = 3
}
