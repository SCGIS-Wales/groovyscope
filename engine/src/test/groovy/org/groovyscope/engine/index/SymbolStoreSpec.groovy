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

        when: 'saving again replaces everything'
        store.save(sources, [child])

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
        def document = cold.documentSymbols(Fixtures.uri('basic-app', 'src/main/groovy/app/Greeter.groovy'))*.name()

        then:
        names.containsAll(['Greeter', 'GreeterSpec'])
        document.containsAll(['Greeter', 'greet', 'greeting'])

        and: 'the background compile catches up'
        waitFor { cold.hasCompiled() }
        cold.workspaceSymbols('Greeter')*.name().contains('Greeter')
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
}
