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
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.services.LanguageServices
import spock.lang.Specification
import spock.lang.TempDir

/** Past workspace.maxFiles or workspace.maxTotalBytes the rest is left out and reported once as GS101. */
class BudgetSpec extends Specification {

    @TempDir Path root

    private EngineConfig budget(int maxFiles, long maxTotalBytes) {
        EngineConfig.fromJson("""{"workspace": {"maxFiles": $maxFiles, "maxTotalBytes": $maxTotalBytes}}""")
    }

    def setup() {
        Files.createDirectories(root.resolve('src/main/groovy'))
        ['A', 'B', 'C'].each { Files.writeString(root.resolve("src/main/groovy/${it}.groovy"), "class $it { void run() {} }\n") }
    }

    def 'the defaults take everything in'() {
        given:
        def model = new WorkspaceModel(root, EngineConfig.DEFAULTS)

        expect:
        model.sources().size() == 3
        model.omitted().empty
        EngineConfig.DEFAULTS.budget() == EngineConfig.Budget.DEFAULTS
        budget(2, 0).budget().maxFiles() == 2
    }

    def 'a file limit leaves the last files out and the analysis says so once'() {
        given:
        def model = new WorkspaceModel(root, budget(2, 0))
        def services = new LanguageServices(model, new GroovyCompiler())

        when:
        def diagnostics = services.diagnostics()

        then:
        model.sources().size() == 2
        model.omitted()*.fileName*.toString() == ['C.groovy']
        def warnings = diagnostics.values().flatten().findAll { it.code() == LanguageServices.BUDGET_CODE }
        warnings.size() == 1
        warnings[0].message().contains('1 file(s) left out')
        warnings[0].message().contains('C.groovy')
        warnings[0].message().contains('workspace.maxFiles (2)')
    }

    def 'a byte limit works the same way'() {
        given:
        def model = new WorkspaceModel(root, budget(0, 40))

        expect:
        model.sources().size() == 1
        model.omitted().size() == 2
    }
}
