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
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/** The agent-facing analysis requests: documentSymbols extras, readSymbol, findCode, duplicates and overview. */
class AnalysisRequestsSpec extends Specification {

    static final String PROJECT = 'search-sample'
    static final String ORDERS = 'src/main/groovy/sample/OrderService.groovy'
    static final String REPORT = 'src/main/groovy/sample/Report.groovy'
    static final String HANDLERS = 'src/main/groovy/sample/Handlers.groovy'

    @Shared GroovyscopeLanguageServer server = new GroovyscopeLanguageServer({ }, Fixtures.project(PROJECT), EngineConfig.DEFAULTS)
    @Shared String root = Fixtures.project(PROJECT).toString()

    @TempDir Path temp

    Map call(String method, Map params) {
        server."$method"([root: root] + params).get(60, TimeUnit.SECONDS) as Map
    }

    static String uri(String relative) {
        Fixtures.uri(PROJECT, relative)
    }

    static int lineOf(String relative, String needle) {
        Fixtures.pos(PROJECT, relative, needle).line()
    }

    def 'documentSymbols reports each symbol\'s last line and the document\'s line count'() {
        when:
        def outline = call('documentSymbols', [uri: uri(ORDERS)])
        def total = outline.symbols[0].children.find { it.name == 'total' }

        then:
        outline.lines == Fixtures.text(PROJECT, ORDERS).readLines().size()
        outline.symbols[0].name == 'OrderService'
        outline.symbols[0].endLine == outline.lines - 1
        total.endLine == lineOf(ORDERS, 'BigDecimal invoiceTotal') - 2
        outline.symbols[0].children.every { it.endLine != null }
    }

    def 'readSymbol returns a method with its Javadoc and annotations'() {
        when:
        def total = call('readSymbol', [uri: uri(ORDERS), symbol: 'total'])

        then:
        total.found
        total.name == 'total'
        total.kind == 'method'
        total.container == 'sample.OrderService'
        total.startLine == lineOf(ORDERS, '/**')
        total.endLine == lineOf(ORDERS, 'BigDecimal invoiceTotal') - 2
        total.text.startsWith('    /**')
        total.text.contains('@Deprecated')
        total.text.endsWith('    }')
        !total.truncated
    }

    def 'readSymbol accepts qualified, hash and class forms'() {
        expect:
        call('readSymbol', [uri: uri(ORDERS), symbol: 'OrderService.invoiceTotal']).name == 'invoiceTotal'
        call('readSymbol', [uri: uri(ORDERS), symbol: 'OrderService#invoiceTotal']).startLine == lineOf(ORDERS, 'BigDecimal invoiceTotal')
        call('readSymbol', [uri: uri(ORDERS), symbol: 'sample.OrderService']).kind == 'class'
        call('readSymbol', [uri: uri(ORDERS), symbol: 'OrderService']).startLine == lineOf(ORDERS, 'class OrderService')
        call('readSymbol', [uri: uri(ORDERS), symbol: 'PREFIX']).kind == 'property'
        call('readSymbol', [uri: uri(ORDERS), symbol: 'Other.total']).found == false
    }

    def 'readSymbol picks the overload at or nearest the given line'() {
        given:
        int second = lineOf(REPORT, 'String label(int')

        expect:
        call('readSymbol', [uri: uri(REPORT), symbol: 'label']).startLine == lineOf(REPORT, 'String label(String')
        call('readSymbol', [uri: uri(REPORT), symbol: 'label', line: second + 1]).startLine == second
        call('readSymbol', [uri: uri(REPORT), symbol: 'label', line: second + 20]).startLine == second
    }

    def 'readSymbol truncates on a line boundary and lists candidates when nothing matches'() {
        when:
        def cut = call('readSymbol', [uri: uri(ORDERS), symbol: 'total', maxChars: 40])
        def tiny = call('readSymbol', [uri: uri(ORDERS), symbol: 'total', maxChars: 3])
        def missing = call('readSymbol', [uri: uri(ORDERS), symbol: 'otal'])
        def outside = call('readSymbol', [uri: temp.resolve('X.groovy').toUri().toString(), symbol: 'x'])

        then:
        cut.truncated
        cut.text.length() <= 40
        cut.text.readLines().every { Fixtures.text(PROJECT, ORDERS).contains(it) }
        tiny.truncated
        tiny.text == '   '
        !missing.found
        missing.candidates*.name.toSet() == ['total', 'invoiceTotal'] as Set
        missing.candidates.every { it.kind == 'method' && it.line > 0 }
        outside == [found: false, candidates: []]
    }

