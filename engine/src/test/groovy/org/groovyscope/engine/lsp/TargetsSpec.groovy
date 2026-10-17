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

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

class TargetsSpec extends Specification {

    @TempDir Path root

    def 'directories expand to the Groovy documents under them'() {
        given:
        Files.createDirectories(root.resolve('src/a'))
        Files.createDirectories(root.resolve('empty'))
        Path one = Files.writeString(root.resolve('src/a/One.groovy'), 'class One {}\n')
        Path two = Files.writeString(root.resolve('src/Two.groovy'), 'class Two {}\n')
        Path other = Files.writeString(root.resolve('Other.groovy'), 'class Other {}\n')
        def services = new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
        def uri = { Path p -> WorkspaceModel.toUri(p) }

        expect:
        GroovyscopeLanguageServer.targets(services, [uris: [uri(root.resolve('src'))]]) == [uri(one), uri(two)].sort()
        GroovyscopeLanguageServer.targets(services, [uris: [uri(other)]]) == [uri(other)]
        GroovyscopeLanguageServer.targets(services, [uris: [uri(root.resolve('empty'))]]) == [uri(root.resolve('empty'))]
        GroovyscopeLanguageServer.targets(services, [:]) == []
        services.quality().check(GroovyscopeLanguageServer.targets(services, [uris: [uri(root.resolve('empty'))]]),
                null, ['practices'] as Set).diagnostics().values().flatten().empty
    }
}
