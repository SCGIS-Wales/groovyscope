package sample

class Truth {
    String nameOr(String given) {
        return given ?: 'anonymous'
    }

    List<String> tagsOr(List<String> given) {
        return given ?: ['default']
    }

    boolean isUnset(List<String> items) {
        boolean isEmpty = items?.isEmpty()
        return isEmpty
    }

    int sizeOf(List<String> items) {
        return items?.size()
    }

    int implicitSize(List<String> items) {
        items?.size()
    }

    boolean isImplicitlyEmpty(List<String> items) {
        items?.isEmpty()
    }
}
