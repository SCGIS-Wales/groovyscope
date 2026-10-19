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
package org.groovyscope.engine.practices

import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.control.SourceUnit
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.index.TextLines
import org.groovyscope.engine.services.LanguageServices
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** The string quick fixes change quoting, never a value (1.3), and their result parses (1.6). */
class StringFixSpec extends Specification {

    static final String URI = 'file:///probe/src/main/groovy/app/Strings.groovy'

    @Shared
    LanguageServices services = Fixtures.services('basic-app')

    private static ModuleNode module(String text) {
        new GroovyCompiler().compile([(URI): text], [], false).modules()[URI]
    }

    /** Every string constant outside a GString, and the GStrings' text, in source order. */
    private static List<String> values(String text) {
        ModuleNode tree = module(text)
        List<String> found = []
        def visitor = new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                tree.context
            }

            @Override
            void visitConstantExpression(ConstantExpression constant) {
                if (constant.value instanceof String) {
                    found << (String) constant.value
                }
            }

            @Override
            void visitGStringExpression(GStringExpression expression) {
                found << ('GString:' + expression.text)
            }
        }
        tree.classes.each { visitor.visitClass(it) }
        tree.statementBlock.visit(visitor)
        found
    }

    private String fixAll(String text, String code) {
        TextLines lines = new TextLines(text)
        def edits = services.quality().lint(URI, text, 'default').findAll { it.code() == code }
                .collectMany { PracticeFixes.lintFixes(it, lines, module(text)) }
                .collectMany { it.edits() }
        PracticeFixes.apply(text, edits).text()
    }

    def 'only the plain double-quoted strings are requoted, and every value stays the same'() {
        given:
        String text = '''\
String a = 'x'
def s = "$a" + "b"
def t = "x" + "y"
def u = "it's" + "z"
def v = "tab\\there\\\\back"
def w = """triple"""
'''

        when:
        String fixed = fixAll(text, 'UnnecessaryGString')

        then:
        fixed.contains('''def s = "$a" + 'b\'''')
        fixed.contains("def t = 'x' + 'y'")
        fixed.contains("def u = 'it\\'s' + 'z'")
        fixed.contains("def v = 'tab\\there\\\\back'")
        values(fixed) == values(text)
        PracticeFixes.singleQuoted('a\u0001\r\n\b\f') == "'a\\u0001\\r\\n\\b\\f'"
    }

    def 'a GString map key becomes a parenthesised toString() call that parses'() {
        given:
        String text = '''\
String k = 'a'
def map = ["${k}": 1]
'''

        when:
        String fixed = fixAll(text, 'GStringAsMapKey')
        ModuleNode tree = module(fixed)
        def keys = []
        tree.statementBlock.visit(new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                tree.context
            }

            @Override
            void visitMapEntryExpression(MapEntryExpression entry) {
                keys << entry.keyExpression.text
            }
        })

        then:
        fixed.contains('def map = [("${k}".toString()): 1]')
        new GroovyCompiler().parses(URI, fixed)
        keys.size() == 1 && keys[0].contains('toString')
    }
}
