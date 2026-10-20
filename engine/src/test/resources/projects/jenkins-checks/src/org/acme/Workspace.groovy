package org.acme

class Workspace implements Serializable {
    def steps

    Workspace(steps) {
        this.steps = steps
    }

    String manifest(String path) {
        return new File(path).text
    }

    String manifestOnAgent(String path) {
        return steps.readFile(path)
    }
}
