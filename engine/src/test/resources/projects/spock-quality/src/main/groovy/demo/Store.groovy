package demo

class Store {
    String load(String key) {
        key.toUpperCase()
    }

    void save(String key) {
        if (!key) {
            throw new IllegalArgumentException('key')
        }
    }
}
