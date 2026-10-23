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

import spock.lang.Specification

/** The comment lexer: comments of each kind, and the four Groovy string quotes that only look like comments. */
class SourceCommentsSpec extends Specification {

    def 'line, block and Javadoc comments with their lines, own-line flags and stripped text'() {
        given:
        def source = SourceComments.of('''\
// first
int a = 1 // trailing
/* block
 * second line
 */
/** Javadoc. */
/**/ int b = 2
''')

        expect:
        source.comments()*.kind() == [SourceComments.Kind.LINE, SourceComments.Kind.LINE, SourceComments.Kind.BLOCK,
                                      SourceComments.Kind.JAVADOC, SourceComments.Kind.BLOCK]
        source.comments()*.ownLine() == [true, false, true, true, true]
        source.contentAt(0) == 'first'
        source.contentAt(1) == null
        source.commentOn(1).kind() == SourceComments.Kind.LINE
        source.isCode(1) && !source.isCommentOnly(1)
        (2..4).collect { source.contentAt(it) } == ['block', 'second line', '']
        source.contentAt(5) == 'Javadoc.'
        source.isCode(6) && source.commentAt(6) == null
        source.raw(source.comments()[0]) == '// first'
        source.lineCount() == 8
    }

    def 'nothing inside a string of any quote is a comment'() {
        given:
        def source = SourceComments.of('''\
def a = '// single'
def b = "/* double */ ${x.collect { "// nested" }} // inside"
def c = \'\'\'
// triple single
\'\'\'
def d = """
/* triple double ${y}
"""
def e = /\\/\\/ slashy/
def f = $/ // dollar $$ $/ /$
def g = 'escaped \\' // still'
def h = a / b // division then a comment
// real
''')

        expect:
        source.comments().size() == 2
        source.comments()[0].line() == 11
        source.contentAt(12) == 'real'
        (0..12).every { it == 12 || source.isCode(it) }
    }

    def 'regex after an operator or a keyword, a shebang, CRLF and unterminated text'() {
        given:
        def regex = SourceComments.of('def r = ~/a\\/b/\nif (x =~ /c/) { return /d/ }\n// after\n')
        def shebang = SourceComments.of('#!/usr/bin/env groovy\n// note\n')
        def crlf = SourceComments.of('// one\r\nint x\r\n')
        def open = SourceComments.of('def s = "never closed\n/* never closed')
        def empty = SourceComments.of(null)

        expect:
        regex.comments().size() == 1 && regex.contentAt(2) == 'after'
        shebang.isCode(0) && shebang.contentAt(1) == 'note'
        crlf.contentAt(0) == 'one'
        open.comments().size() == 1 && open.comments()[0].kind() == SourceComments.Kind.BLOCK
        empty.comments().isEmpty() && empty.lineCount() == 1
    }
}
