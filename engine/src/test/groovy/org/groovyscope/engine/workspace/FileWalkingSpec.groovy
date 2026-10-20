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
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.profiles.Descriptors
import org.groovyscope.engine.profiles.Profile
import spock.lang.Specification
import spock.lang.TempDir

/** Walking the workspace skips excluded trees, survives unreadable entries and oversized files. */
class FileWalkingSpec extends Specification {

    @TempDir Path root

    private Path write(String relative, String text) {
        Path file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    def 'excluded directories and .git are never entered'() {
        given:
        write('src/A.groovy', 'class A {}')
        write('node_modules/pkg/B.groovy', 'class B {}')
        write('.git/hooks/C.groovy', 'class C {}')
        write('sub/build/D.groovy', 'class D {}')
        write('build/E.groovy', 'class E {}')
        write('keep/build.groovy', 'class F {}')
        List<Path> seen = []

        when:
        def found = FileWalker.files(root, root, ['**/build/**', '**/node_modules/**'], [] as Set) { path, attributes ->
            seen << root.relativize(path)
            true
        }

        then:
        found.collect { root.relativize(it).toString() } == ['keep/build.groovy', 'src/A.groovy']
        seen*.toString().sort() == ['keep/build.groovy', 'src/A.groovy']
    }

    def 'an unreadable directory is reported and skipped instead of aborting the walk'() {
        given:
        write('src/main/groovy/A.groovy', 'class A {}')
        Path locked = Files.createDirectories(root.resolve('src/main/groovy/locked'))
        write('src/main/groovy/locked/B.groovy', 'class B {}')
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString('---------'))
        def model = new WorkspaceModel(root, EngineConfig.DEFAULTS)

        when:
        def files = model.discoverSourceFiles()*.fileName*.toString()
        def fingerprint = model.diskFingerprint()

        then:
        files.contains('A.groovy')
        fingerprint

        cleanup:
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString('rwx------'))
    }

    def 'a missing start directory yields nothing'() {
        expect:
        FileWalker.files(root.resolve('absent'), root, [], [] as Set) { p, a -> true } == []
        FileWalker.reportOnce('spec-key', 'reported once')
        !FileWalker.reportOnce('spec-key', 'reported once')
    }

    def 'files over workspace.maxFileBytes are skipped; 0 means no limit'() {
        given:
        write('src/main/groovy/Small.groovy', 'class Small {}')
        write('src/main/groovy/Big.groovy', 'class Big {}\n' + ('// filler\n' * 50))
        def limited = new WorkspaceModel(root, EngineConfig.fromJson('{"workspace": {"maxFileBytes": 100}}'))
        def unlimited = new WorkspaceModel(root, EngineConfig.fromJson('{"workspace": {"maxFileBytes": 0}}'))

        expect:
        EngineConfig.DEFAULTS.maxFileBytes() == 2_000_000L
        EngineConfig.fromJson('{"workspace": {"maxFileBytes": -5}}').maxFileBytes() == 0
        limited.discoverSourceFiles()*.fileName*.toString() == ['Small.groovy']
        limited.sources().keySet().every { !it.endsWith('Big.groovy') }
        unlimited.discoverSourceFiles()*.fileName*.toString() == ['Big.groovy', 'Small.groovy']
        EngineConfig.DEFAULTS.withMaxFileBytes(7).maxFileBytes() == 7
    }

    def 'a link to a file outside the root is not discovered; one inside is'() {
        given:
        write('src/main/groovy/A.groovy', 'class A {}')
        Path elsewhere = Files.createTempFile('outside', '.groovy')
        Files.createSymbolicLink(root.resolve('src/main/groovy/Out.groovy'), elsewhere)
        Files.createSymbolicLink(root.resolve('src/main/groovy/In.groovy'), root.resolve('src/main/groovy/A.groovy'))
        Files.createSymbolicLink(root.resolve('src/main/groovy/Dangling.groovy'), root.resolve('nothing'))

        expect:
        new WorkspaceModel(root, EngineConfig.DEFAULTS).discoverSourceFiles()*.fileName*.toString() == ['A.groovy', 'In.groovy']

        cleanup:
        Files.deleteIfExists(elsewhere)
    }

    def 'invalid globs are reported once and match nothing'() {
        given:
        write('src/main/groovy/A.groovy', 'class A {}')
        def config = EngineConfig.builder().exclude(['src/[', '**/build/**']).build()

        expect:
        new WorkspaceModel(root, config).discoverSourceFiles()*.fileName*.toString() == ['A.groovy']
        !Profile.matches('src/[', Paths.get('src/x'))
        !Profile.matches(null, Paths.get('x'))
        Profile.matches('**/*.groovy', Paths.get('A.groovy'))
        Globs.matcher('src/[').empty
        Globs.matcher('*.groovy').is(Globs.matcher('*.groovy'))
        Globs.excludesDirectory('**/build/**', Paths.get('build'))
        Globs.excludesDirectory('**/build/**', Paths.get('a/build'))
        !Globs.excludesDirectory('**/build/**', Paths.get('a/builder'))
        !Globs.excludesDirectory('**/*.groovy', Paths.get('a'))
        !Globs.excludesDirectory('**/build/**', Paths.get(''))
    }

    def 'an open document is reference counted across connections'() {
        given:
        def model = new WorkspaceModel(root, EngineConfig.DEFAULTS)
        String uri = WorkspaceModel.toUri(root.resolve('A.groovy'))

        when:
        model.open(uri, 'one')
        model.open(uri, 'two')
        model.close(uri)

        then:
        model.openText(uri) == 'two'

        when:
        model.change(uri, 'three')
        model.close(uri)

        then:
        !model.isOpen(uri)

        when: 'a change without an open still needs one close'
        model.change(uri, 'four')
        model.close(uri)
        model.close(uri)

        then:
        !model.isOpen(uri)
    }

    def 'the disk fingerprint follows the contract baseline and the profile descriptors and variable files'() {
        given:
        write('src/main/groovy/A.groovy', 'class A {}')
        Path descriptor = write('plugins/p/descriptor.json', '{"parameters": []}')
        Path variables = write('infra/vars.tf', 'variable "a" {\n}\n')
        write('infra/.terraform/modules/x.tf', 'variable "b" {\n}\n')
        def config = EngineConfig.fromJson('''{"profiles": [{"name": "p", "descriptor": {"file": "descriptor.json"},
                "infrastructure": {"variables": "**/*.tf"}}]}''')
        def model = new WorkspaceModel(root, config)
        def plain = new WorkspaceModel(root, EngineConfig.DEFAULTS)

        when:
        def before = model.diskFingerprint()
        def plainBefore = plain.diskFingerprint()

        then:
        model.profileInputs().collect { root.relativize(it).toString() } == ['infra/vars.tf', 'plugins/p/descriptor.json']
        plain.profileInputs().empty

        when:
        write('.groovyscope/contract-baseline.json', '{}')
        def withBaseline = model.diskFingerprint()

        then:
        withBaseline != before
        plain.diskFingerprint() != plainBefore

        when:
        Files.writeString(descriptor, '{"parameters": [{"name": "x"}]}')
        Files.setLastModifiedTime(descriptor, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        def afterDescriptor = model.diskFingerprint()

        then:
        afterDescriptor != withBaseline

        when:
        Files.writeString(variables, 'variable "a" {\n  default = 1\n}\n')
        Files.setLastModifiedTime(variables, FileTime.fromMillis(System.currentTimeMillis() + 10_000))

        then:
        model.diskFingerprint() != afterDescriptor
    }

    def 'descriptor and variable walks continue past a bad file'() {
        given:
        write('a/descriptor.json', '{ not json')
        write('b/descriptor.json', '{"parameters": [{"name": "region", "type": "string"}]}')
        write('build/c/descriptor.json', '{"parameters": [{"name": "ignored"}]}')
        Path broken = root.resolve('infra/bad.tf')
        Files.createDirectories(broken.parent)
        Files.write(broken, [0xC3, 0x28, 0x0A] as byte[])
        write('infra/good.tf', 'variable "region" {\n  type = string\n}\n')
        def profile = Profile.fromJson(com.google.gson.JsonParser.parseString(
                '{"name": "p", "descriptor": {"file": "descriptor.json"}, "infrastructure": {"variables": "**/*.tf"}}')
                .asJsonObject)

        when:
        def documents = Descriptors.all(profile.descriptor(), root)
        def variables = Descriptors.variables(profile.infrastructure(), root)
        def excluded = Descriptors.all(profile.descriptor(), root, ['b/**'])

        then:
        documents*.file*.toString() == [root.resolve('b/descriptor.json').toString()]
        variables*.name() == ['region']
        excluded.empty
    }
}
