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
package org.groovyscope.engine.search

import java.nio.file.Path
import java.nio.file.Paths
import spock.lang.Specification
import spock.lang.TempDir

/** Workspace overview: line counts, test detection, packages and untested classes. */
class WorkspaceOverviewSpec extends Specification {

    @TempDir Path root

    def 'line counts ignore the empty remainder after a final line break'() {
        expect:
        WorkspaceOverview.lineCount(text) == lines

        where:
        text        | lines
        null        | 0
        ''          | 0
        'a'         | 1
        'a\n'       | 1
        'a\nb'      | 2
        'a\n\nb\n'  | 3
    }

    def 'tests are recognised by source root or by name'() {
        given:
        Path top = Paths.get('/work')

        expect:
        WorkspaceOverview.isTest(top, Paths.get('/work', relative).toUri().toString()) == test

        where:
        relative                                   | test
        'src/test/groovy/a/B.groovy'               | true
        'tests/B.groovy'                           | true
        'src/integrationTest/groovy/B.groovy'      | true
        'src/main/groovy/a/BSpec.groovy'           | true
        'src/main/groovy/a/BTests.groovy'          | true
        'src/main/groovy/a/B.groovy'               | false
        'vars/deploy.groovy'                       | false
    }

    def 'non-file URIs are not tests and packages fall back to the source text'() {
        expect:
        !WorkspaceOverview.isTest(root, 'untitled:Scratch.groovy')
        WorkspaceOverview.packageOf(null, 'package a.b\nclass X {}') == 'a.b'
        WorkspaceOverview.packageOf(null, 'class X {}') == '(default)'
        WorkspaceOverview.packageOf(null, null) == '(default)'
    }

    def 'documents that did not compile still count; scripts, interfaces and inner classes are never untested'() {
        given:
        def snapshot = SearchFixtures.compile(root, [
                'src/main/groovy/a/Api.groovy'    : 'package a\ninterface Api { void go() }\n',
                'src/main/groovy/a/Impl.groovy'   : 'package a\nclass Impl implements Api {\n    void go() {}\n    class Part {}\n}\n',
                'src/main/groovy/a/ImplTests.groovy' : 'package a\nclass ImplTests {}\n',
                'scripts/run.groovy'              : 'println "hi"\n',
                'src/main/groovy/b/Broken.groovy' : 'package b\nclass Broken {\n'])
        Map<String, String> sources = [:]
        root.toFile().eachFileRecurse { File file ->
            if (file.name.endsWith('.groovy')) {
                sources[file.toPath().toUri().toString()] = file.text
            }
        }
        sources['file:///elsewhere/Other.java'] = 'class Other {}'

        when:
        def overview = WorkspaceOverview.of(root, sources, snapshot.result().modules())

        then:
        overview.files() == 5
        overview.testFiles() == 1
        !overview.untested().any { it.startsWith('a.') }
        overview.packages()*.name().toSet() == ['a', 'b', '(default)'] as Set
        overview.largestClasses()*.name().contains('a.Impl$Part')
        overview.largestFiles().size() == 5
    }
}
