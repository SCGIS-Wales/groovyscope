package org.slf4j

/** Stand-in for SLF4J, which is not on the fixture classpath. */
interface Logger {
    void info(String message, Object... arguments)

    void debug(String message, Object... arguments)
}
