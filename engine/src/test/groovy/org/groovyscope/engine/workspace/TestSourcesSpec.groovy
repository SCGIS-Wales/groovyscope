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
package org.groovyscope.engine.workspace

import spock.lang.Specification

/** One rule for test code: any source set but main, or a test-like class name. */
class TestSourcesSpec extends Specification {

    def 'a file in a source set other than main is test code'() {
        expect:
        TestSources.isTestPath(path) == test

        where:
        path                                                   | test
        'file:///w/src/test/groovy/app/A.groovy'               | true
        'file:///w/src/it/java/app/StoreIT.java'               | true
        'file:///w/src/integrationTest/groovy/app/A.groovy'    | true
        'C:\\w\\src\\testFixtures\\java\\app\\A.java'          | true
        'file:///w/src/main/groovy/app/A.groovy'               | false
        'file:///w/src/org/acme/Shared.groovy'                 | false
        'file:///w/vars/build.groovy'                          | false
        null                                                   | false
    }

    def 'test class names'() {
        expect:
        TestSources.isTestName(name) == test
        TestSources.isTest('file:///w/src/main/groovy/X.groovy', name) == test

        where:
        name                     | test
        'StoreTest'              | true
        'StoreTests'             | true
        'StoreSpec'              | true
        'StoreIT'                | true
        'StoreITCase'            | true
        'StoreIntegrationTest'   | true
        'Store'                  | false
        null                     | false
    }
}
