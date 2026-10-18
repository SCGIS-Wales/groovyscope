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
package org.groovyscope.engine.practices

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

class InferTypeFixSpec extends Specification {

    @TempDir Path root

    def 'untyped locals get their inferred type, after def or after final'() {
        given:
        Path file = Files.writeString(root.resolve('Strings.groovy'), '''\
            |import groovy.transform.CompileStatic
            |
            |@CompileStatic
            |class Strings {
            |    static String clean(String value) {
            |        def prefix = 'p'
            |        final pattern = /[^a-z]/
            |        return prefix + value.replaceAll(pattern, '')
            |    }
            |}
            |'''.stripMargin())
        def services = new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
        def uri = WorkspaceModel.toUri(file)

        when:
        def result = services.quality().fix([uri], ['NoDef', 'VariableTypeRequired'] as Set)
        def fixed = PracticeFixes.apply(Files.readString(file), result.edits()[uri]).text()

        then:
        fixed.contains("        String prefix = 'p'")
        fixed.contains('        final String pattern = /[^a-z]/')
        result.rejected().isEmpty()
    }

    def 'a type from another package of the workspace is written with its import'() {
        given:
        Files.createDirectories(root.resolve('app/model'))
        Files.writeString(root.resolve('app/model/Rule.groovy'), 'package app.model\nclass Rule {\n    String name\n}\n')
        Path file = Files.writeString(root.resolve('app/Report.groovy'), '''\
            |package app
            |
            |import groovy.transform.CompileStatic
            |
            |@CompileStatic
            |class Report {
            |    String first() {
            |        def rule = new app.model.Rule(name: 'a')
            |        return rule.name
            |    }
            |}
            |'''.stripMargin())
        def services = new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
        def uri = WorkspaceModel.toUri(file)

        when:
        def result = services.quality().fix([uri], ['NoDef', 'VariableTypeRequired'] as Set)
        def fixed = PracticeFixes.apply(Files.readString(file), result.edits()[uri]).text()

        then: 'the simple name resolves because the import came with it'
        fixed.contains('        Rule rule = new app.model.Rule')
        fixed.contains('import app.model.Rule\n')
        new GroovyCompiler().compile([(uri): fixed, (WorkspaceModel.toUri(root.resolve('app/model/Rule.groovy'))):
                Files.readString(root.resolve('app/model/Rule.groovy'))], [], false).diagnostics()[uri].isEmpty()
    }

    def 'a local whose type cannot be inferred is counted as left, not as applied'() {
        given:
        Path file = Files.writeString(root.resolve('Mixed.groovy'), '''\
            |class Mixed {
            |    Object run(Map source) {
            |        def known = 'a'
            |        def unknown = source.anything
            |        return known + unknown
            |    }
            |}
            |'''.stripMargin())
        def services = new LanguageServices(new WorkspaceModel(root, EngineConfig.DEFAULTS), new GroovyCompiler())
        def uri = WorkspaceModel.toUri(file)

        when:
        def result = services.quality().fix([uri], ['NoDef'] as Set)

        then:
        result.appliedCounts()[uri] == [NoDef: 1]
        result.left()[uri] == [NoDef: 1]
    }

    def 'whole-line inserts at one point all apply; other inserts at one point do not'() {
        given:
        def first = new org.groovyscope.engine.model.TextEdit(org.groovyscope.engine.model.Range.of(1, 0, 1, 0), 'import a.A\n')
        def second = new org.groovyscope.engine.model.TextEdit(org.groovyscope.engine.model.Range.of(1, 0, 1, 0), 'import b.B\n')

        expect:
        PracticeFixes.apply('package p\nclass C {}\n', [first, second]).text() == 'package p\nimport a.A\nimport b.B\nclass C {}\n'
    }

    def 'identical edits apply once and two inserts at one point do not both apply'() {
        given:
        def insert = new org.groovyscope.engine.model.TextEdit(org.groovyscope.engine.model.Range.of(0, 4, 0, 4), 'X ')
        def other = new org.groovyscope.engine.model.TextEdit(org.groovyscope.engine.model.Range.of(0, 4, 0, 4), 'Y ')

        expect:
        PracticeFixes.apply('abc def', [insert, insert]).text() == 'abc X def'
        PracticeFixes.apply('abc def', [insert, other]).text() == 'abc X def'
        PracticeFixes.apply('abc def', [insert, other]).edits() == [insert]
    }
}
