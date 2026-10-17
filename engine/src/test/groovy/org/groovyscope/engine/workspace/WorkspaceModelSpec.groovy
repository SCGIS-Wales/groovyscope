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

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Specification
import spock.lang.TempDir

class WorkspaceModelSpec extends Specification {

    @TempDir
    Path temp

    def 'discovers Groovy sources under the configured roots, sorted'() {
        given:
        def model = new WorkspaceModel(Fixtures.project('basic-app'), EngineConfig.DEFAULTS)

        when:
        def files = model.discoverSourceFiles()*.fileName*.toString()

        then:
        files == ['Extras.groovy', 'Greeter.groovy', 'Main.groovy', 'Shapes.groovy', 'Person.groovy', 'GreeterSpec.groovy']
    }

    def 'walks the whole workspace when no configured root exists'() {
        given:
        def model = new WorkspaceModel(Fixtures.project('gradle-multi'), EngineConfig.DEFAULTS)

        expect:
        model.discoverSourceFiles()*.fileName*.toString().sort() == ['App.groovy', 'Util.groovy', 'build.gradle', 'settings.gradle']
    }

    def 'excludes build output and honours classpath resolution'() {
        given:
        Files.createDirectories(temp.resolve('src/main/groovy'))
        Files.createDirectories(temp.resolve('build/classes'))
        Files.writeString(temp.resolve('src/main/groovy/A.groovy'), 'class A {}')
        Files.writeString(temp.resolve('build/classes/Gen.groovy'), 'class Gen {}')
        Files.writeString(temp.resolve('notes.txt'), 'nope')
        def config = new EngineConfig(['src/main/groovy', 'missing'], ['libs/x.jar'], ['**/build/**'], false)
        def model = new WorkspaceModel(temp, config)

        expect:
        model.discoverSourceFiles()*.fileName*.toString() == ['A.groovy']
        model.classpath() == [temp.resolve('libs/x.jar').normalize()]
        model.isExcluded(temp.resolve('build/classes/Gen.groovy'))
        !model.isExcluded(temp.resolve('src/main/groovy/A.groovy'))
    }

    def 'open documents overlay the disk and closing restores it'() {
        given:
        Files.createDirectories(temp.resolve('src/main/groovy'))
        Path file = temp.resolve('src/main/groovy/A.groovy')
        Files.writeString(file, 'class A {}')
        def model = new WorkspaceModel(temp, EngineConfig.DEFAULTS)
        String uri = WorkspaceModel.toUri(file)

        when:
        model.open(uri, 'class A { int edited }')

        then:
        model.isOpen(uri)
        model.openText(uri) == 'class A { int edited }'
        model.sources()[uri] == 'class A { int edited }'

        when:
        model.change(uri, 'class A { int changed }')

        then:
        model.sources()[uri] == 'class A { int changed }'

        when:
        model.close(uri)

        then:
        !model.isOpen(uri)
        model.sources()[uri] == 'class A {}'
    }

    def 'open documents that are not on disk are still compiled when they are Groovy'() {
        given:
        def model = new WorkspaceModel(temp, EngineConfig.DEFAULTS)

        when:
        model.open('file:///virtual/New.groovy', 'class New {}')
        model.open('file:///virtual/notes.txt', 'text')

        then:
        model.sources().keySet() == ['file:///virtual/New.groovy'] as Set
    }

    def 'uri and path conversions'() {
        expect:
        WorkspaceModel.toPath(WorkspaceModel.toUri(temp)) == temp.toAbsolutePath().normalize()
        WorkspaceModel.toPath('http://example.com/x') == null
        WorkspaceModel.toPath(':bad') == null
        WorkspaceModel.isGroovyUri('file:///a/b/Jenkinsfile')
        !WorkspaceModel.isGroovyUri('file:///a/b/x.java')
    }
}
