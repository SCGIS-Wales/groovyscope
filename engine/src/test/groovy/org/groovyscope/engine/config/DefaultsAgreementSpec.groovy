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
package org.groovyscope.engine.config

import com.google.gson.Gson
import groovy.yaml.YamlSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification

/** The defaults the front end ships (defaults.yaml) and the engine's own defaults are the same settings. */
class DefaultsAgreementSpec extends Specification {

    static Path defaultsFile() {
        Path here = Paths.get('').toAbsolutePath()
        for (Path dir = here; dir != null; dir = dir.parent) {
            Path candidate = dir.resolve('python/src/groovyscope/defaults.yaml')
            if (Files.isRegularFile(candidate)) {
                return candidate
            }
        }
        throw new IllegalStateException('defaults.yaml not found above ' + here)
    }

    def 'EngineConfig.fromJson(defaults.yaml) equals EngineConfig.DEFAULTS'() {
        given:
        def defaults = new YamlSlurper().parse(defaultsFile())

        when:
        def parsed = EngineConfig.fromJson(new Gson().toJson(defaults))

        then:
        parsed == EngineConfig.DEFAULTS
    }
}
