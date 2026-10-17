package sample

class Handlers {

    String load(String path) {
        try {
            return new File(path).text
        } catch (IOException e) {
        }
        return null
    }

    void store(String path, String text) {
        try {
            new File(path).text = text
        } catch (Exception e) {
            throw new IllegalStateException('cannot store ' + path, e)
        }
    }

    def guess(def input) {
        def result = input
        if (result == null) {
        }
        return result
    }

    void noop() {
    }

    String lookup(String key) {
        if (key.startsWith('secret')) {
            return null
        }
        return key.toUpperCase()
    }

    @SuppressWarnings('unused')
    private int helper(@SuppressWarnings('x') int a, int b, int c, int d) {
        return a + b + c + d
    }
}
