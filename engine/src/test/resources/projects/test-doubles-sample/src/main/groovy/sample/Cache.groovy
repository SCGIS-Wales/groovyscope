package sample

class Cache {

    int size() {
        entries().size()
    }

    protected List<String> entries() {
        []
    }

    private void evict() {
    }
}
