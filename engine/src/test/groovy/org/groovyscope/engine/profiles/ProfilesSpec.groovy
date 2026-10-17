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
package org.groovyscope.engine.profiles

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

class ProfilesSpec extends Specification {

    static final String DEMO = 'plugins/demo/src/main/groovy/demo/DemoPlugin.groovy'

    @Shared LanguageServices services = services(Fixtures.project('profile-sample'), profileJson())

    @TempDir Path temp

    static String profileJson(Map overrides = [:]) {
        Map profile = new Gson().fromJson(ProfilesSpec.getResource('/profiles/demo-runtime.json').text, Map)
        profile.putAll(overrides)
        new Gson().toJson(profile)
    }

    static LanguageServices services(Path root, String profile) {
        EngineConfig config = EngineConfig.fromJson('{"profiles": [' + profile + ']}')
        new LanguageServices(new WorkspaceModel(root, config), new GroovyCompiler())
    }

    String uri(String relative) {
        Fixtures.uri('profile-sample', relative)
    }

    Map<Integer, List<String>> codesByLine(String relative) {
        services.quality().practices(uri(relative)).groupBy { it.diagnostic().range().start().line() + 1 }
                .collectEntries { line, found -> [line, found.collect { it.diagnostic().code() }.sort()] }
    }

    def 'a profile governs files by name, SDK import or marker, and nothing else'() {
        expect:
        services.quality().governing(uri(DEMO)).get().name() == 'demo-runtime'
        services.quality().governing(uri('plugins/demo/src/test/groovy/demo/DemoPluginTest.groovy')).empty
        services.quality().governing(uri('plugins/other/src/Util.groovy')).empty
        services.quality().practices(uri('plugins/other/src/Util.groovy')).empty
    }

    def 'runtime findings on a governed plugin'() {
        when:
        def byLine = codesByLine(DEMO)

        then:
        byLine[4] == ['RP001']           // blocked import
        byLine[8] == ['RP030']           // SDK hint for a class the library replaces
        byLine[17] == ['RP012']          // fallback on a declared parameter
        byLine[18] == ['RP011']          // declared boolean, read as String
        byLine[19] == ['RP010']          // not declared
        byLine[20] == ['RP013']          // raw map read
        byLine[21] == ['RP001']          // unproven factory call
        byLine[24] == ['RP022']          // secret into a log
        byLine[25] == ['RP022']          // secret into outputs
        byLine[26] == ['RP032']          // network call outside the allowed method
        byLine[27] == ['RP002']          // construct newer than the target Groovy
        byLine[36] == ['RP001']          // arrow switch reported once per block
        byLine[45] == ['RP001']          // nested class proven only elsewhere
        !byLine.values().flatten().contains('GS010')
    }

    def 'messages name the target, the service that compiles the file and the evidence'() {
        when:
        def messages = services.quality().practices(uri(DEMO)).collect { it.diagnostic().message() }

        then:
        messages.any { it.contains('groovy-json is blocked on demo-runtime (demo-service)') && it.contains('Jackson ObjectMapper') }
        messages.any { it.startsWith('Groovy 5.1 on demo-runtime: val is Groovy 6 only') }
        messages.any { it.contains("'NOT_DECLARED' is not declared in descriptor.json") }
    }

    def 'descriptor and infrastructure artefacts are checked on a whole-workspace check'() {
        when:
        def found = services.quality().check([], null, ['practices'] as Set).diagnostics()
                .findAll { k, v -> !k.endsWith('.groovy') }
                .collectEntries { k, v -> [k.substring(k.lastIndexOf('/') + 1), v.collect { [it.range().start().line() + 1, it.code()] }] }

        then:
        found['descriptor.json'] == [[3, 'RP004']]
        found['variables.tf'] as Set == [[4, 'RP015'], [5, 'RP014']] as Set
    }

    def 'artefacts are not reported when only some documents are checked'() {
        expect:
        services.quality().check([uri(DEMO)], null, ['practices'] as Set).diagnostics().keySet() == [uri(DEMO)] as Set
    }

