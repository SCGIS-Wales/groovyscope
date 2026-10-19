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
package org.groovyscope.engine.index

import java.nio.file.Files
import java.nio.file.Path
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Location
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.model.Symbol
import org.groovyscope.engine.model.SymbolKind
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import org.groovyscope.engine.workspace.WorkspaceModel
import spock.lang.Specification
import spock.lang.TempDir

class SymbolStoreSpec extends Specification {

    @TempDir
    Path temp

    def 'symbols round-trip and only matching hashes are loaded'() {
        given:
        def store = SymbolStore.open(temp.resolve('cache/symbols.db'))
        def child = new Symbol('m', SymbolKind.METHOD, 'p.C', new Location('file:///C.groovy', Range.of(2, 0, 3, 0)), Range.of(2, 4, 2, 5), 'void m()', [])
        def root = new Symbol('C', SymbolKind.CLASS, 'p', new Location('file:///C.groovy', Range.of(0, 0, 9, 0)), null, 'class C', [child])
        def external = new Symbol('X', SymbolKind.CLASS, null, null, null, null, [])
        def sources = ['file:///C.groovy': 'class C { void m() {} }']

        when:
        store.save(sources, [root, external])

        then:
        store.count() == 2
        store.load(sources)*.name == ['C', 'm']
        store.load(sources)[0].selectionRange() == Range.of(0, 0, 9, 0)
        store.load(sources)[1].container() == 'p.C'
        store.load(['file:///C.groovy': 'class C { }']).empty
        store.load(['file:///Other.groovy': 'x']).empty

        when: 'saving the same text keeps the rows; a changed text replaces them'
        store.save(sources, [child])

        then:
        store.count() == 2

        when:
        store.save(['file:///C.groovy': 'class C { void m() { } }'], [child])

        then:
        store.count() == 1

        cleanup:
        store.close()
    }

    def 'language services answer symbol searches from the cache before the first compile'() {
        given:
        Path db = temp.resolve('symbols.db')
        def warm = new LanguageServices(new WorkspaceModel(Fixtures.project('basic-app'), EngineConfig.DEFAULTS), new GroovyCompiler(), SymbolStore.open(db))
        warm.refresh()
        waitForRows(db)

        when: 'a fresh instance on the same workspace is asked before compiling'
        def cold = new LanguageServices(new WorkspaceModel(Fixtures.project('basic-app'), EngineConfig.DEFAULTS), new GroovyCompiler(), SymbolStore.open(db))
        def names = cold.workspaceSymbols('Greet')*.name()
        def outline = cold.documentSymbols(Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy'))
        def document = outline*.name() + outline*.children().flatten()*.name()

        then:
        names.containsAll(['Greeter', 'GreeterSpec'])
        document.containsAll(['Greeter', 'greet', 'greeting'])

        and: 'cached and compiled outlines have the same shape: members nested under their class'
        outline*.name() == ['Greeter']

        and: 'the background compile catches up and answers the same way'
        waitFor { cold.hasCompiled() }
        cold.workspaceSymbols('Greeter')*.name().contains('Greeter')
        cold.documentSymbols(Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy'))*.name() == ['Greeter']
    }

    def 'a corrupt database is reported as an IOException'() {
        given:
        Path bad = temp.resolve('bad.db')
        Files.writeString(bad, 'this is not sqlite')

        when:
        SymbolStore.open(bad).count()

        then:
        thrown(IOException)
    }

    private static void waitForRows(Path db) {
        long deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            def store = SymbolStore.open(db)
            long rows = store.count()
            store.close()
            if (rows > 0) {
                return
            }
            Thread.sleep(50)
        }
        assert false : 'symbol cache never filled'
    }

    private static void waitFor(Closure<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(50)
        }
        assert condition()
    }

    private static Symbol symbol(String uri, String name) {
        new Symbol(name, SymbolKind.CLASS, null, new Location(uri, Range.of(0, 0, 0, 5)), Range.of(0, 0, 0, 5), name, List.of())
    }

    private static List<Long> rowids(Path file, String uri) {
        def connection = java.sql.DriverManager.getConnection("jdbc:sqlite:${file}")
        try {
            def statement = connection.prepareStatement('SELECT rowid FROM symbols WHERE uri = ? ORDER BY rowid')
            statement.setString(1, uri)
            def rows = statement.executeQuery()
            List<Long> ids = []
            while (rows.next()) {
                ids << rows.getLong(1)
            }
            ids
        } finally {
            connection.close()
        }
    }

    def 'a save rewrites only the documents that changed, in WAL mode, and two writers do not collide (E-23)'() {
        given:
        Path file = temp.resolve('wal/symbols.db')
        def store = SymbolStore.open(file)
        def other = SymbolStore.open(file)
        Map<String, String> sources = ['file:///w/A.groovy': 'class A {}', 'file:///w/B.groovy': 'class B {}']
        store.save(sources, [symbol('file:///w/A.groovy', 'A'), symbol('file:///w/B.groovy', 'B')])
        def before = rowids(file, 'file:///w/A.groovy')

        when: 'B changes and C is gone, A is untouched'
        Map<String, String> edited = ['file:///w/A.groovy': 'class A {}', 'file:///w/B.groovy': 'class B { int x }']
        store.save(edited, [symbol('file:///w/A.groovy', 'A'), symbol('file:///w/B.groovy', 'B')])

        then:
        rowids(file, 'file:///w/A.groovy') == before
        store.load(edited)*.name().sort() == ['A', 'B']
        store.journalMode() == 'wal'

        when: 'two connections save at the same time'
        def saves = [store, other].collect { s ->
            java.util.concurrent.CompletableFuture.runAsync {
                20.times { i -> s.save(['file:///w/A.groovy': "class A { int v${i} }".toString()], [symbol('file:///w/A.groovy', 'A')]) }
            }
        }
        saves*.get(60, java.util.concurrent.TimeUnit.SECONDS)

        then:
        notThrown(Exception)

        cleanup:
        store?.close()
        other?.close()
    }
}
