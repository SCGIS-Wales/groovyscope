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
package org.groovyscope.engine.search

import java.nio.file.Path
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.builder.AstStringCompiler
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.CompilePhase
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.services.Snapshot
import spock.lang.Specification
import spock.lang.TempDir

/** The parts of the similar code search: loose hashes, name stems, calls, overrides, supertypes and the cache. */
class ReuseFinderSpec extends Specification {

    static final String SOURCE = '''
        class A {
            static int helper(int x) { x }
            int go(String title, List<String> xs) {
                String lower = title.toLowerCase()
                int m = Math.max(1, 2)
                int h = helper(3)
                Integer v = Integer.valueOf(3)
                def f = new File('x')
                if (lower instanceof String) { return 1 }
                return lower.length() > 40 ? m : h
            }
        }
    '''

    @TempDir Path root

    static MethodNode method(CompilePhase phase, String source, String name) {
        def nodes = new AstStringCompiler().compile(source, phase, false)
        ClassNode type = nodes.find { it instanceof ClassNode && !it.script } as ClassNode
        type.getMethods(name)[0]
    }

    static Optional<ModuleNode> parse(String text) {
        def parsed = new GroovyCompiler().parse('Candidate.groovy', text)
        parsed.ok() ? parsed.module() : Optional.empty()
    }

    def 'a parsed tree and a resolved one hash alike when loose, and apart when strict'() {
        given:
        List parsed = (method(CompilePhase.CONVERSION, SOURCE, 'go').code as BlockStatement).statements
        List resolved = (method(CompilePhase.CANONICALIZATION, SOURCE, 'go').code as BlockStatement).statements

        expect:
        parsed.collect { CloneFinder.looseHash(it) } == resolved.collect { CloneFinder.looseHash(it) }
        parsed.collect { CloneFinder.hash(it) } != resolved.collect { CloneFinder.hash(it) }
    }

    def 'a name stem drops a leading verb and keeps the rest of the words'() {
        expect:
        ReuseFinder.stem(name) == stem

        where:
        name                || stem
        'buildSlug'         || 'slug'
        'slug'              || 'slug'
        'getReportFile'     || 'reportfile'
        'parse_report_file' || 'reportfile'
        'pageName'          || 'pagename'
        'get'               || 'get'
        'toJson'            || 'json'
    }

    def 'calls are keyed by the receiver type when the tree tells it, by name alone otherwise'() {
        given:
        def collector = new ReuseFinder.CallCollector(0, Integer.MAX_VALUE)
        MethodNode go = parsedOnly ? (parse(SOURCE).get().classes.find { !it.script } as ClassNode).getMethods('go')[0]
                : method(CompilePhase.CANONICALIZATION, SOURCE, 'go')
        go.code.visit(collector)

        expect:
        collector.keys()*.toString() as Set == expected as Set

        where:
        parsedOnly || expected
        true       || ['String.toLowerCase', 'Math.max', 'helper', 'Integer.valueOf', 'new File', 'String.length']
        false      || ['String.toLowerCase', 'Math.max', 'A.helper', 'Integer.valueOf', 'new File', 'String.length']
    }

    def 'a call with a known receiver type and one without are the same call'() {
        expect:
        new ReuseFinder.CallKey('String', 'trim').compatible(new ReuseFinder.CallKey(null, 'trim'))
        new ReuseFinder.CallKey(null, 'trim').compatible(new ReuseFinder.CallKey('String', 'trim'))
        !new ReuseFinder.CallKey('String', 'trim').compatible(new ReuseFinder.CallKey('Text', 'trim'))
        !new ReuseFinder.CallKey(null, 'trim').compatible(new ReuseFinder.CallKey(null, 'strip'))
    }

    def 'parameter types are simple names without generics, def when untyped, arrays marked'() {
        given:
        MethodNode parsed = method(CompilePhase.CONVERSION, 'class B { void m(List<String> a, b, java.io.File[] c, int d) {} }', 'm')
        MethodNode resolved = method(CompilePhase.CANONICALIZATION, 'class B { void m(List<String> a, b, java.io.File[] c, int d) {} }', 'm')

        expect:
        ReuseFinder.parameterTypes(parsed.parameters) == ['List', 'def', 'File[]', 'int']
        ReuseFinder.parameterTypes(resolved.parameters) == ['List', 'def', 'File[]', 'int']
        ReuseFinder.simple(null) == 'def'
    }

    def 'an override is known by its annotation or by a supertype declaring the method'() {
        given:
        String source = '''
            abstract class Base { abstract String name(); String label() { 'x' } }
            class Child extends Base {
                String name() { 'child' }
                @Override String toString() { 'c' }
                String own() { 'own' }
            }
            class Runner implements Runnable { void run() {} }
        '''

        expect:
        ReuseFinder.overrides(method(CompilePhase.CANONICALIZATION, source, 'name')) == false // Base's own, abstract
        ReuseFinder.overrides(childMethod(source, 'name'))
        ReuseFinder.overrides(childMethod(source, 'toString'))
        !ReuseFinder.overrides(childMethod(source, 'own'))
        ReuseFinder.overrides(classMethod(source, 'Runner', 'run'))
    }

