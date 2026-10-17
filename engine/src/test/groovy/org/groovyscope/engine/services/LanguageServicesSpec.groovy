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
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.SymbolKind
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** Navigation over the basic-app fixture: the behaviour Kiro's `code` tool relies on. */
class LanguageServicesSpec extends Specification {

    static final String MAIN = 'src/main/groovy/app/Main.groovy'
    static final String GREETER = 'src/main/groovy/app/Greeter.groovy'
    static final String PERSON = 'src/main/groovy/app/model/Person.groovy'
    static final String SPEC = 'src/test/groovy/app/GreeterSpec.groovy'

    @Shared
    LanguageServices services = Fixtures.services('basic-app')

    private static String uri(String relative) {
        Fixtures.uri('basic-app', relative)
    }

    private static pos(String relative, String needle, int occurrence = 1, int offset = 0) {
        Fixtures.pos('basic-app', relative, needle, occurrence, offset)
    }

    def 'definition of a method call on an inferred receiver'() {
        when:
        def definition = services.definition(uri(MAIN), pos(MAIN, "greeter.greet('Ada')", 1, 8)).get()

        then:
        definition.symbol().name() == 'greet'
        definition.symbol().kind() == SymbolKind.METHOD
        definition.symbol().detail() == 'String greet(String name)'
        definition.symbol().location().uri() == uri(GREETER)
        definition.confidence() == Confidence.INFERRED
        definition.inWorkspace
    }

    def 'definition of a method call on a declared receiver is static'() {
        when:
        def definition = services.definition(uri(MAIN), pos(MAIN, 'typed.greet()', 1, 6)).get()

        then:
        definition.symbol().detail() == 'String greet()'
        definition.confidence() == Confidence.STATIC
    }

    def 'a call on an unknown receiver is matched by name only'() {
        when:
        def definition = services.definition(uri(MAIN), pos(MAIN, "unknownThing.greet('x')", 1, 13)).get()

        then:
        definition.symbol().name() == 'greet'
        definition.confidence() == Confidence.DYNAMIC_UNKNOWN
    }

    def 'definition of a type usage, a constructor call and a property getter'() {
        expect:
        services.definition(uri(MAIN), pos(MAIN, 'Person p =')).get().symbol().with {
            kind() == SymbolKind.CLASS && location().uri() == uri(PERSON)
        }
        services.definition(uri(MAIN), pos(MAIN, "new Person('Ada', 36)", 1, 4)).get().symbol().with {
            name() == 'Person' && location().uri() == uri(PERSON)
        }
        services.definition(uri(MAIN), pos(MAIN, 'p.adult', 1, 2)).get().with {
            symbol().detail() == 'boolean isAdult()' && confidence() == Confidence.STATIC
        }
    }

    def 'definition of a local variable and of a field through implicit this'() {
        expect:
        services.definition(uri(MAIN), pos(MAIN, 'println greeter.greet', 1, 8)).get().symbol().kind() == SymbolKind.VARIABLE
        services.definition(uri(GREETER), pos(GREETER, 'count++')).get().symbol().with {
            kind() == SymbolKind.FIELD && name() == 'count'
        }
        services.definition(uri(GREETER), pos(GREETER, 'greet(DEFAULT_NAME)', 1, 6)).get().symbol().with {
            kind() == SymbolKind.PROPERTY && name() == 'DEFAULT_NAME'  // no visibility modifier: a Groovy property
        }
    }

    def 'references to a method include static, inferred and name-only call sites'() {
        when:
        def references = services.references(uri(GREETER), pos(GREETER, 'String greet(String name)', 1, 7), false)
        def byConfidence = references.groupBy { it.confidence() }

        then: 'GreeterSpec cannot be fully resolved (Spock is not on the classpath) so its call is inferred by name'
        references.size() == 4
        byConfidence[Confidence.STATIC]*.location()*.uri() == [uri(GREETER)]
        byConfidence[Confidence.INFERRED]*.location()*.uri().sort() == [uri(MAIN), uri(SPEC)]
        byConfidence[Confidence.DYNAMIC_UNKNOWN]*.location()*.uri() == [uri(MAIN)]
    }

    def 'references can include the declaration'() {
        when:
        def references = services.references(uri(GREETER), pos(GREETER, 'private int count'), true)

        then:
        references.size() == 3
        references.every { it.location().uri() == uri(GREETER) }
        references.every { it.confidence() == Confidence.STATIC }
    }

    def 'hover shows the signature, the owner and the groovydoc'() {
        expect:
        services.hover(uri(GREETER), pos(GREETER, 'String greeting', 1, 7)).get().markdown().with {
            contains('String greeting') && contains('`app.Greeter`')
        }
        services.hover(uri(MAIN), pos(MAIN, 'new Greeter()', 1, 4)).get().markdown().with {
            contains('class Greeter') && contains('Greets people politely.')
        }
        services.hover(uri(MAIN), pos(MAIN, "unknownThing.greet('x')", 1, 13)).get().markdown().contains('dynamic_unknown')
        services.hover(uri(MAIN), pos(MAIN, 'def greeter', 1, 4)).get().markdown().contains('Greeter greeter')
    }

    def 'document symbols describe the class and its members'() {
        when:
        def symbols = services.documentSymbols(uri(GREETER))

        then:
        symbols.size() == 1
        symbols[0].name() == 'Greeter'
        symbols[0].kind() == SymbolKind.CLASS
        symbols[0].children()*.name().sort() == ['DEFAULT_NAME', 'count', 'getCount', 'greet', 'greet', 'greeting']
        symbols[0].selectionRange() != null
    }

