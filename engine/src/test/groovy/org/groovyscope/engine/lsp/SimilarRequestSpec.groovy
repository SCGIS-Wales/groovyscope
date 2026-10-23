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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/** {@code groovyscope/similar}: existing code a candidate text, declaration or added lines could reuse. */
@Timeout(120)
class SimilarRequestSpec extends Specification {

    static final String PROJECT = 'edit-gate'
    static final String COPIED = 'src/main/groovy/demo/Copied.groovy'
    static final String STRINGS = 'src/main/groovy/demo/Strings.groovy'
    static final String SUBJECT = 'src/main/groovy/demo/Subject.groovy'

    @Shared GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ int code -> } as IntConsumer,
            Fixtures.project(PROJECT), EngineConfig.DEFAULTS)
    @Shared String root = Fixtures.project(PROJECT).toString()

    @TempDir Path temp

    Map similar(Map params) {
        server.similar([root: root] + params).get(60, TimeUnit.SECONDS) as Map
    }

    static String uri(String relative) {
        Fixtures.uri(PROJECT, relative)
    }

    /** Lines {@code from} to {@code to} (1-based, inclusive) of a fixture file. */
    static String lines(String relative, int from, int to) {
        Fixtures.text(PROJECT, relative).readLines().subList(from - 1, to).join('\n')
    }

    ResponseErrorException refusal(Map params) {
        try {
            similar(params)
        } catch (ExecutionException e) {
            return e.cause as ResponseErrorException
        }
        throw new AssertionError('the request was not refused')
    }

    def 'the copied method as text finds the original as an exact copy with a score'() {
        when:
        Map result = similar(text: lines(COPIED, 5, 10))
        Map slug = result.matches.find { it.symbol == 'demo.Strings.slug' }

        then:
        slug.kind == 'exact'
        slug.score == 1.0d
        slug.uri == uri(STRINGS)
        slug.line == 6
        slug.endLine == 9
        slug.reason.startsWith('the same code: 4 of the candidate\'s 4 statements, the whole of slug')
        result.matches.first().score >= result.matches.last().score
        result.elapsedMs instanceof Number
    }

    def 'renamed variables and literals make a similar match, ranked under an exact one'() {
        given:
        String renamed = '''\
            String label(String text) {
                String small = text.toLowerCase()
                String joined = small.replaceAll(/[^a-z]+/, '_')
                String clean = joined.replaceAll(/^_+|_+$/, '')
                return clean.length() > 20 ? clean.substring(0, 20) : clean
            }'''.stripIndent()

        when:
        Map slug = similar(text: renamed, minLines: 3).matches.find { it.symbol == 'demo.Strings.slug' }

        then:
        slug.kind == 'similar'
        slug.score == 0.9d
        slug.reason.contains('other names or literals')
    }

    def 'bare statements parse wrapped in a method; under minLines they get only signature and calls matches'() {
        when:
        Map brief = similar(text: lines(COPIED, 6, 9))
        Map shapes = similar(text: lines(COPIED, 6, 9), minLines: 4)

        then:
        brief.matches*.kind.every { it in ['signature', 'calls'] }
        brief.matches.find { it.symbol == 'demo.Strings.slug' }.kind == 'calls'
        shapes.matches.find { it.symbol == 'demo.Strings.slug' }.kind == 'exact'
    }

    def 'a symbol is resolved as edit resolves it and never matches itself'() {
        when:
        Map result = similar(uri: uri(COPIED), symbol: 'Copied.pageName')

        then:
        result.symbol == 'Copied.pageName'
        result.matches*.symbol.contains('demo.Strings.slug')
        !result.matches*.symbol.contains('demo.Copied.pageName')
        result.matches.find { it.symbol == 'demo.Strings.slug' }.kind == 'exact'
    }

    def 'the text itself is found as well when no uri says where it lives'() {
        expect:
        similar(text: lines(COPIED, 5, 10)).matches*.symbol.containsAll(['demo.Strings.slug', 'demo.Copied.pageName'])
    }

    def 'paths narrow the search to the documents under them'() {
        when:
        Map narrowed = similar(text: lines(COPIED, 5, 10), paths: [SUBJECT])
        Map folder = similar(text: lines(COPIED, 5, 10), paths: ['src/main/groovy/demo'])
        Map outside = similar(text: lines(COPIED, 5, 10), paths: ['../elsewhere'])

        then:
        narrowed.matches == []
        folder.matches*.symbol.contains('demo.Strings.slug')
        outside.matches == []
    }

    def 'limit caps the matches, the strongest kept'() {
        when:
        Map all = similar(text: lines(COPIED, 5, 10))
        Map one = similar(text: lines(COPIED, 5, 10), limit: 1)

        then:
        all.matches.size() >= 2
        one.matches.size() == 1
        one.matches[0].score == all.matches[0].score
    }

    def 'a method with the same name stem and parameter types in the package is a signature match'() {
        given:
        String text = 'String buildSlug(String title) {\n    title.trim()\n}'

        when:
        Map result = similar(text: text, uri: uri(COPIED), line: 5)
        Map elsewhere = similar(text: 'String buildSlug(int count) {\n    count.toString()\n}', uri: uri(COPIED))

        then:
        with(result.matches.find { it.symbol == 'demo.Strings.slug' }) {
            kind == 'signature'
            score == 0.6d
            reason == "the same name stem 'slug' and parameters (String)"
            line == 5
            endLine == 10
        }
        elsewhere.matches.every { it.kind != 'signature' }
    }

    def 'the same name and parameters scores above a shared stem, and a package scopes the text'() {
        given:
        String text = 'package demo\n\nclass Other {\n    static String slug(String text) {\n        text\n    }\n}\n'

        when:
        Map slug = similar(text: text).matches.find { it.symbol == 'demo.Strings.slug' }
        Map elsewhere = similar(text: text.replace('package demo', 'package other'))

        then:
        slug.kind == 'signature'
        slug.score == 0.7d
        slug.reason == 'the same name and parameters (String)'
        elsewhere.matches.every { it.kind != 'signature' }
    }

    def 'a method that makes the same calls in another shape is a calls match'() {
        given:
        String text = '''\
            String tidy(String name) {
                if (name == null) {
                    return ''
                }
                return name.toLowerCase().replaceAll(/\\s+/, '-').substring(0, 3)
            }'''.stripIndent()

        when:
        Map slug = similar(text: text).matches.find { it.symbol == 'demo.Strings.slug' }

        then:
        slug.kind == 'calls'
        slug.score > 0.4d
        slug.reason.startsWith('makes 3 of the same 3 calls: ')
        slug.reason.contains('toLowerCase')
    }

    def 'a unique method finds nothing above the thresholds'() {
        expect:
        similar(uri: uri(SUBJECT), symbol: 'square').matches == []
        similar(text: 'int triple(int value) {\n    int twice = value + value\n    return twice + value\n}').matches == []
    }

    def 'ranges answer per range: an added copy finds the original, other lines find nothing'() {
        when:
        Map result = similar(uri: uri(COPIED), ranges: [[5, 10], [1, 3]])

        then:
        result.byRange*.range == [[5, 10], [1, 3]]
        with(result.byRange[0].matches.find { it.symbol == 'demo.Strings.slug' }) {
            kind == 'exact'
            line == 6
            endLine == 9
        }
        !result.byRange[0].matches*.symbol.contains('demo.Copied.pageName')
        result.byRange[1].matches == []
        result.elapsedMs instanceof Number
    }

    def 'requests that name no form, two forms or a bad form are refused'() {
        expect:
        refusal(params).responseError.code == ResponseErrorCode.InvalidParams.value
        refusal(params).message.contains(message)

        where:
        params                                                    || message
        [:]                                                       || 'exactly one of'
        [text: 'x', symbol: 'y', uri: uri(COPIED)]                || 'exactly one of'
        [text: '   ']                                             || 'exactly one of'
        [symbol: 'pageName']                                      || 'need the uri'
        [uri: uri(COPIED), symbol: 'nothing']                     || 'not found'
        [uri: uri(COPIED), ranges: [[3, 1]]]                      || 'ranges must be'
        [uri: uri(COPIED), ranges: 'x']                           || 'ranges must be'
        [text: 'def x( {']                                        || 'does not parse'
        [uri: 'file:///elsewhere/X.groovy', symbol: 'x']          || 'outside the workspace'
    }

    /** Writes {@code files} into a temporary workspace and asks it {@code params}; a refusal comes back as its message. */
    Object similarIn(Map<String, String> files, Map params) {
        files.each { String relative, String text ->
            Path file = temp.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        def local = new GroovyscopeLanguageServer({ int code -> } as IntConsumer, temp, EngineConfig.DEFAULTS)
        Map named = params.collectEntries { key, value ->
            [key, key == 'uri' ? temp.resolve(value as String).toUri().toString() : value]
        }
        try {
            return local.similar([root: temp.toString()] + named).get(60, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            return e.cause.message
        }
    }

    def 'a field, a Java source and a document that does not parse are not candidates'() {
        expect:
        similarIn(['src/main/groovy/demo/Holder.groovy': 'package demo\n\nclass Holder {\n    int size\n}\n'],
                [uri: 'src/main/groovy/demo/Holder.groovy', symbol: 'Holder.size']).contains('is a field')
        similarIn(['src/main/java/demo/Plain.java': 'package demo;\n\nclass Plain {\n    int one() { return 1; }\n}\n'],
                [uri: 'src/main/java/demo/Plain.java', symbol: 'one']).contains('is a Java source')
        similarIn(['src/main/groovy/demo/Broken.groovy': 'package demo\n\nclass Broken {\n    void go( {\n}\n'],
                [uri: 'src/main/groovy/demo/Broken.groovy', ranges: [[1, 3]]]).contains('does not parse')
        similarIn([:], [uri: 'src/main/groovy/demo/Missing.groovy', symbol: 'x']).contains('cannot be read')
    }

    def 'a text meant for a class is scoped by that class: its package and its superclasses'() {
        given:
        Map files = [
                'src/main/groovy/base/Base.groovy': 'package base\n\nclass Base {\n    String makeKey(String text) {\n        text.trim()\n    }\n}\n',
                'src/main/groovy/other/Child.groovy': 'package other\n\nimport base.Base\n\nclass Child extends Base {\n    int size() {\n        1\n    }\n}\n']

        when:
        def result = similarIn(files, [text: 'String buildKey(String text) {\n    text\n}',
                                       uri: 'src/main/groovy/other/Child.groovy', line: 6])

        then:
        result.matches*.symbol == ['base.Base.makeKey']
        result.matches*.kind == ['signature']
    }

    def 'the class a line lies in is the context, else the first class that is not a script'() {
        given:
        def module = new org.groovyscope.engine.compile.GroovyCompiler().parse('Mixed.groovy',
                'println 1\nclass One {\n  int a() { 1 }\n}\nclass Two {\n  int b() { 2 }\n}\n').module().get()

        expect:
        ReuseRequests.contextClass(module, 6).nameWithoutPackage == 'Two'
        ReuseRequests.contextClass(module, null).nameWithoutPackage == 'One'
    }

    def 'a class as the symbol searches with all its methods'() {
        expect:
        similar(uri: uri(COPIED), symbol: 'Copied').matches*.symbol.contains('demo.Strings.slug')
    }

    def 'explainCode lists the helpers a method and a class could call'() {
        when:
        Map method = server.explainCode([root: root, symbol: 'demo.Copied.pageName']).get(60, TimeUnit.SECONDS) as Map
        Map type = server.explainCode([root: root, symbol: 'demo.Verbose']).get(60, TimeUnit.SECONDS) as Map
        Map file = server.explainCode([root: root, uri: uri(SUBJECT)]).get(60, TimeUnit.SECONDS) as Map
        List methodHelpers = method.items[0].helpers
        List verboseHelpers = type.items[0].helpers
        Map slug = methodHelpers.find { it.symbol == 'demo.Strings.slug' }

        then:
        slug == [symbol: 'demo.Strings.slug', signature: 'static String slug(String title)', line: 4,
                 uri: uri(STRINGS)]
        !methodHelpers*.symbol.contains('demo.Copied.pageName')
        !methodHelpers*.symbol.contains('demo.Subject.square')
        verboseHelpers*.symbol.contains('demo.Verbose.current')
        !verboseHelpers*.symbol.contains('demo.Verbose.countWords')
        file.items[0].helpers*.symbol.contains('demo.Subject.square')
        file.items[0].helpers*.symbol.contains('demo.Strings.slug')
    }
}
