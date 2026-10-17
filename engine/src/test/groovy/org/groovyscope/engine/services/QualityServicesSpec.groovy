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

import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.lint.CodeNarcAnalyzer
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.typecheck.TypeCheckAnalyzer
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Shared
import spock.lang.Specification

class QualityServicesSpec extends Specification {

    static final String MESSY = 'src/main/groovy/sample/Messy.groovy'
    static final String TYPED = 'src/main/groovy/sample/Typed.groovy'
    static final String DEPLOY = 'vars/deploy.groovy'

    @Shared
    LanguageServices services = Fixtures.services('lint-sample')

    @Shared
    QualityServices quality = services.quality()

    private static String uri(String relative) {
        Fixtures.uri('lint-sample', relative)
    }

    def 'profiles are picked from the path'() {
        expect:
        QualityServices.profileFor('file:///p/Jenkinsfile') == 'jenkins'
        QualityServices.profileFor('file:///p/Jenkinsfile.prod') == 'jenkins'
        QualityServices.profileFor('file:///lib/vars/deploy.groovy') == 'jenkins'
        QualityServices.profileFor('file:///p/src/A.groovy') == 'default'
    }

    def 'check runs every analysis and sorts findings by position'() {
        when:
        def result = quality.check([], null, [] as Set)

        then:
        result.diagnostics().keySet() == [uri(MESSY), uri(TYPED), uri('src/main/groovy/sample/Tidy.groovy'), uri(DEPLOY)] as Set
        result.diagnostics()[uri(MESSY)]*.source().contains(CodeNarcAnalyzer.SOURCE)
        result.diagnostics()[uri(TYPED)].any { it.source() == TypeCheckAnalyzer.SOURCE && it.message().contains('lenght') }
        result.diagnostics()[uri(MESSY)] == result.diagnostics()[uri(MESSY)].sort(false) { it.range().start() }
        result.total() > 0
        result.count(Diagnostic.Severity.WARNING) > 0
    }

    def 'check can be narrowed to files and analyses'() {
        when:
        def lintOnly = quality.check([uri(MESSY)], 'default', ['lint'] as Set)
        def compileOnly = quality.check([uri(MESSY)], null, ['compile'] as Set)

        then:
        lintOnly.diagnostics().keySet() == [uri(MESSY)] as Set
        lintOnly.diagnostics()[uri(MESSY)].every { it.source() == CodeNarcAnalyzer.SOURCE }
        compileOnly.diagnostics()[uri(MESSY)].every { it.source() == GroovyCompiler.SOURCE }
    }

    def 'lint results are cached per content and profile'() {
        given:
        String text = Fixtures.text('lint-sample', MESSY)

        expect:
        quality.lint(uri(MESSY), text, null).is(quality.lint(uri(MESSY), text, null))
        !quality.lint(uri(MESSY), text + '\n// changed\n', null).is(quality.lint(uri(MESSY), text, null))
    }

    def 'published diagnostics merge compile errors with lint findings'() {
        when:
        def published = quality.publishedDiagnostics()

        then:
        published[uri(MESSY)].any { it.source() == CodeNarcAnalyzer.SOURCE }
        published.containsKey(uri('src/main/groovy/sample/Tidy.groovy'))
    }

    def 'format produces one whole-document edit that removes flagged semicolons and whitespace'() {
        when:
        def edits = quality.format(uri(MESSY))
        String formatted = edits[0].newText()

        then:
        edits.size() == 1
        formatted.contains('String name = "plain"\n')
        formatted.contains('import java.util.List\n')
        !formatted.contains('   \n')
        !formatted.contains('\n\n\n')
        quality.format('file:///nowhere/Missing.groovy').empty
    }

    def 'quick fixes are offered for the findings under a range'() {
        given:
        def text = Fixtures.text('lint-sample', MESSY)
        int semicolonLine = text.readLines().findIndexOf { it.contains('def untyped = 1;') }

        when:
        def actions = quality.codeActions(uri(MESSY), Range.of(semicolonLine, 0, semicolonLine, 1))

        then:
        actions*.title().contains('Remove unnecessary semicolon')
        def fix = actions.find { it.title() == 'Remove unnecessary semicolon' }
        fix.edits()[0].newText() == ''
        fix.edits()[0].range().start().line() == semicolonLine
        quality.codeActions('file:///nowhere/Missing.groovy', Range.of(0, 0, 0, 1)).empty
    }

    def 'an unbundled formatter engine is refused'() {
        given:
        def config = new EngineConfig(EngineConfig.DEFAULTS.sourceRoots(), [], EngineConfig.DEFAULTS.exclude(), false,
            EngineConfig.Lint.DEFAULTS, EngineConfig.TypeCheck.DEFAULTS, new EngineConfig.Format('greclipse', 4, 1, true))
        def other = new LanguageServices(new WorkspaceModel(Fixtures.project('lint-sample'), config), new GroovyCompiler())

        when:
        other.quality().format(uri(MESSY))

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('not bundled')
    }
}
