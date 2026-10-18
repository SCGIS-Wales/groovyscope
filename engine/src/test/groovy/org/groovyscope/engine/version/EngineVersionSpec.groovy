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
package org.groovyscope.engine.version

import spock.lang.Specification

class EngineVersionSpec extends Specification {

    def 'current() returns the version expanded by Gradle'() {
        expect:
        EngineVersion.current() ==~ /\d+\.\d+\.\d+.*/
    }

    def 'read() falls back to unknown for #label'() {
        expect:
        EngineVersion.read(stream) == 'unknown'

        where:
        label              | stream
        'a missing stream' | null
        'an unexpanded placeholder' | new ByteArrayInputStream('version=${version}\n'.bytes)
        'an empty value'   | new ByteArrayInputStream('version=\n'.bytes)
        'no version key'   | new ByteArrayInputStream('other=1\n'.bytes)
    }

    def 'read() trims the value'() {
        expect:
        EngineVersion.read(new ByteArrayInputStream('version=  1.2.3  \n'.bytes)) == '1.2.3'
    }

    def 'codenarc() returns the CodeNarc release the build embedded'() {
        expect:
        EngineVersion.codenarc() ==~ /\d+\.\d+(\.\d+)?/
    }

    def 'read() reads the codenarc key and falls back to unknown for #label'() {
        expect:
        EngineVersion.read(new ByteArrayInputStream(content.bytes), EngineVersion.CODENARC_KEY) == expected

        where:
        label                       | content                                   | expected
        'an expanded value'         | 'version=1.0.0\ncodenarc= 4.0.0 \n'       | '4.0.0'
        'an unexpanded placeholder' | 'version=1.0.0\ncodenarc=${codenarc}\n'   | 'unknown'
        'a missing key'             | 'version=1.0.0\n'                         | 'unknown'
    }
}