    def 'the profile can switch CodeNarc rules off for governed files only'() {
        expect:
        !services.quality().lint(uri(DEMO), Fixtures.text('profile-sample', DEMO), null).any { it.code() == 'CompileStatic' }
        services.quality().lint(uri('plugins/other/src/Util.groovy'), Fixtures.text('profile-sample', 'plugins/other/src/Util.groovy'), null)
                .any { it.code() == 'CompileStatic' }
    }

    def 'fixes rewrite parameter reads and never touch a protected method'() {
        when:
        def result = services.quality().fix([uri(DEMO)], [] as Set)
        def edits = result.edits()[uri(DEMO)]

        then:
        result.applied()[uri(DEMO)] as Set == ['RP012', 'RP013'] as Set
        edits*.newText().contains("getResourceParameter('TOPIC_NAME', String.class)")
        edits.every { it.range().start().line() < 33 }

        and: 'the semicolon in the protected method is linted but has no code action'
        services.quality().lint(uri(DEMO), Fixtures.text('profile-sample', DEMO), null).any { it.code() == 'UnnecessarySemicolon' }
        services.quality().codeActions(uri(DEMO), Range.of(37, 0, 37, 20)).empty
    }

    def 'contract items cover parameters, outputs, frozen methods and test seams'() {
        when:
        def items = services.quality().contracts()[DEMO]

        then:
        items == ['class:demo.DemoPlugin'      : 'class',
                  'method:preProcessing'       : 'preProcessing():void',
                  'output:secret'              : 'output',
                  'parameter:ENABLED'          : 'boolean',
                  'parameter:RETENTION_DAYS'   : 'string',
                  'parameter:TOPIC_NAME'       : 'string',
                  'seam:seam'                  : 'public']
        services.quality().contracts().keySet() == [DEMO] as Set
    }

    def 'drift against a recorded baseline is reported, additions are not'() {
        given:
        Path root = copyFixture()
        Files.createDirectories(root.resolve('.groovyscope'))
        root.resolve(Contracts.BASELINE).text = new Gson().toJson([files: [(DEMO): [
                'parameter:TOPIC_NAME': 'list',
                'parameter:ENABLED'   : 'boolean',
                'method:postProcessing': 'postProcessing(int):void',
                'seam:seam'           : 'package-private']]])
        def copy = services(root, profileJson())

        when:
        def drift = copy.quality().practices(root.resolve(DEMO).toUri().toString())
                .collect { it.diagnostic() }.findAll { it.code() in ['RP020', 'RP021'] }

        then:
        drift*.code().sort() == ['RP020', 'RP020', 'RP021']
        drift.any { it.message() == 'recorded contract item changed: parameter:TOPIC_NAME was list, now string' }
        drift.any { it.message() == 'recorded contract item is gone: method:postProcessing' }
        drift.any { it.message() == 'test seam seam was package-private, now public' }
        copy.quality().profileStatus(root.resolve(DEMO).toUri().toString()).baselineRecorded == true
    }

    def 'a malformed or missing baseline means no drift'() {
        given:
        Path root = copyFixture()
        Files.createDirectories(root.resolve('.groovyscope'))
        root.resolve(Contracts.BASELINE).text = text

        expect:
        Contracts.baseline(root, DEMO) == null

        where:
        text << ['not json', '{"files": {}}', '[]']
    }

    def 'parameter navigation jumps from an accessor to the descriptor and the tf variable'() {
        given:
        def at = Fixtures.pos('profile-sample', DEMO, 'TOPIC_NAME', 1, 2)

        when:
        def declarations = services.quality().parameterDeclarations(uri(DEMO), at)
        def references = services.quality().parameterReferences(uri(DEMO), at, false)
        def hover = services.quality().parameterHover(uri(DEMO), at)

        then:
        declarations.collect { [it.uri().substring(it.uri().lastIndexOf('/') + 1), it.range().start().line()] } as Set ==
                [['descriptor.json', 4], ['variables.tf', 0]] as Set
        references.every { it.uri() == uri(DEMO) }
        references*.range()*.start()*.line().contains(15)
        hover.present
        hover.get().contains('TOPIC_NAME')
        hover.get().contains('string')
    }

