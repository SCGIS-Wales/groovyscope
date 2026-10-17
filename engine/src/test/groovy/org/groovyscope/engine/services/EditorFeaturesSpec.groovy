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

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.index.TextLines
import org.groovyscope.engine.model.FoldingRange
import org.groovyscope.engine.model.Position
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** Implementations, document highlights, folding ranges and inlay hints at the service level. */
class EditorFeaturesSpec extends Specification {

    static final String ZOO = '''package demo

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Animals of the zoo,
 * one per class.
 */
interface Animal {
    String sound()
}

abstract class Base implements Animal {
}

class Dog extends Base {
    String sound() {
        'woof'
    }
}

class Cat implements Animal {
    String sound() { 'meow' }
}

class Counter {
    String label = "/* not a comment */"

    int count() {
        def total = 0
        def name = 'counter'
        def counter = new AtomicInteger()
        def list = [1, 2].collect { item ->
            item * 2
        }
        total = total + 1
        total++
        Pattern.compile(name)
        return total + name.size() + counter.get() + list.size()
    }
}
'''

    @Shared
    @TempDir
    Path dir

    @Shared
    LanguageServices services

    @Shared
    String zoo

    def setupSpec() {
        Path src = Files.createDirectories(dir.resolve('src/main/groovy/demo'))
        Files.writeString(src.resolve('Zoo.groovy'), ZOO)
        zoo = src.resolve('Zoo.groovy').toUri().toString()
        services = new LanguageServices(new WorkspaceModel(dir, new EngineConfig(['src/main/groovy'], [], [], false)),
                new GroovyCompiler())
    }

    static Position at(String needle, int occurrence = 1, int offset = 0) {
        int index = -1
        occurrence.times { index = ZOO.indexOf(needle, index + 1) }
        assert index >= 0
        new TextLines(ZOO).positionOf(index + offset)
    }

    static int line(String needle) {
        at(needle).line()
    }

    def 'implementations of an interface are its subclasses and implementors, transitively'() {
        when:
        def found = services.implementations(zoo, at('Animal {', 1, 1))

        then:
        found*.range()*.start()*.line() as Set == [line('class Base'), line('class Dog'), line('class Cat')] as Set
        found.every { it.uri() == zoo }
    }

    def 'implementations of a method are the overriding methods'() {
        when:
        def found = services.implementations(zoo, at('String sound()', 1, 8))

        then:
        found*.range()*.start()*.line() as Set == [line("String sound() {\n        'woof'"), line("String sound() { 'meow'")] as Set
    }

    def 'implementations are empty for leaf methods and unresolvable positions'() {
        expect:
        services.implementations(zoo, at("String sound() { 'meow'", 1, 8)).isEmpty()
        services.implementations(zoo, new Position(1, 0)).isEmpty()
        services.implementations(zoo, at('def total', 1, 5)).isEmpty()
    }

    def 'highlights mark the declaration and assignments as writes and other uses as reads'() {
        when:
        def highlights = services.highlights(zoo, at('def total', 1, 5))

        then:
        highlights.collect { [it.range().start().line(), it.write()] } as Set == [
                [line('def total'), true],
                [line('total = total'), true],
                [line('total = total'), false],
                [line('total++'), true],
                [line('return total'), false]] as Set
        highlights.size() == 5
        services.highlights(zoo, new Position(1, 0)).isEmpty()
    }

    def 'folding ranges cover classes, methods, closures, comments and imports'() {
        when:
        def ranges = services.foldingRanges(zoo)

        then:
        ranges.contains(new FoldingRange(line('import java.util.concurrent'), line('import java.util.regex'), FoldingRange.Kind.IMPORTS))
        ranges.contains(new FoldingRange(line('/**'), line(' */'), FoldingRange.Kind.COMMENT))
        ranges.contains(new FoldingRange(line('class Counter'), line('return total') + 1, FoldingRange.Kind.REGION))
        ranges.contains(new FoldingRange(line('int count()'), line('return total'), FoldingRange.Kind.REGION))
        ranges.contains(new FoldingRange(line('collect {'), line('item * 2'), FoldingRange.Kind.REGION))
        ranges.contains(new FoldingRange(line('class Dog'), line("'woof'") + 1, FoldingRange.Kind.REGION))
        ranges.every { it.endLine() > it.startLine() }
        ranges.count { it.kind() == FoldingRange.Kind.COMMENT } == 1
    }

    def 'folding works on text alone when the document does not parse'() {
        expect:
        DocumentSupport.foldingRanges(null, 'import a.B\nimport c.D\n\nimport e.F\nclass X {\n  // x\n  /* a\n  b */\n  String s = \'\'\'/*\n\'\'\'\n}\n') == [
                new FoldingRange(0, 3, FoldingRange.Kind.IMPORTS), new FoldingRange(6, 7, FoldingRange.Kind.COMMENT)]
        DocumentSupport.foldingRanges(null, null).isEmpty()
        DocumentSupport.foldingRanges(null, 'String s = "a\\"/*"\n/* open\n') == [new FoldingRange(1, 2, FoldingRange.Kind.COMMENT)]
    }

    def 'inlay hints show inferred types of def locals within the requested range'() {
        given:
        def whole = new TextLines(ZOO).fullRange()

        when:
        def hints = services.inlayHints(zoo, whole)
        def narrowed = services.inlayHints(zoo, new Range(new Position(line('def name'), 0), new Position(line('def name'), 40)))

        then:
        hints*.label() == [': int', ': String', ': AtomicInteger']
        hints[1].position() == at('def name', 1, 8)
        narrowed*.label() == [': String']
        services.inlayHints('file:///nowhere.groovy', whole).isEmpty()
        DocumentSupport.inlayHints(null, new TextLines(''), whole).isEmpty()
    }
}
