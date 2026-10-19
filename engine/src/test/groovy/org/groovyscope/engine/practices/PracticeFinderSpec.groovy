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

import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.Position
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.model.TextEdit
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Shared
import spock.lang.Specification

/** GS001-GS012 on the practices-sample fixture: what is reported, what is left alone, and the fixes. */
class PracticeFinderSpec extends Specification {

    @Shared
    LanguageServices services = Fixtures.services('practices-sample')

    private static String uri(String name) {
        Fixtures.uri('practices-sample', (name.endsWith('Spec.groovy') ? 'src/test/groovy/sample/' : 'src/main/groovy/sample/') + name)
    }

    private List<List> found(String file) {
        services.quality().practices(uri(file)).collect { [it.diagnostic().code(), it.diagnostic().range().start().line() + 1] }
    }

    private String fixed(String file, String code) {
        def practice = services.quality().practices(uri(file)).find { it.diagnostic().code() == code }
        PracticeFixes.apply(Fixtures.text('practices-sample', uri(file).substring(uri(file).indexOf('src/'))), practice.fix()).text()
    }

    def 'numeric, Elvis and safe-navigation pitfalls are found only where the types are known'() {
        expect:
        found('Numbers.groovy') == [['GS001', 5], ['GS002', 14], ['GS003', 22]]
        services.quality().practices(uri('Numbers.groovy')).count { it.diagnostic().code() == 'GS001' } == 1
        fixed('Numbers.groovy', 'GS001').contains('def result = total.intdiv(parts)')
        fixed('Numbers.groovy', 'GS002').contains('return requested != null ? requested : 50')
        // `parseText(body ?: '{}')` (line 43) is absent from the list above: the parser rejects an empty body, so
        // defaulting empty and null alike is deliberate.
        services.quality().practices(uri('Numbers.groovy')).find { it.diagnostic().code() == 'GS002' }
                .diagnostic().message().contains('distinct from absent')
        fixed('Numbers.groovy', 'GS003').contains('int n = items?.size() ?: 0')
    }

    def 'a default that is the same empty value is not an Elvis pitfall'() {
        given:
        def string = org.codehaus.groovy.ast.ClassHelper.STRING_TYPE
        def list = org.codehaus.groovy.ast.ClassHelper.LIST_TYPE
        def map = org.codehaus.groovy.ast.ClassHelper.MAP_TYPE
        def number = org.codehaus.groovy.ast.ClassHelper.Integer_TYPE
        def constant = { new org.codehaus.groovy.ast.expr.ConstantExpression(it) }

        expect:
        PracticeFinder.sameEmpty(string, constant(''))
        !PracticeFinder.sameEmpty(string, constant('none'))
        PracticeFinder.sameEmpty(number, constant(0))
        !PracticeFinder.sameEmpty(number, constant(50))
        PracticeFinder.sameEmpty(list, new org.codehaus.groovy.ast.expr.ListExpression())
        !PracticeFinder.sameEmpty(list, new org.codehaus.groovy.ast.expr.ListExpression([constant('x')]))
        !PracticeFinder.sameEmpty(list, constant(0))
        PracticeFinder.sameEmpty(map, new org.codehaus.groovy.ast.expr.MapExpression())
        !PracticeFinder.sameEmpty(map, new org.codehaus.groovy.ast.expr.ListExpression())
        !PracticeFinder.sameEmpty(string, new org.codehaus.groovy.ast.expr.VariableExpression('other'))
    }

    def 'File and Path truthiness becomes an explicit null comparison'() {
        expect:
        found('Files.groovy') == [['GS004', 5], ['GS004', 8]]
        fixed('Files.groovy', 'GS004').contains('if (config != null) {')
        services.quality().practices(uri('Files.groovy'))[1].fix()*.newText() == ['other == null']
        services.quality().practices(uri('Files.groovy'))[0].alternatives()*.title() == ['Test that it exists']
        services.quality().practices(uri('Files.groovy'))[0].alternatives()[0].edits()*.newText() == ['config.exists()']
        services.quality().practices(uri('Files.groovy'))[1].alternatives()[0].edits()*.newText() ==
            ['!java.nio.file.Files.exists(other)']
        services.quality().codeActions(uri('Files.groovy'), new Range(new Position(4, 0), new Position(4, 30)))*.title()
            .containsAll(['Compare with != null', 'Test that it exists'])
    }

