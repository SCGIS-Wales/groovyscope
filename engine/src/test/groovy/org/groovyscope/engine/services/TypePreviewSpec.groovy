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

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.profiles.Profile
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.typecheck.TypePreview
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** GS077, the type preview: Groovy's static type checker run speculatively on dynamic classes. */
class TypePreviewSpec extends Specification {

    static final String TYPES = 'src/main/groovy/demo/Types.groovy'

    @Shared LanguageServices services = Fixtures.services('edit-gate')
    @Shared GroovyCompiler compiler = new GroovyCompiler()

    @TempDir Path temp

    private static EngineConfig withTypes(EngineConfig.Types types, Map<String, String> severities = [:]) {
        EngineConfig.DEFAULTS.toBuilder().practices(new EngineConfig.Practices(true, severities,
                EngineConfig.Naming.DEFAULTS, [], EngineConfig.Comments.DEFAULTS, EngineConfig.Reuse.DEFAULTS, EngineConfig.Tests.DEFAULTS, types,
                EngineConfig.Design.DEFAULTS)).build()
    }

    /** The type preview of {@code text} as {@code relative} in a fresh workspace under {@code temp}. */
    private Map<String, List<Diagnostic>> preview(String text, String relative = 'src/main/groovy/app/Job.groovy',
            EngineConfig config = EngineConfig.DEFAULTS, boolean declared = false) {
        Path file = temp.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        String uri = file.toUri().toString()
        def fresh = new LanguageServices(new WorkspaceModel(temp, config), new GroovyCompiler())
        fresh.quality().typePreview([(uri): text], compiler.compile([(uri): text], [], false).modules(), declared)
    }

    private static List<String> shown(Map<String, List<Diagnostic>> found) {
        found.values().flatten().collect { Diagnostic d -> "${d.code()}:${d.range().start().line() + 1}".toString() }
    }

    def "the edit gate fixture's type cases come out in the checker's own words"() {
        given:
        String uri = Fixtures.uri('edit-gate', TYPES)
        String text = Fixtures.text('edit-gate', TYPES)

        when:
        List<Diagnostic> found = services.quality().typePreview([(uri): text], services.snapshot().result().modules(),
                false)[uri]

        then:
        found*.code() == ['GS077', 'GS077']
        found*.range()*.start()*.line() == [5, 17]
        found[0].message() == 'would not type check: Cannot call java.lang.String#compareTo(java.lang.String) with arguments [int]'
        found[1].message() == 'would not type check: No such property: length for class: java.util.List'
        found.every { it.confidence() == Confidence.INFERRED && it.severity() == Diagnostic.Severity.WARNING }
        found.every { it.source() == 'groovyscope' }
    }

    def 'check of named files runs the preview with the practices, a whole workspace check only when types is named'() {
        given:
        String uri = Fixtures.uri('edit-gate', TYPES)

        expect:
        services.quality().check([uri], null, ['types'] as Set).diagnostics()[uri]*.code() == ['GS077', 'GS077']
        services.quality().check([uri], null, ['practices'] as Set).diagnostics()[uri]*.code().count('GS077') == 2
        !services.quality().check([uri], null, ['lint', 'compile'] as Set).diagnostics()[uri]*.code().contains('GS077')
        services.quality().check([], null, ['types'] as Set).diagnostics()[uri]*.code() == ['GS077', 'GS077']
        !services.quality().check([], null, ['practices'] as Set).diagnostics()[uri]*.code().contains('GS077')
    }

    def 'a dynamic class whose values are unknown, and calls a closure delegate answers, report nothing'() {
        expect:
        shown(preview('''\
package app

class Job {
    def handle(request) {
        def body = request.body
        body.items.each { item -> item.price = 1 }
        request.respond(body)
    }
    void render(Closure layout) {
        layout.delegate = this
        layout { html { h1 'x' } }
    }
}
''')) == []
    }

    def 'classes relying on builders, methodMissing, metaClass, Expando or a category, and typed ones, are skipped'() {
        expect:
        shown(preview(text)) == []

        where:
        text << [
            'package app\nclass Job {\n    int n() {\n        def xml = new groovy.xml.MarkupBuilder()\n        return "x"\n    }\n}\n',
            'package app\nclass Job {\n    def methodMissing(String name, args) { null }\n    int n() { return "x" }\n}\n',
            'package app\nclass Job {\n    int n() {\n        String.metaClass.shout = { -> 1 }\n        return "x"\n    }\n}\n',
            'package app\nclass Job {\n    int n() {\n        def e = new Expando()\n        return "x"\n    }\n}\n',
            'package app\n@groovy.transform.CompileDynamic\nclass Job {\n    int n() { return "x" }\n}\n',
            'package app\nclass JobSpec extends spock.lang.Specification {\n    int n() { return "x" }\n}\n',
            'package app\ntrait Named {\n    int n() { return "x" }\n}\n',
        ]
    }