    def 'readSymbol needs a uri and a symbol'() {
        when:
        call('readSymbol', [uri: uri(ORDERS)])

        then:
        def e = thrown(ExecutionException)
        e.cause.message.contains('needs a uri and a symbol')
    }

    def 'findCode finds catch clauses by type, empty body and rethrow'() {
        when:
        def all = call('findCode', [kind: 'catch'])
        def empty = call('findCode', [kind: 'catch', emptyBody: true])
        def swallowed = call('findCode', [kind: 'catch', rethrows: false])
        def broad = call('findCode', [kind: 'catch', type: 'Exception'])
        def io = call('findCode', [kind: 'catch', type: 'java.io.IOException'])

        then:
        all.count == 2
        all.matches*.enclosing == ['Handlers.load', 'Handlers.store']
        all.matches[0].detail == 'catch (IOException e) empty'
        all.matches[0].snippet == '} catch (IOException e) {'
        all.matches[0].uri == uri(HANDLERS)
        all.matches[0].line == lineOf(HANDLERS, 'catch (IOException')
        all.matches[0].kind == 'catch'
        all.matches[0].handling == 'swallowed'
        all.matches[0].code == 'EmptyCatchBlock'
        all.matches[0].catches == 'IOException'
        all.matches[0].variable == 'e'
        all.matches[0].implicitType == null
        all.matches[1].handling == 'rethrows'
        all.matches[1].code == 'CatchException'
        empty.matches*.enclosing == ['Handlers.load']
        swallowed.matches*.enclosing == ['Handlers.load']
        broad.matches*.enclosing == ['Handlers.store']
        broad.matches[0].endLine > broad.matches[0].line
        io.count == 1
        !all.truncated
    }

    def 'findCode finds throws, calls, constructor calls and return null'() {
        when:
        def throwing = call('findCode', [kind: 'throw', type: 'IllegalStateException'])
        def none = call('findCode', [kind: 'throw', type: 'IOException'])
        def logging = call('findCode', [kind: 'call', name: 'info', type: 'Logger'])
        def wrongType = call('findCode', [kind: 'call', name: 'info', type: 'String'])
        def upper = call('findCode', [kind: 'call', name: 'toUpperCase', type: 'java.lang.String'])
        def files = call('findCode', [kind: 'new', type: 'File'])
        def nulls = call('findCode', [kind: 'return-null'])

        then:
        throwing.matches*.detail == ['throw new IllegalStateException']
        none.count == 0
        logging.count == 2
        logging.matches[0].detail == 'call log.info'
        logging.matches*.enclosing == ['OrderService.total', 'OrderService.invoiceTotal']
        wrongType.count == 0
        upper.count == 1
        files.count == 2
        files.matches.every { it.detail == 'new File' }
        nulls.matches*.enclosing == ['Handlers.load', 'Handlers.lookup']
        nulls.matches.every { it.detail == 'return null' }
    }

    def 'findCode finds string literals by pattern and annotations by name'() {
        when:
        def secrets = call('findCode', [kind: 'string-literal', pattern: '^sec'])
        def all = call('findCode', [kind: 'string-literal', uris: [uri(REPORT)]])
        def deprecated = call('findCode', [kind: 'annotation', name: 'Deprecated'])
        def suppressed = call('findCode', [kind: 'annotation', name: 'java.lang.SuppressWarnings'])

        then:
        secrets.matches*.detail == ['"secret"']
        secrets.matches[0].enclosing == 'Handlers.lookup'
        all.matches*.detail.toSet() == ['"header"', '"="', '"footer"', '"["', '"]"', '"#"'] as Set
        deprecated.matches*.detail == ['@Deprecated on method total']
        suppressed.matches*.detail == ['@SuppressWarnings on method helper', '@SuppressWarnings on parameter a']
    }

    def 'findCode finds dynamic declarations and empty blocks'() {
        when:
        def dynamic = call('findCode', [kind: 'def'])
        def empty = call('findCode', [kind: 'empty-block'])

        then:
        dynamic.matches*.detail.toSet() == ['def property cache', 'def method guess(def)', 'def parameter input of guess',
                                            'def result'] as Set
        empty.matches*.detail == ['empty catch block', 'empty if block', 'empty method body noop()']
    }

