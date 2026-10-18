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
package org.groovyscope.engine.lsp

import spock.lang.Specification

/** groovyscope/layout: boxes and links in, positions, routes and size out; missing values take defaults. */
class LayoutJsonSpec extends Specification {

    def 'the request is read with defaults and answered with positions, routes and the drawing size'() {
        when:
        def answer = LayoutJson.layout([
                boxes  : [[id: 'A', width: 120, height: 46], [id: 'B'], 'not a box'],
                links  : [[id: '0', source: 'A', target: 'B', hierarchy: false], [id: '1', source: 'A', target: 'Z']],
                options: [layerBound: 2, aspectRatio: 1.4],
        ])

        then:
        answer.boxes.keySet() == ['A', 'B'] as Set
        answer.routes.keySet() == ['0'] as Set
        answer.routes['0'].size() >= 2
        answer.width > 0 && answer.height > 0
        LayoutJson.layout([:]).boxes == [:]
    }
}
