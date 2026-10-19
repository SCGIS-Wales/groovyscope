/*
 * Copyright 2026 SCGIS-Wales and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.groovyscope.engine.practices

import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import spock.lang.Specification

/** GS005 only for proven loggers; the {} fix only where the library takes placeholders (1.7). */
class LoggingCheckSpec extends Specification {

    static final String URI = 'file:///w/src/main/groovy/app/Job.groovy'

    private static List<PracticeFinder.Practice> gs005(String text) {
        def module = new GroovyCompiler().compile([(URI): text], [], false).modules()[URI]
        PracticeFinder.find(URI, module, text, EngineConfig.DEFAULTS).findAll { it.diagnostic().code() == 'GS005' }
    }

    def 'java.util.logging and commons logging get no placeholder fix'() {
        when:
        def jul = gs005('''\
class Job {
    java.util.logging.Logger log = java.util.logging.Logger.getLogger('job')
    void run(int n) {
        log.info("ran ${n}")
    }
}
''')

        then:
        jul.size() == 1
        !jul[0].fixable()
        jul[0].diagnostic().severity() == Diagnostic.Severity.WARNING
        jul[0].diagnostic().message().contains('guard the call with the level check')
    }

    def '@Log is information without a fix; @Slf4j is information with the fix'() {
        when:
        def julAnnotated = gs005('''\
@groovy.util.logging.Log
class Job {
    void run(int n) {
        log.info("ran ${n}")
    }
}
''')
        def slf4jAnnotated = gs005('''\
@groovy.util.logging.Slf4j
class Job {
    void run(int n) {
        log.info("ran ${n}")
    }
}
''')

        then:
        julAnnotated.size() == 1 && !julAnnotated[0].fixable()
        julAnnotated[0].diagnostic().severity() == Diagnostic.Severity.INFORMATION
        slf4jAnnotated.size() == 1 && slf4jAnnotated[0].fixable()
        slf4jAnnotated[0].diagnostic().severity() == Diagnostic.Severity.INFORMATION
        slf4jAnnotated[0].diagnostic().message().contains('placeholders keep the message template constant')
        slf4jAnnotated[0].fix()*.newText() == ["'ran {}', n"]
    }

    def 'no placeholder fix for a lazy closure value or a trailing Throwable, which SLF4J would treat differently'() {
        when:
        def found = gs005('''\
class Job {
    org.slf4j.Logger log
    void run(int count, RuntimeException failure) {
        log.info("lazy ${-> count}")
        log.error("failed ${failure}")
        try {
            count++
        } catch (e) {
            log.warn("caught ${e}")
        }
        log.info("plain ${count}")
    }
}
''')

        then:
        found.size() == 4
        found[0..2].every { !it.fixable() }
        found[3].fixable()
    }

    def 'a receiver merely named log is not a logger'() {
        expect:
        gs005('''\
class Job {
    def log = new Object()
    void run(int n) {
        log.info("ran ${n}")
        logger.debug("x ${n}")
    }
}
''').empty
    }
}
