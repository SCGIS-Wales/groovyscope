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
package org.groovyscope.engine.probes

import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/**
 * Probe 0.5 of the improvement plan, flipped by Phase 4: enhanced rules now see the workspace's own classes (Q-05),
 * and the jenkins.xml rules run in every file of the Jenkins profile, a shared library's vars/ and src/ included (Q-04).
 */
class CodeNarcEnhancedProbeSpec extends Specification {

    @Shared
    LanguageServices services = Fixtures.services('basic-app')

    static final String CLOSURE_IN_GSTRING = 'def call() {\n    String x = "a"\n    echo "value ${-> x}"\n}\n'

    private Set<String> codes(String text, String profile, String uri = 'file:///probe/src/main/groovy/app/Probe.groovy') {
        services.quality().lint(uri, text, profile)*.code() as Set
    }

    def 'enhanced rules fire for JDK types and for workspace classes'() {
        expect:
        'MissingOverrideAnnotation' in codes('class Task implements Runnable {\n    void run() {\n    }\n}\n', 'default')
        'CloneWithoutCloneable' in codes('class Copy {\n    Copy clone() {\n        null\n    }\n}\n', 'default')
        'MissingOverrideAnnotation' in codes(
                'import app.Greeter\nclass Polite extends Greeter {\n    String greet(String name) {\n        name\n    }\n}\n',
                'default')
    }

    def 'jenkins.xml rules run in every file of the Jenkins profile'() {
        expect:
        'ClosureInGString' in codes(CLOSURE_IN_GSTRING, 'jenkins', 'file:///probe/Jenkinsfile')
        'ClosureInGString' in codes(CLOSURE_IN_GSTRING, 'jenkins', 'file:///probe/vars/deploy.groovy')
        'ClosureInGString' in codes(CLOSURE_IN_GSTRING, 'jenkins', 'file:///probe/src/org/acme/Deploy.groovy')
    }
}
