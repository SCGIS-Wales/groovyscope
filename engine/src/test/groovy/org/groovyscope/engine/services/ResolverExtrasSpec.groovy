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

import org.groovyscope.engine.model.Confidence
import org.groovyscope.engine.model.SymbolKind
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** Resolution across inheritance, static imports, chained calls, casts and constructors (Extras.groovy). */
class ResolverExtrasSpec extends Specification {

    static final String EXTRAS = 'src/main/groovy/app/Extras.groovy'

    @Shared
    LanguageServices services = Fixtures.services('basic-app')

    private static String uri() {
        Fixtures.uri('basic-app', EXTRAS)
    }

    private static pos(String needle, int occurrence = 1, int offset = 0) {
        Fixtures.pos('basic-app', EXTRAS, needle, occurrence, offset)
    }

    def 'calls through super and this resolve along the hierarchy'() {
        expect:
        services.definition(uri(), pos('super.base()', 1, 6)).get().with {
            symbol().detail() == 'String base()' && symbol().container() == 'app.Base' && confidence() == Confidence.STATIC
        }
        services.definition(uri(), pos('this.base()', 1, 5)).get().symbol().container() == 'app.Base'
    }

    def 'a static call through the class name is exact'() {
        when:
        def definition = services.definition(uri(), pos('Derived.twice(3)', 1, 8)).get()

        then:
        definition.symbol().detail() == 'static int twice(int n)'
        definition.confidence() == Confidence.STATIC
    }

    def 'a statically imported JDK method resolves to external overloads'() {
        when:
        def definition = services.definition(uri(), pos('max(n, 0)')).get()

        then:
        definition.symbol().name() == 'max'
        !definition.inWorkspace
        definition.symbol().container() == 'java.lang.Math'
    }

    def 'chained calls and property chains infer the receiver type'() {
        expect:
        services.definition(uri(), pos('d.derived().toUpperCase()', 1, 12)).get().with {
            symbol().name() == 'toUpperCase' && !inWorkspace && confidence() == Confidence.INFERRED
        }
        services.definition(uri(), pos('person.name.toLowerCase()', 1, 12)).get().with {
            symbol().name() == 'toLowerCase' && !inWorkspace
        }
        services.definition(uri(), pos('person.name.toLowerCase()', 1, 7)).get().symbol().with {
            kind() == SymbolKind.PROPERTY && name() == 'name'
        }
    }

    def 'constructor overloads are picked by arity'() {
        expect:
        services.definition(uri(), pos("new Derived('x')", 1, 4)).get().symbol().kind() == SymbolKind.CLASS
        services.definition(uri(), pos("new Derived('x')")).get().with {
            symbol().kind() == SymbolKind.CONSTRUCTOR && symbol().detail() == 'Derived(String seed)'
        }
        services.definition(uri(), pos('new Derived()')).get().symbol().detail() == 'Derived()'
        services.definition(uri(), pos('super()')).get().symbol().kind() == SymbolKind.CLASS
        services.definition(uri(), pos('this()')).get().symbol().detail() == 'Derived()'
    }

    def 'a cast fixes the receiver type and safe navigation still resolves'() {
        expect:
        services.definition(uri(), pos('casted?.isAdult()', 1, 8)).get().with {
            symbol().detail() == 'boolean isAdult()' && confidence() == Confidence.STATIC
        }
        services.typeDefinition(uri(), pos('casted?.isAdult()')).get().symbol().name() == 'Person'
    }

    def 'GDK extension methods on JDK types are left unresolved rather than guessed'() {
        expect:
        services.definition(uri(), pos('s.size()', 1, 2)).empty
    }

    def 'generic field types, arrays and loop variables render and resolve'() {
        expect:
        services.hover(uri(), pos('names.add(chained)')).get().markdown().contains('List<String> names')
        services.hover(uri(), pos('String[] tags', 1, 9)).get().markdown().contains('String[] tags')
        services.definition(uri(), pos('println tag', 1, 8)).get().symbol().kind() == SymbolKind.PARAMETER
        services.definition(uri(), pos('e.message')).get().symbol().with {
            kind() == SymbolKind.PARAMETER && detail() == 'IllegalStateException e'
        }
        services.definition(uri(), pos('names.add(chained)', 1, 6)).get().with {
            symbol().name() == 'add' && !inWorkspace
        }
        services.definition(uri(), pos('this.names.size()', 1, 5)).get().symbol().name() == 'names'
    }

    def 'class signatures mention abstract, extends and implements'() {
        expect:
        services.hover(uri(), pos('abstract class Base', 1, 15)).get().markdown().contains('abstract class Base')
        services.hover(uri(), pos('class Derived extends Base', 1, 6)).get().markdown().contains('class Derived extends Base')
        services.documentSymbols(uri())*.name() == ['Base', 'Derived', 'Extras']
        services.documentSymbols(uri())[0].children()*.name() == ['base', 'label']
    }

    def 'references to an inherited method include calls through subclasses'() {
        when:
        def references = services.references(uri(), pos('String base()', 1, 7), true)

        then:
        references.size() == 3
        references*.confidence().every { it == Confidence.STATIC }
    }
}
