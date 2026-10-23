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

import spock.lang.Specification

/** The design thresholds ({@code design.*}, GS090 to GS097) reach the engine as the other sections do. */
class DesignConfigSpec extends Specification {

    def 'the design thresholds are read from the design section, each missing key keeping its default'() {
        when:
        def tuned = EngineConfig.fromJson('''
            {"design": {"maxMethods": 30, "maxFields": 8, "maxLines": 400, "maxParameters": 5, "envyMinUses": 4}}
        ''').practices().design()
        def partial = EngineConfig.fromJson('{"design": {"maxLines": 900, "maxFields": "x"}}').practices().design()

        then:
        tuned == new EngineConfig.Design(30, 8, 400, 5, 4)
        partial == new EngineConfig.Design(25, 12, 900, 4, 3)
        EngineConfig.DEFAULTS.practices().design() == EngineConfig.Design.DEFAULTS
        new EngineConfig.Practices(true, [:], null, null, null, null, null, null, null).design() == EngineConfig.Design.DEFAULTS
        new EngineConfig.Practices(true, [:], null, null, null).design() == EngineConfig.Design.DEFAULTS
    }

    def 'a threshold under one is raised to one'() {
        expect:
        new EngineConfig.Design(0, -1, 0, 0, 0) == new EngineConfig.Design(1, 1, 1, 1, 1)
    }
}