    def 'an error of a class already static is a type error, reported only when asked for'() {
        given:
        String text = 'package app\n@groovy.transform.CompileStatic\nclass Job {\n    int n() {\n        return "x"\n    }\n}\n'

        when:
        def asked = preview(text, 'src/main/groovy/app/Job.groovy', EngineConfig.DEFAULTS, true).values().flatten()
        def silent = preview(text, 'src/main/groovy/app/Job.groovy', EngineConfig.DEFAULTS, false).values().flatten()

        then:
        asked*.code() == ['typecheck']
        asked*.severity() == [Diagnostic.Severity.ERROR]
        asked[0].message().contains('Cannot return value of type java.lang.String')
        silent == []
    }

    def 'types.preview off, GS077 off, the per file limit and Jenkins code are honoured'() {
        given:
        String text = 'package app\nclass Job {\n    int a() { return "x" }\n    int b() { return "y" }\n    int c() { return "z" }\n}\n'

        expect:
        shown(preview(text)) == ['GS077:3', 'GS077:4', 'GS077:5']
        shown(preview(text, 'src/main/groovy/app/Job.groovy', withTypes(new EngineConfig.Types(true, 2, true)))) ==
                ['GS077:3', 'GS077:4']
        shown(preview(text, 'src/main/groovy/app/Job.groovy', withTypes(new EngineConfig.Types(false, 10, true)))) == []
        shown(preview(text, 'src/main/groovy/app/Job.groovy', withTypes(EngineConfig.Types.DEFAULTS, [GS077: 'off']))) == []
        shown(preview(text, 'vars/job.groovy')) == []
    }

    def 'a file a runtime profile governs is skipped when the profile blocks static compilation'() {
        given:
        Map profile = new Gson().fromJson(TypePreviewSpec.getResource('/profiles/demo-runtime.json').text, Map)
        profile.constructs = [[id: 'static', classes: ['groovy.transform.CompileStatic'], status: 'blocked']]
        EngineConfig config = EngineConfig.fromJson('{"profiles": [' + new Gson().toJson(profile) + ']}')
        Files.createDirectories(temp.resolve('plugins/demo'))
        Files.writeString(temp.resolve('plugins/demo/descriptor.json'), '{}')
        String text = 'package app\nclass DemoPlugin {\n    int a() { return "x" }\n}\n'

        expect:
        Profile.fromJson(new Gson().toJsonTree(profile).asJsonObject).blocksStaticCompilation()
        !Profile.fromJson(new Gson().toJsonTree(profile + [constructs: []]).asJsonObject).blocksStaticCompilation()
        shown(preview(text, 'plugins/demo/src/DemoPlugin.groovy', config)) == []
        shown(preview(text, 'plugins/demo/src/DemoPlugin.groovy')) == ['GS077:3']
    }

    def 'a text that does not resolve is not checked, and the candidates of no module are none'() {
        expect:
        shown(preview('package app\nclass Job {\n    Missing m() { return "x" }\n}\n')) == []
        TypePreview.candidates(null).isEmpty()
    }

    def 'the texts that do not resolve are dropped and the rest checked again'() {
        when:
        def outcome = TypePreview.check([
                'file:///w/A.groovy': 'class A {\n    Missing m() { return null }\n}\n',
                'file:///w/B.groovy': 'class B {\n    int n() { return "x" }\n}\n'], [], false, [:], ['A', 'B'] as Set, null)

        then:
        outcome.checked()
        !outcome.errors().containsKey('file:///w/A.groovy')
        outcome.errors()['file:///w/B.groovy']*.speculative() == [true]
    }

    def 'untyped declarations get the type the checker infers, or none when it cannot tell'() {
        given:
        String uri = Fixtures.uri('edit-gate', 'src/main/groovy/demo/Fresh.groovy')
        String text = '''\
package demo

class Fresh {
    def items = new ArrayList<String>()
    def raw

    Fresh(name = 'n') {
    }

    def nothing() {
    }

    def none() {
        return null
    }

    def echo(value) {
        value
    }

    def twice(int a) {
        if (a) {
            return a * 2
        }
        return 'none'
    }
}
'''

        when:
        def found = services.quality().untyped(uri, text, 1, 30)

        then:
        found.collect { [it.line(), it.what(), it.inferred()] } == [
                [4, 'field items', 'ArrayList<String>'], [5, 'field raw', null], [7, 'parameter name', 'String'],
                [10, 'return type of nothing', 'void'], [13, 'return type of none', null],
                [17, 'return type of echo', null], [17, 'parameter value', null], [21, 'return type of twice', null]]
        services.quality().untyped(uri, text, 30, 40) == []
        services.quality().untyped(uri, 'not groovy {', 1, 1) == []
    }
}
