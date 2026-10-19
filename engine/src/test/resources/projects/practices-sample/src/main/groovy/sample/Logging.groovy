package sample

class Logging {
    static final org.slf4j.Logger log = null

    void run(int count, String who) {
        log.info("Loaded ${count} rows for ${who}")
        log.debug('plain message')
        println "not a log call ${count}"
    }
}
