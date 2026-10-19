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
package org.groovyscope.engine.log

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/** The log file: only under the cache or temp directory, never through a link, private, rotated, one line a record. */
class EngineLogSpec extends Specification {

    @TempDir
    Path temp

    ByteArrayOutputStream captured = new ByteArrayOutputStream()

    def setup() {
        EngineLog.redirect(new PrintStream(captured, true, 'UTF-8'))
    }

    def cleanup() {
        EngineLog.redirect(System.err)
        EngineLog.configure(null, null)
    }

    def 'a file outside the cache and temp directories is refused'() {
        when:
        EngineLog.configure('info', '~/.bashrc')

        then:
        EngineLog.file() == null
        captured.toString('UTF-8').contains('logging.file ~/.bashrc is refused')
    }

    def 'records are escaped onto one line, the file is private and rotated'() {
        given:
        Path log = temp.resolve('logs/engine.log')

        when:
        EngineLog.configure('info', log.toString(), null, 300)
        EngineLog.info('checked %s', 'a\nb.groovy')
        EngineLog.error(new IllegalStateException('bad\r\nthing'), 'failed')
        EngineLog.info('after %s', 'rotation')

        then:
        Files.readAllLines(log).size() == 1
        Files.readAllLines(log)[0].contains('after rotation')
        def previous = Files.readAllLines(temp.resolve('logs/engine.log.1'))
        previous.size() <= 2
        previous.any { it.contains('checked a\\nb.groovy') } || previous.any { it.contains('IllegalStateException: bad\\r\\nthing') }
        PosixFilePermissions.toString(Files.getPosixFilePermissions(log)) == 'rw-------'
        EngineLog.escape('tab\there\u001b\u007f') == 'tab\there\\x1b\\x7f'
    }

    def 'a log file reached through a symbolic link is never written'() {
        given:
        Path outside = Files.createTempFile(temp, 'victim', '.txt')
        Files.writeString(outside, 'original')
        Path link = temp.resolve('engine.log')
        Files.createSymbolicLink(link, outside)

        when:
        EngineLog.configure('info', link.toString(), null, 1000)
        EngineLog.info('injected')

        then:
        Files.readString(outside) == 'original'
        EngineLog.file() == null
        captured.toString('UTF-8').contains('cannot write the log file')
    }

    def 'a directory link that leaves the allowed bases is refused'() {
        given:
        Path base = Files.createDirectories(temp.resolve('cache'))
        Path elsewhere = Files.createDirectories(temp.resolve('elsewhere'))
        Files.createSymbolicLink(base.resolve('escape'), elsewhere)

        expect:
        !EngineLog.allowed(base.resolve('escape/engine.log'), [base])
        EngineLog.allowed(base.resolve('logs/engine.log'), [base])
        !EngineLog.allowed(base.resolve('x.log'), [temp.resolve('missing')])
    }
}