    static MethodNode childMethod(String source, String name) {
        classMethod(source, 'Child', name)
    }

    static MethodNode classMethod(String source, String className, String name) {
        def nodes = new AstStringCompiler().compile(source, CompilePhase.CANONICALIZATION, false)
        (nodes.find { it instanceof ClassNode && it.name == className } as ClassNode).getMethods(name)[0]
    }

    def 'workspace supertypes are the superclasses and traits the workspace declares, nearest first'() {
        given:
        def nodes = new AstStringCompiler().compile('''
            trait Named { String name() { 'n' } }
            interface Plain {}
            class Top {}
            class Middle extends Top implements Named, Plain {}
            class Bottom extends Middle {}
        ''', CompilePhase.CANONICALIZATION, false)
        ClassNode bottom = nodes.find { it instanceof ClassNode && it.name == 'Bottom' } as ClassNode

        expect:
        ReuseHelpers.workspaceSupertypes(bottom)*.name == ['Middle', 'Top', 'Named']
    }

    def 'a text is read as a file, as members of a class or as statements of a method'() {
        expect:
        ReuseFinder.ofText(text, ReuseFinderSpec::parse, null).map { it.methods()*.name }.orElse(null) == methods

        where:
        text                                                    || methods
        'class C { int a() { 1 } \n int b() { 2 } }'            || ['a', 'b']
        'int a() {\n  1\n}'                                     || ['a']
        'int x = 1\nreturn x'                                   || []
        'private int size\nint a() { size }'                    || ['a']
        'def x( {'                                              || null
    }

    def 'a text that names its superclass is scoped by it for signature matches'() {
        given:
        Snapshot snapshot = SearchFixtures.compile(root, [
                'src/main/groovy/base/Base.groovy': 'package base\nclass Base {\n    String slug(String t) { t.trim() }\n}\n',
                'src/main/groovy/other/Child.groovy': 'package other\nclass Child extends base.Base {}\n'])
        def candidate = ReuseFinder.ofText('package other\nclass Mine extends Base {\n    String makeSlug(String v) { v }\n}',
                ReuseFinderSpec::parse, null).get()

        when:
        def matches = ReuseFinder.find(candidate, snapshot.result().modules(), snapshot.index(), query())

        then:
        matches*.symbol == ['base.Base.slug']
        matches*.kind == ['signature']
    }

    def 'an override with the same name is polymorphism, not a signature match'() {
        given:
        Snapshot snapshot = SearchFixtures.compile(root, [
                'src/main/groovy/demo/Base.groovy': 'package demo\nabstract class Base {\n    abstract String render(String t)\n}\n',
                'src/main/groovy/demo/One.groovy': 'package demo\nclass One extends Base {\n    String render(String t) { t }\n}\n',
                'src/main/groovy/demo/Two.groovy': 'package demo\nclass Two extends Base {\n    String render(String t) { t.trim() }\n}\n'])
        String two = SearchFixtures.uri(root, 'src/main/groovy/demo/Two.groovy')
        def candidate = ReuseFinder.ofSymbol(snapshot.result().modules()[two], two, snapshot.index().lines(two),
                'render', null)

        expect:
        ReuseFinder.find(candidate, snapshot.result().modules(), snapshot.index(), query()) == []
    }

    def 'the workspace facts are built once per snapshot and window'() {
        given:
        Snapshot snapshot = SearchFixtures.compile(root, ['src/main/groovy/A.groovy': SOURCE])
        def first = ReuseFinder.workspace(snapshot.result().modules(), snapshot.index(), 3)

        expect:
        ReuseFinder.workspace(snapshot.result().modules(), snapshot.index(), 3).is(first)
        !ReuseFinder.workspace(snapshot.result().modules(), snapshot.index(), 2).is(first)
        first.units()*.symbol as Set == ['A.helper', 'A.go'] as Set
    }

    def 'a script body is searched as a unit named after its class'() {
        given:
        Snapshot snapshot = SearchFixtures.compile(root, ['src/main/groovy/run.groovy': '''\
            String lower = title.toLowerCase()
            String dashed = lower.replaceAll(/[^a-z0-9]+/, '-')
            String trimmed = dashed.replaceAll(/^-+|-+$/, '')
            println trimmed
        '''.stripIndent()])
        def candidate = ReuseFinder.ofText('''\
            String lower = title.toLowerCase()
            String dashed = lower.replaceAll(/[^a-z0-9]+/, '-')
            String trimmed = dashed.replaceAll(/^-+|-+$/, '')
        '''.stripIndent(), ReuseFinderSpec::parse, null).get()

        when:
        def matches = ReuseFinder.find(candidate, snapshot.result().modules(), snapshot.index(),
                new ReuseFinder.Query(EngineConfig.Reuse.DEFAULTS, 3, 10, null))

        then:
        matches*.symbol == ['run']
        matches*.kind == ['exact']
    }

    static ReuseFinder.Query query() {
        new ReuseFinder.Query(EngineConfig.Reuse.DEFAULTS, EngineConfig.Reuse.DEFAULTS.minLines(), 10, null)
    }
}
