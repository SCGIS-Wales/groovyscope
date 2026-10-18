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

import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.DiagnosticSeverity
import org.groovyscope.engine.model.Completion
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.Hover
import org.groovyscope.engine.model.Location
import org.groovyscope.engine.model.Range
import org.groovyscope.engine.model.SignatureHelp
import org.groovyscope.engine.model.Symbol
import org.groovyscope.engine.model.SymbolKind
import org.groovyscope.engine.model.TextEdit
import spock.lang.Specification

class ConvertersSpec extends Specification {

    def 'every symbol kind maps to an LSP symbol kind and a completion kind'() {
        expect:
        SymbolKind.values().every { Converters.symbolKind(it) != null && Converters.completionKind(it) != null }
        Converters.symbolKind(null) == org.eclipse.lsp4j.SymbolKind.Variable
        Converters.completionKind(null) == CompletionItemKind.Keyword
        Converters.symbolKind(SymbolKind.TRAIT) == org.eclipse.lsp4j.SymbolKind.Interface
        Converters.completionKind(SymbolKind.METHOD) == CompletionItemKind.Method
    }

    def 'every severity maps'() {
        expect:
        Diagnostic.Severity.values()*.with { Converters.severity(it) } ==
            [DiagnosticSeverity.Error, DiagnosticSeverity.Warning, DiagnosticSeverity.Information, DiagnosticSeverity.Hint]
    }

    def 'diagnostics, edits and locations keep their coordinates'() {
        given:
        def range = Range.of(1, 2, 3, 4)

        expect:
        Converters.toLsp(new Diagnostic('file:///x', range, Diagnostic.Severity.WARNING, 'groovyc', 'syntax', 'oops')).with {
            it.range.start.line == 1 && it.range.end.character == 4 && it.code.getLeft() == 'syntax' && it.source == 'groovyc'
        }
        Converters.toLsp(new Diagnostic('file:///x', range, Diagnostic.Severity.ERROR, 'groovyc', null, 'oops')).code == null
        Converters.toLsp(new TextEdit(range, 'new')).newText == 'new'
        Converters.toLsp(new Location('file:///x', range)).uri == 'file:///x'
        Converters.fromLsp(new org.eclipse.lsp4j.Position(5, 6)) == new org.groovyscope.engine.model.Position(5, 6)
    }

    def 'document symbols fall back to the full range when the selection is outside it'() {
        given:
        def child = new Symbol('m', SymbolKind.METHOD, 'C', new Location('file:///x', Range.of(2, 0, 3, 0)), Range.of(2, 4, 2, 5), 'void m()', [])
        def external = new Symbol('ext', SymbolKind.METHOD, 'C', null, null, null, [])
        def symbol = new Symbol('C', SymbolKind.CLASS, 'p', new Location('file:///x', Range.of(0, 0, 9, 0)), Range.of(20, 0, 20, 1), 'class C', [child, external])

        when:
        def converted = Converters.documentSymbol(symbol)

        then:
        converted.selectionRange == converted.range
        converted.children*.name == ['m']
        converted.children[0].selectionRange.start.character == 4
    }

    def 'a document symbol starts on the line of its name, below its annotations'() {
        given: 'a method whose node starts at an annotation two lines above the name'
        def method = new Symbol('execute', SymbolKind.METHOD, 'C', new Location('file:///x', Range.of(80, 4, 103, 5)),
                Range.of(81, 12, 81, 19), 'Results execute()', [])

        when:
        def converted = Converters.documentSymbol(method)

        then:
        converted.range.start.line == 81 && converted.range.start.character == 0
        converted.range.end.line == 103
        converted.selectionRange.start.line == 81 && converted.selectionRange.start.character == 12
    }

    def 'workspace symbols use the selection range when present'() {
        given:
        def symbol = new Symbol('C', SymbolKind.CLASS, 'p', new Location('file:///x', Range.of(0, 0, 9, 0)), Range.of(0, 6, 0, 7), 'class C', [])
        def noSelection = new Symbol('D', SymbolKind.CLASS, null, new Location('file:///y', Range.of(1, 0, 2, 0)), null, null, [])

        expect:
        Converters.workspaceSymbol(symbol).location.getLeft().range.start.character == 6
        Converters.workspaceSymbol(noSelection).location.getLeft().range.start.line == 1
    }

    def 'completion items, hovers and signature help carry their details'() {
        expect:
        Converters.completionItem(new Completion('greet(String name)', SymbolKind.METHOD, 'String greet(String)', 'greet(')).with {
            it.insertText == 'greet(' && it.detail == 'String greet(String)'
        }
        Converters.hover(new Hover('**doc**', Range.of(0, 0, 0, 1))).contents.getRight().value == '**doc**'
        Converters.signatureHelp(new SignatureHelp([new SignatureHelp.Signature('void m(int a)', ['int a'], 'docs')], 0, 0)).with {
            it.signatures[0].parameters[0].label.getLeft() == 'int a' && it.signatures[0].documentation.getLeft() == 'docs'
        }
    }
}