    def 'navigation stays out of the way away from parameters and in ungoverned files'() {
        expect:
        services.quality().parameterDeclarations(uri(DEMO), Fixtures.pos('profile-sample', DEMO, 'CLIENT')).empty
        services.quality().parameterHover(uri(DEMO), Fixtures.pos('profile-sample', DEMO, 'CLIENT')).empty
        services.quality().parameterReferences(uri('plugins/other/src/Util.groovy'),
                Fixtures.pos('profile-sample', 'plugins/other/src/Util.groovy', 'names'), true).empty
    }

    def 'profile status summarises what governs a document'() {
        when:
        def status = services.quality().profileStatus(uri(DEMO))

        then:
        status.governed == true
        status.profile == 'demo-runtime'
        status.targetGroovy == '5.1'
        status.compiledBy == 'demo-service'
        status.descriptor == 'plugins/demo/descriptor.json'
        status.parameters == [TOPIC_NAME: 'string', RETENTION_DAYS: 'string', ENABLED: 'boolean']
        status.seams == ['seam']
        status.protectedMethods == ['advance']
        status.baselineRecorded == false
        services.quality().profileStatus(uri('plugins/other/src/Util.groovy')) == [governed: false]
    }

    def 'Groovy 6 checkers are advisory on governed files and can be switched off'() {
        given:
        def off = services(Fixtures.project('profile-sample'),
                profileJson(target: [groovy: '5.1', groovy6Checkers: 'off', compiledBy: []]))

        when:
        def advisory = services.quality().check([uri(DEMO)], null, ['typecheck'] as Set).diagnostics()[uri(DEMO)]
        def none = off.quality().check([uri(DEMO)], null, ['typecheck'] as Set).diagnostics()[uri(DEMO)]

        then:
        advisory.every { it.severity() != org.groovyscope.engine.model.Diagnostic.Severity.ERROR }
        none.empty
    }

    def 'the emulation compile reports what the runtime Groovy reports'() {
        given:
        String groovyJar = Path.of(GroovySystem.protectionDomain.codeSource.location.toURI()).toString()
        def emulated = services(Fixtures.project('profile-sample'),
                profileJson(target: [groovy: '5.1', groovy6Checkers: 'advisory', compiledBy: [], emulationJars: [groovyJar]]))

        when:
        def found = emulated.quality().check([], null, ['practices'] as Set).diagnostics()[uri(DEMO)]
                .findAll { it.code() == 'RP050' }

        then:
        !found.empty
        found.every { it.message().startsWith('does not compile on demo-runtime (Groovy 5.1): ') }
        found.any { it.message().contains('com.example.sdk.PluginBase') }
    }

    def 'no emulation jars, or missing ones, means no emulation compile'() {
        given:
        def missing = services(Fixtures.project('profile-sample'),
                profileJson(target: [groovy: '5.1', emulationJars: ['/does/not/exist.jar']]))

        expect:
        !services.quality().check([], null, ['practices'] as Set).diagnostics().values().flatten().any { it.code() == 'RP050' }
        !missing.quality().check([], null, ['practices'] as Set).diagnostics().values().flatten().any { it.code() == 'RP050' }
    }

    def 'a profile parses from JSON with defaults for every missing section'() {
        when:
        def config = EngineConfig.fromJson('{"profiles": [{"name": "bare"}]}')
        def bare = config.profiles()[0]

        then:
        bare.name() == 'bare'
        bare.constructs().empty
        bare.descriptor() == null
        bare.target().emulationJars().empty
        bare.compiledBy('anything.groovy') == null
        bare.target().groovy6Checkers() == 'on'
    }

    private Path copyFixture() {
        Path source = Fixtures.project('profile-sample')
        Path root = temp.resolve('ws')
        Files.walk(source).forEach { Path from ->
            Path to = root.resolve(source.relativize(from).toString())
            if (Files.isDirectory(from)) {
                Files.createDirectories(to)
            } else {
                Files.copy(from, to)
            }
        }
        root
    }
}