    def 'GStrings in log calls become placeholders'() {
        expect:
        found('Logging.groovy') == [['GS005', 7]]
        fixed('Logging.groovy', 'GS005').contains("log.info('Loaded {} rows for {}', count, who)")
    }

    def 'security findings: evaluation, @Grab, string commands, quoted SQL interpolation, DOCTYPE'() {
        expect:
        found('Security.groovy') == [['GS007', 6], ['GS006', 9], ['GS006', 11], ['GS008', 12], ['GS008', 13], ['GS012', 15], ['GS009', 17]]
        services.quality().practices(uri('Security.groovy')).findAll { it.diagnostic().code() in ['GS006', 'GS007', 'GS009', 'GS012'] }
            .every { it.diagnostic().severity() == Diagnostic.Severity.ERROR }
        fixed('Security.groovy', 'GS008').contains("['git', 'log', '--oneline'].execute()")
        fixed('Security.groovy', 'GS012').contains('sql.rows("SELECT * FROM users WHERE name = $name")')
        services.quality().practices(uri('Security.groovy')).find { it.diagnostic().code() == 'GS007' }.with {
            !it.fixable() && it.diagnostic().message().contains("declare implementation 'org.example:library:1.0' in the build")
        }
    }

    def 'Groovy 6-only constructs are flagged only while groovy.compat includes 5.x'() {
        given:
        def base = EngineConfig.DEFAULTS
        def groovy6Only = new EngineConfig(base.sourceRoots(), base.classpath(), base.exclude(), base.trust(), base.lint(),
                base.typecheck(), base.format(), base.javaSources(), new EngineConfig.Groovy('6.0', []), base.practices())
        def services6 = new LanguageServices(new WorkspaceModel(Fixtures.project('practices-sample'), groovy6Only), new GroovyCompiler())

        expect:
        found('Modern.groovy') == [['GS010', 3], ['GS010', 7]]
        services6.quality().practices(uri('Modern.groovy')).empty
    }

    def 'Spock .with {} in then: is found and rewritten; @Grab in tests is allowed'() {
        expect:
        found('ThingSpec.groovy') == [['GS011', 13]]
        fixed('ThingSpec.groovy', 'GS011').contains("with(thing) { name == 'x' }\n        with(thing) { name == 'x' }")
        found('Clean.groovy').empty
    }

    def 'severities can be overridden or switched off per code, and the whole set disabled'() {
        given:
        def base = EngineConfig.DEFAULTS
        def tuned = new EngineConfig(base.sourceRoots(), base.classpath(), base.exclude(), base.trust(), base.lint(),
                base.typecheck(), base.format(), base.javaSources(), base.groovy(),
                new EngineConfig.Practices(true, [GS001: 'off', GS002: 'info']))
        def disabled = new EngineConfig(base.sourceRoots(), base.classpath(), base.exclude(), base.trust(), base.lint(),
                base.typecheck(), base.format(), base.javaSources(), base.groovy(), new EngineConfig.Practices(false, [:]))
        def tunedServices = new LanguageServices(new WorkspaceModel(Fixtures.project('practices-sample'), tuned), new GroovyCompiler())
        def disabledServices = new LanguageServices(new WorkspaceModel(Fixtures.project('practices-sample'), disabled), new GroovyCompiler())

        expect:
        tunedServices.quality().practices(uri('Numbers.groovy')).collect { [it.diagnostic().code(), it.diagnostic().severity()] } ==
            [['GS002', Diagnostic.Severity.INFORMATION], ['GS003', Diagnostic.Severity.WARNING]]
        disabledServices.quality().practices(uri('Numbers.groovy')).empty
        EngineConfig.fromJson('{"practices": {"enabled": false, "severity": {"GS005": "off"}}}').practices() ==
            new EngineConfig.Practices(false, [GS005: 'off'])
    }