    def 'document symbols of a script list its top level statements owner'() {
        expect:
        services.documentSymbols(uri(MAIN))*.kind() == [SymbolKind.SCRIPT]
        services.documentSymbols(uri('src/main/groovy/app/Shapes.groovy'))*.name() == ['Shape', 'Named', 'Circle']
    }

    def 'workspace symbols match case-insensitively'() {
        expect:
        services.workspaceSymbols('gree')*.name().toSet().containsAll(['Greeter', 'greet', 'greeting', 'GreeterSpec'])
        services.workspaceSymbols('nothing-like-this').empty
    }

    def 'completion after a dot lists the members of the receiver'() {
        when:
        def labels = services.completion(uri(MAIN), pos(MAIN, "greeter.greet('Ada')", 1, 8))*.label()

        then:
        'greet(String name)' in labels
        'greet()' in labels
        'greeting' in labels
        'getCount()' in labels

        when:
        def filtered = services.completion(uri(MAIN), pos(MAIN, "greeter.greet('Ada')", 1, 11))*.label()

        then:
        filtered.every { it.toLowerCase().startsWith('gre') }
        'greeting' in filtered
    }

    def 'completion without a dot proposes locals, classes and keywords'() {
        when:
        def labels = services.completion(uri(MAIN), pos(MAIN, 'println p.name', 1, 8))*.label()

        then:
        'p' in labels
        'greeter' in labels
        'Person' in labels
        labels.any { it.startsWith('println(') }
        'package' in labels
    }

    def 'signature help names the active parameter'() {
        when:
        def help = services.signatureHelp(uri(MAIN), pos(MAIN, "greeter.greet('Ada')", 1, 15)).get()

        then:
        help.signatures()*.label() == ['String greet(String name)']
        help.signatures()[0].parameters() == ['String name']
        help.activeParameter() == 0
    }

    def 'prepare rename returns the name token and the placeholder'() {
        when:
        def preparation = services.prepareRename(uri(GREETER), pos(GREETER, 'String greeting', 1, 7))

        then:
        !preparation.refused
        preparation.placeholder() == 'greeting'
        preparation.range().start().line() == pos(GREETER, 'String greeting').line()
    }

    def 'rename edits every static reference of a property'() {
        when:
        def result = services.rename(uri(GREETER), pos(GREETER, 'String greeting', 1, 7), 'salutation')

        then:
        !result.refused
        result.edits()[uri(GREETER)].size() == 2
        result.edits()[uri(GREETER)].every { it.newText() == 'salutation' }
        !result.edits().containsKey(uri(MAIN))
    }

    def 'rename is refused when a reference is matched by name only'() {
        when:
        def result = services.rename(uri(GREETER), pos(GREETER, 'String greet(String name)', 1, 7), 'welcome')

        then:
        result.refused
        result.refusalReason().contains('name only')
    }

    def 'rename rejects invalid identifiers and unresolvable positions'() {
        expect:
        services.rename(uri(GREETER), pos(GREETER, 'private int count', 1, 12), '1bad').refused
        services.prepareRename(uri(GREETER), new org.groovyscope.engine.model.Position(0, 0)).refused
    }

    def 'type definition of a variable goes to its class'() {
        expect:
        services.typeDefinition(uri(MAIN), pos(MAIN, 'p.adult')).get().symbol().with {
            name() == 'Person' && location().uri() == uri(PERSON)
        }
        services.typeDefinition(uri(MAIN), pos(MAIN, 'println greeter.greet', 1, 8)).get().symbol().name() == 'Greeter'
    }

    def 'diagnostics are reported per document'() {
        when:
        def diagnostics = services.diagnostics()

        then:
        diagnostics[uri(GREETER)].empty
        // spock.lang is not on the (empty) classpath: the unresolved import folds into one GS100 warning
        diagnostics[uri(SPEC)]*.code() == ['GS100']
        diagnostics[uri(SPEC)][0].message().contains('spock.lang')
    }

    def 'an edit in an open document is reflected after marking dirty'() {
        given:
        String main = Fixtures.text('basic-app', MAIN)
        String edited = main.replace("unknownThing.greet('x')", "typed.greet('Bob')")

        when:
        services.workspace().open(uri(MAIN), edited)
        services.markDirty()
        def result = services.rename(uri(GREETER), pos(GREETER, 'String greet(String name)', 1, 7), 'welcome')

        then:
        !result.refused
        result.edits().keySet().containsAll([uri(GREETER), uri(MAIN), uri(SPEC)])

        cleanup:
        services.workspace().close(uri(MAIN))
        services.markDirty()
    }
    def 'an edit that arrives during a compile is not lost'() {
        given: 'a fresh workspace with an open virtual document'
        def fresh = Fixtures.services('basic-app')
        String virtual = 'file:///virtual/Later.groovy'
        fresh.workspace().open(virtual, 'class Later { }')
        fresh.markDirty()

        when: 'a compile starts in the background and the document changes before it finishes'
        def background = new Thread({ fresh.refresh() })
        background.start()
        fresh.workspace().change(virtual, 'class Later { String added() { "x" } }')
        fresh.markDirty()
        background.join()

        then: 'the next query sees the edit, either from that compile or from a fresh one'
        fresh.snapshot().symbols().workspaceSymbols('added', 10)*.name() == ['added']
        !fresh.isDirty()

        cleanup:
        fresh.workspace().close(virtual)
    }
}
