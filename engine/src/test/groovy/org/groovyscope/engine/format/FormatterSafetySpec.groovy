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
package org.groovyscope.engine.format

import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.control.SourceUnit
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import spock.lang.Specification

/** The formatter never changes what a string holds, keeps line endings, and is idempotent (1.8). */
class FormatterSafetySpec extends Specification {

    static final EngineConfig.Format SETTINGS = EngineConfig.DEFAULTS.format()
    static final String URI = 'file:///w/Strings.groovy'

    // Every multi-line string kind with trailing blanks, tabs and blank runs inside it, a block comment with a triple
    // quote in it, and a line comment holding one.
    static final String STRINGS = 'class Strings {\n' +
            '\tdef single = \'\'\'one  \n\n\n\n\ttwo\t\n\'\'\'  \n' +
            '\tdef interpolated = """a ${1 + 1}  \n\n\n\n  b"""\n' +
            '\tdef dollar = $/x  \n\n\n\n y/$\n' +
            '\t/* not a """ string\n\n\n\n   still a comment */\n' +
            '\t// a """ in a line comment  \n' +
            '\tdef after = \'tail\'   \n' +
            '}\n'

    private static List<String> constants(String text) {
        def module = new GroovyCompiler().compile([(URI): text], [], false).modules()[URI]
        List<String> found = []
        def visitor = new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                module.context
            }

            @Override
            void visitConstantExpression(ConstantExpression constant) {
                if (constant.value instanceof String) {
                    found << (String) constant.value
                }
            }
        }
        module.classes.each { visitor.visitClass(it) }
        found
    }

    def 'nothing inside a string changes, and code around it is still formatted'() {
        when:
        String formatted = BasicFormatter.format(STRINGS, SETTINGS, [])

        then:
        constants(formatted) == constants(STRINGS)
        formatted.contains("one  \n\n\n\n\ttwo\t\n'''")
        formatted.contains('a ${1 + 1}  \n\n\n\n  b"""')
        formatted.contains('$/x  \n\n\n\n y/$')
        formatted.contains('/* not a """ string\n\n\n\n   still a comment */')
        formatted.contains("    def after = 'tail'\n")
        formatted.contains('    // a """ in a line comment\n')
        !formatted.contains("'''  \n")
    }

    def 'CRLF line endings are kept, inside strings too'() {
        given:
        String crlf = "class A {\r\n\tString s = '''x  \r\n y'''   \r\n\r\n\r\n\r\n}\r\n"

        when:
        String formatted = BasicFormatter.format(crlf, SETTINGS, [])

        then:
        formatted == "class A {\r\n    String s = '''x  \r\n y'''\r\n\r\n}\r\n"
        BasicFormatter.lineEndings('a\r\nb\nc') == ['\r\n', '\n', '']
    }

    def 'text that does not lex is left exactly as it is'() {
        given:
        String broken = "class A {\n\tdef s = '''never closed   \n\n\n\n"

        expect:
        BasicFormatter.formattedLines(broken, SETTINGS, []) == broken.split('\n', -1).toList().dropRight(1)
    }

    def 'formatting is idempotent and keeps every string constant, over many whitespace variants'() {
        given:
        def random = new Random(42)
        def fragments = [STRINGS, 'class B {\n  int x = 1;  \n\n\n  String s = "plain"\t\n}\n',
                         'def a = """\n\tkeep\t\n"""\n\n\n\nprintln a  \n']

        expect:
        (1..60).every {
            String base = fragments[random.nextInt(fragments.size())]
            String noisy = base.readLines().collect { line ->
                String padded = random.nextBoolean() ? line + ' ' * random.nextInt(3) : line
                random.nextInt(4) == 0 ? padded + '\n' : padded
            }.join(random.nextBoolean() ? '\n' : '\r\n') + '\n'
            String once = BasicFormatter.format(noisy, SETTINGS, [])
            String twice = BasicFormatter.format(once, SETTINGS, [])
            twice == once && constants(once) == constants(noisy)
        }
    }
}