    def 'check, published diagnostics and code actions include the practices'() {
        given:
        String numbers = uri('Numbers.groovy')

        when:
        def checked = services.quality().check([numbers], null, ['practices'] as Set)
        def published = services.quality().publishedDiagnostics()[numbers]
        def actions = services.quality().codeActions(numbers, new Range(new Position(4, 0), new Position(4, 1)))

        then:
        checked.diagnostics()[numbers]*.code() == ['GS001', 'GS002', 'GS003']
        published*.code().containsAll(['GS001', 'GS002', 'GS003'])
        actions*.title().contains('Use intdiv')
    }

    def 'the batch fix applies only fixes that keep behaviour; the others need their code named'() {
        when:
        def result = services.quality().fix([], [] as Set)
        def annotation = { String file -> result.edits()[uri(file)]?.find { it.newText().contains('@Compile') }?.newText()?.trim() }

        then: '@CompileDynamic is the default made explicit; @CompileStatic and GS011 change behaviour'
        annotation('Clean.groovy') == null
        result.needsReview()[uri('Clean.groovy')].CompileStatic == 1
        annotation('Numbers.groovy').startsWith('@CompileDynamic // TODO justify')
        annotation('Logging.groovy').startsWith('@CompileDynamic')
        result.applied()[uri('ThingSpec.groovy')] == null
        result.needsReview()[uri('ThingSpec.groovy')] == [GS011: 1]
        result.needsReview()[uri('Numbers.groovy')].keySet().containsAll(['GS001', 'GS002', 'GS003'])
        result.rejected().isEmpty()

        when: 'the codes are named'
        def named = services.quality().fix([], ['CompileStatic', 'GS011'] as Set)
        def namedAnnotation = { String file -> named.edits()[uri(file)].find { it.newText().contains('@Compile') }?.newText()?.trim() }

        then: '@CompileStatic only where the class then type-checks, and never in tests'
        namedAnnotation('Clean.groovy') == '@CompileStatic'
        namedAnnotation('Numbers.groovy').startsWith('@CompileDynamic')
        named.applied()[uri('ThingSpec.groovy')] == ['GS011']

        when: 'only some codes'
        def only = services.quality().fix([uri('Numbers.groovy')], ['GS001'] as Set)

        then:
        only.applied() == [(uri('Numbers.groovy')): ['GS001']]

        when: 'only the changed lines: GS001 is on line 5, GS002 on line 14'
        def changed = services.quality().fix([uri('Numbers.groovy')], ['GS001', 'GS002'] as Set,
                [(uri('Numbers.groovy')): [[14, 15] as int[]]])
        def nothing = services.quality().fix([uri('Numbers.groovy')], [] as Set, [(uri('Numbers.groovy')): []])

        then:
        changed.applied() == [(uri('Numbers.groovy')): ['GS002']]
        changed.appliedCounts()[uri('Numbers.groovy')] == [GS002: 1]
        nothing.edits().isEmpty() && nothing.left().isEmpty()
        org.groovyscope.engine.lsp.GroovyscopeLanguageServer.lineRanges([(uri('Numbers.groovy')): [[14, 15], 'x']])
                .get(uri('Numbers.groovy'))*.toList() == [[14, 15]]
        org.groovyscope.engine.lsp.GroovyscopeLanguageServer.lineRanges(null) == null
    }

    def 'fix application skips overlapping edits and the catalogue links every code'() {
        given:
        def edits = [new TextEdit(Range.of(0, 0, 0, 3), 'abc'), new TextEdit(Range.of(0, 1, 0, 2), 'X'), new TextEdit(Range.of(0, 4, 0, 5), 'Y')]

        expect:
        PracticeFixes.apply('012 456', edits).text() == 'abc Y56'
        PracticeFixes.apply('012 456', edits).edits().size() == 2
        PracticeCatalog.bundled().link('GS001').endsWith('docs/practices.md#23-numeric-pitfalls')
        PracticeCatalog.bundled().link('NoSuchCode') == null
        (1..16).every { PracticeCatalog.bundled().find(String.format('GS%03d', it)).present }
    }
}
