package sample

import java.util.concurrent.ConcurrentHashMap

class Registry {
    static int created = 0
    static final Map<String, String> BY_NAME = new HashMap<>()
    static final Set<String> KNOWN = ['a'] as Set
    static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>()
    static final List<String> NAMES = ['a'].asImmutable()
    static final String LABEL = 'registry'
    private static final long serialVersionUID = 1L
    String name
}

enum Colour {
    RED, GREEN
}
