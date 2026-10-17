package org.acme

class Deployer {
    String env

    Deployer(String env) {
        this.env = env
    }

    void deploy(String proxy) {
        println "deploy ${proxy} to ${env}"
    }
}
