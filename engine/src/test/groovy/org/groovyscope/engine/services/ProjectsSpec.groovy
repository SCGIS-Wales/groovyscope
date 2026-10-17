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

import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.model.SymbolKind
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification

/** The other fixture layouts: Jenkins shared library, dynamic DSL, Gradle multi-project, broken file, Groovy 5 code. */
class ProjectsSpec extends Specification {

    def 'jenkins shared library: vars script resolves into src classes'() {
        given:
        def config = new EngineConfig(['vars', 'src'], [], EngineConfig.DEFAULTS.exclude(), false)
        def services = Fixtures.services('jenkins-lib', config)
        String script = 'vars/deployProxy.groovy'

        when:
        def definition = services.definition(Fixtures.uri('jenkins-lib', script),
            Fixtures.pos('jenkins-lib', script, 'deployer.deploy(', 1, 9)).get()

        then:
        definition.symbol().detail() == 'void deploy(String proxy)'
        definition.symbol().location().uri() == Fixtures.uri('jenkins-lib', 'src/org/acme/Deployer.groovy')
        definition.confidence() == Confidence.INFERRED

        and: 'the script itself is a symbol with its call method'
        services.documentSymbols(Fixtures.uri('jenkins-lib', script))[0].children()*.name() == ['call']
    }

    def 'dynamic DSL: calls answered by methodMissing stay unresolved rather than guessed'() {
        given:
        def services = Fixtures.services('dynamic-dsl')
        String usage = 'src/main/groovy/dsl/Usage.groovy'

        expect:
        services.definition(Fixtures.uri('dynamic-dsl', usage), Fixtures.pos('dynamic-dsl', usage, "anything 'x'")).empty
        services.definition(Fixtures.uri('dynamic-dsl', usage), Fixtures.pos('dynamic-dsl', usage, 'configure {')).get().with {
            symbol().name() == 'configure' && confidence() == Confidence.STATIC
        }
    }

    def 'gradle multi-project: the whole tree is walked and cross-project calls resolve'() {
        given:
        def services = Fixtures.services('gradle-multi')
        String app = 'app/src/main/groovy/app/App.groovy'

        when:
        def definition = services.definition(Fixtures.uri('gradle-multi', app),
            Fixtures.pos('gradle-multi', app, "Util.shout('hi')", 1, 5)).get()

        then:
        definition.symbol().detail() == 'static String shout(String text)'
        definition.symbol().location().uri() == Fixtures.uri('gradle-multi', 'core/src/main/groovy/core/Util.groovy')
        definition.confidence() == Confidence.STATIC
        services.workspaceSymbols('')*.name().containsAll(['App', 'Util', 'shout', 'run'])
    }

    def 'broken file: navigation keeps working in the files that parse'() {
        given:
        def services = Fixtures.services('broken')
        String fine = 'src/main/groovy/Fine.groovy'
        String broken = 'src/main/groovy/Broken.groovy'

        expect:
        services.documentSymbols(Fixtures.uri('broken', fine))[0].children()*.name() == ['ok']
        services.documentSymbols(Fixtures.uri('broken', broken)).empty
        services.diagnostics()[Fixtures.uri('broken', broken)].any { it.code() == 'syntax' }
    }

    def 'calls into JDK types resolve to external declarations without a location'() {
        given:
        def services = Fixtures.services('groovy51-lib')
        String legacy = 'src/main/groovy/lib5/Legacy.groovy'

        when:
        def definition = services.definition(Fixtures.uri('groovy51-lib', legacy),
            Fixtures.pos('groovy51-lib', legacy, 'items.size()', 1, 6)).get()

        then:
        !definition.inWorkspace
        definition.symbol().name() == 'size'
        definition.symbol().kind() == SymbolKind.METHOD
        definition.confidence() == Confidence.STATIC

        and: 'a field typed with generics renders its type'
        services.hover(Fixtures.uri('groovy51-lib', legacy), Fixtures.pos('groovy51-lib', legacy, 'items << item'))
            .get().markdown().contains('List<String> items')
    }
}
