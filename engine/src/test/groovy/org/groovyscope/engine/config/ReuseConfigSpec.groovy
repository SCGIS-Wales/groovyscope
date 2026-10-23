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

/** The {@code reuse} section, with its line and statement floors falling back to {@code duplicates}. */
class ReuseConfigSpec extends Specification {

    def 'the reuse thresholds are read from the reuse section, each missing key keeping its default'() {
        when:
        def tuned = EngineConfig.fromJson('''
            {"reuse": {"callOverlap": 0.5, "limit": 4, "ignoreCalls": ["log"], "helperMaxLines": 8,
                       "helpersLimit": 3, "minLines": 9, "blockReview": true},
             "duplicates": {"minLines": 7, "minStatements": 2}}
        ''').practices().reuse()
        def partial = EngineConfig.fromJson('{"reuse": {"limit": "x"}, "duplicates": {"minLines": 5}}').practices().reuse()

        then:
        tuned == new EngineConfig.Reuse(0.5, 4, ['log'], 8, 3, 9, 2)
        partial == new EngineConfig.Reuse(0.6, 10, EngineConfig.Reuse.DEFAULTS.ignoreCalls(), 15, 20, 5, 3)
        EngineConfig.DEFAULTS.practices().reuse() == EngineConfig.Reuse.DEFAULTS
        new EngineConfig.Practices(true, [:], null, null, null, null, null, null).reuse() == EngineConfig.Reuse.DEFAULTS
        new EngineConfig.Practices(true, [:], null, null, null).reuse() == EngineConfig.Reuse.DEFAULTS
    }

    def 'out of range values are clamped and a missing ignore list is empty'() {
        expect:
        new EngineConfig.Reuse(0.6, 0, null, 0, -1, 0, 0) == new EngineConfig.Reuse(0.6, 1, [], 1, 0, 1, 1)
    }

    def 'the common calls are ignored by default'() {
        expect:
        EngineConfig.Reuse.DEFAULTS.ignoreCalls().containsAll(['println', 'toString', 'size', 'get', 'each', 'collect'])
    }
}
