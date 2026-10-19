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

import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.model.Diagnostic
import org.groovyscope.engine.model.Range
import spock.lang.Specification

class BasicFormatterSpec extends Specification {

    static final EngineConfig.Format SETTINGS = EngineConfig.Format.DEFAULTS

    static Diagnostic semicolon(int line) {
        new Diagnostic('file:///x', Range.of(line, 0, line, 1), Diagnostic.Severity.WARNING, 'codenarc', 'UnnecessarySemicolon', 'x')
    }

    def 'whitespace hygiene: tabs, trailing spaces, blank runs, final newline'() {
        expect:
        BasicFormatter.format('\tclass A {  \n\n\n\n\t\tint x\n}', SETTINGS, []) == '    class A {\n\n        int x\n}\n'
        BasicFormatter.format('', SETTINGS, []) == ''
        BasicFormatter.format('a\r\nb\r\n', SETTINGS, []) == 'a\r\nb\r\n'
        BasicFormatter.format('a\n\n\n', new EngineConfig.Format('basic', 2, 0, true), []) == 'a\n'
    }

    def 'semicolons go only where CodeNarc flagged them'() {
        expect:
        BasicFormatter.format('int a = 1;\nint b = 2;\n', SETTINGS, [semicolon(0)]) == 'int a = 1\nint b = 2;\n'
        BasicFormatter.format('int a = 1;\n', new EngineConfig.Format('basic', 4, 1, false), [semicolon(0)]) == 'int a = 1;\n'
        BasicFormatter.removeTrailingSemicolon('x; // c') == 'x; // c'
        BasicFormatter.removeTrailingSemicolon('x;  ') == 'x'
    }

    def 'triple-quoted string contents are left alone'() {
        given:
        String text = 'def s = """\n  keep   \n\ttabs\n"""\nint x = 1   \n'

        expect:
        BasicFormatter.format(text, SETTINGS, []) == 'def s = """\n  keep   \n\ttabs\n"""\nint x = 1\n'
        BasicFormatter.protectedSpans("x = '''a'''\n// \"\"\" comment\n")*.toList() == [[4, 10]]
    }

    def 'edits are a single whole-document replacement, or nothing'() {
        expect:
        BasicFormatter.edits('clean\n', SETTINGS, []).empty
        BasicFormatter.edits('dirty  \n', SETTINGS, []).size() == 1
        BasicFormatter.edits('dirty  \n', SETTINGS, [])[0].newText() == 'dirty\n'
        BasicFormatter.edits('dirty  \n', SETTINGS, [])[0].range() == Range.of(0, 0, 1, 0)
    }
}
