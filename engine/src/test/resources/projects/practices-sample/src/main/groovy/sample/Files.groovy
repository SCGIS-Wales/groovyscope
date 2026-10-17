package sample

class Files {
    void load(File config, java.nio.file.Path other, String name) {
        if (config) {
            println 'config'
        }
        if (!other) {
            println 'other'
        }
        if (name) {
            println 'name'
        }
    }
}