    def 'findCode filters fields and methods by modifiers, length and parameters'() {
        when:
        def constants = call('findCode', [kind: 'field', modifiers: ['static', 'final']])
        def privates = call('findCode', [kind: 'field', modifiers: ['private']])
        def longMethods = call('findCode', [kind: 'method', minLines: 10])
        def wide = call('findCode', [kind: 'method', minParameters: 4])
        def limited = call('findCode', [kind: 'method', limit: 2])

        then:
        constants.matches*.detail == ['field private static final Logger log', 'property public static final String PREFIX']
        privates.matches*.detail == ['field private static final Logger log', 'field private final List<String> names']
        longMethods.matches*.detail == ['method total(List<BigDecimal>, BigDecimal) 12 lines',
                                        'method invoiceTotal(List<BigDecimal>, BigDecimal) 11 lines']
        wide.matches*.detail == ['method helper(int, int, int, int) 4 lines']
        limited.matches.size() == 2
        limited.count > 2
        limited.truncated
    }

    def 'findCode rejects unknown kinds, modifiers and bad patterns'() {
        when:
        call('findCode', params)

        then:
        def e = thrown(ExecutionException)
        e.cause instanceof ResponseErrorException
        (e.cause as ResponseErrorException).responseError.code == ResponseErrorCode.InvalidParams.value
        e.cause.message.contains(message)

        where:
        params                                           | message
        [kind: 'nonsense']                               | 'catch, throw, call'
        [:]                                              | 'unknown kind'
        [kind: 'field', modifiers: ['shiny']]            | 'unknown modifier shiny'
        [kind: 'string-literal', pattern: '(unclosed']   | 'Unclosed group'
    }

    def 'findCode outside the workspace finds nothing'() {
        expect:
        call('findCode', [kind: 'catch', uris: [temp.toUri().toString()]]).count == 0
    }

    def 'duplicates reports exact and similar groups, largest first'() {
        when:
        def found = call('duplicates', [:])
        def report = found.groups.find { it.occurrences[0].uri == uri(REPORT) }
        def orders = found.groups.find { it.occurrences[0].uri == uri(ORDERS) }

        then:
        found.count == 2
        found.groups.size() == 2
        report.similarity == 'exact'
        report.statements == 5
        report.lines == 7
        report.occurrences*.enclosing == ['Report.render', 'Report.renderAgain']
        report.occurrences[0].line == lineOf(REPORT, 'StringBuilder out')
        report.occurrences[0].endLine == lineOf(REPORT, 'return out')
        orders.similarity == 'similar'
        orders.statements == 5
        orders.lines == 9
        orders.occurrences*.enclosing == ['OrderService.total', 'OrderService.invoiceTotal']
        found.groups[0] == orders
    }

    def 'duplicates honours its thresholds and its document filter'() {
        expect:
        call('duplicates', [minLines: 8]).groups*.lines == [9]
        call('duplicates', [minStatements: 6]).count == 0
        call('duplicates', [uris: [uri(REPORT)]]).count == 1
        call('duplicates', [uris: [temp.toUri().toString()]]).count == 0
    }

    def 'overview sizes the workspace and lists untested classes'() {
        when:
        def overview = call('overview', [:])
        int orderLines = Fixtures.text(PROJECT, ORDERS).readLines().size()

        then:
        overview.files == 4
        overview.lines == ['src/main/groovy/sample/OrderService.groovy', REPORT, HANDLERS, 'src/test/groovy/sample/ReportSpec.groovy']
                .sum { Fixtures.text(PROJECT, it).readLines().size() }
        overview.packages == [[name: 'sample', files: 4, classes: 4, lines: overview.lines]]
        overview.largestFiles.size() == 4
        overview.largestFiles[0].lines >= overview.largestFiles[1].lines
        overview.largestFiles.find { it.uri == uri(ORDERS) } == [uri: uri(ORDERS), lines: orderLines, classes: 1, members: 6]
        overview.largestClasses*.name.toSet() == ['sample.OrderService', 'sample.Report', 'sample.Handlers', 'sample.ReportSpec'] as Set
        overview.largestClasses.find { it.name == 'sample.OrderService' }.lines == orderLines - 4
        overview.testFiles == 1
        overview.untested.toSet() == ['sample.OrderService', 'sample.Handlers'] as Set
    }

    def 'overview of an empty workspace is empty'() {
        given:
        Files.createDirectories(temp.resolve('src/main/groovy'))
        def empty = new GroovyscopeLanguageServer({ }, temp, EngineConfig.DEFAULTS)

        when:
        def overview = empty.overview([root: temp.toString()]).get(60, TimeUnit.SECONDS)

        then:
        overview.files == 0
        overview.lines == 0
        overview.packages == []
        overview.untested == []
    }
}
