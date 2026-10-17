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
package org.groovyscope.engine.services

import org.groovyscope.engine.index.TextLines
import org.groovyscope.engine.model.Position
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

class SignatureSupportSpec extends Specification {

    def 'active parameter counts top-level commas only'() {
        given:
        def lines = new TextLines(text)

        expect:
        SignatureSupport.activeParameter(lines, new Position(0, 0), new Position(0, cursor)) == expected

        where:
        text                              | cursor | expected
        "f(a, b)"                         | 5      | 1
        "f(a, g(x, y), b)"                | 14     | 2
        "f('a, b', c)"                    | 10     | 1
        "f(\"x\\\", y\", z)"              | 12     | 1
        "f([1, 2], m)"                    | 10     | 1
        "f(a"                             | 3      | 0
        "f"                               | 1      | 0
    }

    def 'no call under the cursor yields no signature help'() {
        given:
        def services = Fixtures.services('basic-app')
        String greeter = Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy')

        expect:
        services.signatureHelp(greeter, new Position(0, 0)).empty
    }

    def 'a constructor call reports the constructor signatures'() {
        given:
        def services = Fixtures.services('basic-app')
        String main = Fixtures.uri('basic-app', 'src/main/groovy/app/Main.groovy')

        when:
        def help = services.signatureHelp(main, Fixtures.pos('basic-app', 'src/main/groovy/app/Main.groovy', "new Person('Ada', 36)", 1, 18)).get()

        then:
        help.signatures()*.label() == ['Person(String name, int age)']
        help.activeParameter() == 1
    }
}
