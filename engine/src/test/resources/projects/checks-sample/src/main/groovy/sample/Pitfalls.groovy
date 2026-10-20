package sample

class Pitfalls {
    boolean hasNegative(List<Integer> values) {
        boolean found = false
        values.each { Integer value ->
            if (value < 0) {
                found = true
                return // GS037
            }
        }
        found
    }

    List<Integer> doubled(List<Integer> values) {
        values.collect { Integer value -> return value * 2 }
    }

    boolean knows(Set<String> names, String first) {
        names.contains("${first}") // GS018
    }

    String lookup(Map<String, String> labels, String key) {
        labels.get("${key}") // GS018
    }

    boolean same(String left, String right) {
        left.equals("${right}") // GS018
    }

    boolean sameText(String left, String right) {
        left == "${right}"
    }

    boolean listed(Set<String> names, String first) {
        "${first}" in names // GS018
    }

    boolean mentions(String text, String word) {
        text.contains("${word}")
    }

    String describe(Integer count) {
        if (count) { // GS028
            return 'some'
        }
        'none'
    }

    String describeSize(int size) {
        size ? 'some' : 'none' // GS028
    }

    String describeExplicitly(Integer count) {
        count != null && count != 0 ? 'some' : 'none'
    }

    void configure(Closure body) { // GS017
        body.call()
    }

    void configureTyped(@DelegatesTo(StringBuilder) Closure body) {
        new StringBuilder().with(body)
    }

    private void helper(Closure body) {
        body.call()
    }

    List<String> names(List<Map<String, String>> rows) {
        rows.collect { // GS029
            String name = it.name
            name.trim()
        }
    }

    List<String> trimmed(List<String> rows) {
        rows.collect { it.trim() }
    }
}

record Tagged(String name, List<String> tags) { } // GS034

record Copied(String name, List<String> tags) {
    Copied {
        tags = List.copyOf(tags)
    }
}

record Plain(String name, int size) { }
