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

import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** A Jenkins shared library is linted as Jenkins code throughout (Q-04), with the workspace's classes known (Q-05). */
class SharedLibrarySpec extends Specification {

    @Shared
    LanguageServices services = Fixtures.services('jenkins-lib')

    static String uri(String relative) {
        Fixtures.uri('jenkins-lib', relative)
    }

    static final String CLOSURE_IN_GSTRING = 'def call() {\n    String x = "a"\n    echo "value ${-> x}"\n}\n'

    def 'with vars/ present, src/ and the root Jenkinsfile are discovered under the default source roots'() {
        expect:
        services.workspace().sourceUris().containsAll([uri('src/org/acme/Deployer.groovy'), uri('Jenkinsfile'),
                uri('vars/deployProxy.groovy')])
    }

    def 'src/ of a shared library gets the Jenkins profile and no @CompileStatic advice'() {
        given:
        String deployer = uri('src/org/acme/Deployer.groovy')

        expect:
        services.quality().profileFor(deployer) == QualityServices.PROFILE_JENKINS
        services.quality().profileFor(uri('vars/deployProxy.groovy')) == QualityServices.PROFILE_JENKINS
        !services.quality().check([deployer], null, [QualityServices.INCLUDE_LINT] as Set)
                .diagnostics()[deployer]*.code().contains('CompileStatic')
    }

    def 'the Jenkins rules run in vars/ and src/, not only in files named Jenkinsfile'() {
        expect:
        services.quality().lint(uri('vars/probe.groovy'), CLOSURE_IN_GSTRING, null)*.code().contains('ClosureInGString')
        services.quality().lint(uri('src/org/acme/Probe.groovy'), CLOSURE_IN_GSTRING, null)*.code()
                .contains('ClosureInGString')
    }

    def 'enhanced rules see the workspace classes (Q-05)'() {
        given:
        def basic = Fixtures.services('basic-app')
        String text = 'import app.Greeter\nclass Polite extends Greeter {\n    String greet(String name) {\n        name\n    }\n}\n'

        expect:
        basic.quality().lint(Fixtures.uri('basic-app', 'src/main/groovy/app/Polite.groovy'), text, null)*.code()
                .contains('MissingOverrideAnnotation')

        cleanup:
        basic.close()
    }
}
