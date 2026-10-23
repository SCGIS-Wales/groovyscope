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

import java.nio.file.Path
import java.nio.file.Paths
import org.groovyscope.engine.compile.GroovyCompiler
import org.groovyscope.engine.config.EngineConfig
import org.groovyscope.engine.support.Fixtures
import spock.lang.Shared
import spock.lang.Specification

/** GS044 to GS048: tests kept lean, on test sources only. */
class TestBloatChecksSpec extends Specification {

    static final List<String> CODES = ['GS044', 'GS045', 'GS046', 'GS047', 'GS048']
    static final String SPEC = 'src/test/groovy/demo/SubjectSpec.groovy'
    static final String TEST = 'file:///w/src/test/groovy/app/ShopSpec.groovy'
    static final String MAIN = 'file:///w/src/main/groovy/app/Shop.groovy'
    static final String JUNIT = 'file:///w/src/test/groovy/org/junit/jupiter/api/Test.groovy'
    static final String SHOP = '''\
package app

class Shop {
    String region
    int total(int price) {
        region == 'uk' ? price + price.intdiv(5) : price + 1
    }
}
'''

    /** Spock on the classpath, so a specification resolves and is compiled with the classes it tests. */
    static final Path SPOCK = Paths.get(Specification.protectionDomain.codeSource.location.toURI())

    @Shared
    GroovyCompiler compiler = new GroovyCompiler()

    private List<PracticeFinder.Practice> bloat(Map<String, String> texts, String uri = TEST,
            EngineConfig config = EngineConfig.DEFAULTS) {
        def module = compiler.compile(texts, [SPOCK], false).modules()[uri]
        assert module != null
        PracticeFinder.find(uri, module, texts[uri], config).findAll { it.diagnostic().code() in CODES }
    }

    private List<PracticeFinder.Practice> spec(String text, EngineConfig config = EngineConfig.DEFAULTS) {
        bloat([(MAIN): SHOP, (TEST): text], TEST, config)
    }

    private static List<Integer> lines(List<PracticeFinder.Practice> found, String code) {
        found.findAll { it.diagnostic().code() == code }.collect { it.diagnostic().range().start().line() + 1 }
    }

    private static String message(List<PracticeFinder.Practice> found, String code) {
        found.find { it.diagnostic().code() == code }?.diagnostic()?.message()
    }

    private static EngineConfig with(EngineConfig.Tests tests) {
        EngineConfig.DEFAULTS.toBuilder().practices(new EngineConfig.Practices(true, [:], EngineConfig.Naming.DEFAULTS,
                [], EngineConfig.Comments.DEFAULTS, EngineConfig.Reuse.DEFAULTS, tests)).build()
    }

    def "the edit gate fixture's oversized spec reports GS044, GS046 and GS047, its trivial test and its vague names"() {
        given:
        String uri = Fixtures.uri('edit-gate', SPEC)

        when:
        def found = bloat([(uri): Fixtures.text('edit-gate', SPEC)], uri)

        then:
        lines(found, 'GS044') == [12, 17, 22, 35]
        message(found, 'GS044').endsWith('the same steps as test1 (line 7), literals aside: merge into one data driven '
                + 'test (`where:` table or `@ParameterizedTest`)')
        found.findAll { it.diagnostic().code() == 'GS044' }.every { it.fixable() && !it.preserving() }
        lines(found, 'GS045') == [27]
        message(found, 'GS045').contains('the test only checks that new Subject() does not throw')
        lines(found, 'GS046') == [5]
        message(found, 'GS046').contains('SubjectSpec has 39 lines for the 8 lines of Subject (4.9 times, at most 3.0) '
                + 'and 6 test methods (at most 40)')
        lines(found, 'GS047') == [22]
        message(found, 'GS047').contains("calls square(2) with the same arguments, and asserts the same, as test1 (line 7)")
        lines(found, 'GS048') == [7, 35]
        message(found, 'GS048').contains('the name test1 says no behaviour')
    }

    def 'the integration test of the same subject is in proportion, well named and reports nothing'() {
        given:
        String relative = 'src/it/groovy/demo/SubjectIT.groovy'
        String uri = Fixtures.uri('edit-gate', relative)

        expect:
        bloat([(uri): Fixtures.text('edit-gate', relative)], uri).isEmpty()
    }

    def "in the workspace compile, GS044's fix names the columns after the parameter and the result, one row per case"() {
        given:
        def services = Fixtures.services('edit-gate', EngineConfig.DEFAULTS.toBuilder().classpath([SPOCK.toString()]).build())
        services.snapshot()
        String uri = Fixtures.uri('edit-gate', SPEC)

        when:
        def found = services.quality().practices(uri).findAll { it.diagnostic().code() in CODES }
        String fixed = PracticeFixes.apply(Fixtures.text('edit-gate', SPEC),
                found.find { it.diagnostic().code() == 'GS044' }.fix()).text()

        then:
        lines(found, 'GS046') == [5]
        fixed == '''\
package demo

import spock.lang.Specification

class SubjectSpec extends Specification {

    def test1() {
        expect:
        new Subject().square(value) == expected

        where:
        value | expected
        2     | 4
        3     | 9
        4     | 16
        5     | 25
    }

    def 'creates a subject'() {
        when:
        new Subject()

        then:
        noExceptionThrown()
    }
}
'''
        compiler.parse('SubjectSpec.groovy', fixed).ok()

        cleanup:
        services.close()
    }

    def 'GS044: the varying literals become columns named after the property, the parameter and the result'() {
        given:
        String text = '''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    def 'charges tax on a book'() {
        given: 'a shop'
        Shop shop = new Shop(region: 'uk')

        expect:
        shop.total(10) == 12
    }

    def 'charges tax on a pen'() {
        given: 'another shop'
        Shop shop = new Shop(region: 'fr')

        expect:
        shop.total(2) == 3
    }
}
'''

        when:
        def found = spec(text)

        then:
        lines(found, 'GS044') == [15]
        lines(found, 'GS047') == []
        PracticeFixes.apply(text, found[0].fix()).text() == '''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    def 'charges tax on a book'() {
        given: 'a shop'
        Shop shop = new Shop(region: region)

        expect:
        shop.total(price) == expected

        where:
        region | price | expected
        'uk'   | 10    | 12
        'fr'   | 2     | 3
    }
}
'''
    }

    def 'GS044: one varying literal becomes a data pipe, named after what it is given to and clear of the fields'() {
        given:
        String text = '''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    String region = 'uk'

    def 'charges more than the bare price outside the uk'() {
        given:
        Shop shop = new Shop()
        shop.region = 'fr'

        expect:
        12 != shop.total(10)
    }

    def 'charges more than the bare price in germany'() {
        given:
        Shop shop = new Shop()
        shop.region = 'de'

        expect:
        12 != shop.total(10)
    }
}
'''

        when:
        def found = spec(text)

        then:
        lines(found, 'GS044') == [18]
        lines(found, 'GS045') == []
        PracticeFixes.apply(text, found.find { it.diagnostic().code() == 'GS044' }.fix()).text() == '''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    String region = 'uk'

    def 'charges more than the bare price outside the uk'() {
        given:
        Shop shop = new Shop()
        shop.region = region2

        expect:
        12 != shop.total(10)

        where:
        region2 << ['fr', 'de']
    }
}
'''
    }

    def 'GS044: no fix when a comment sits above a copy, a literal spans lines, or the tests are JUnit tests'() {
        when:
        def commented = spec('''\
package app

class ShopSpec extends spock.lang.Specification {

    def 'charges tax in the uk'() {
        expect:
        new Shop(region: 'uk').total(10) == 12
    }
    // the french rate
    def 'charges tax in france'() {
        expect:
        new Shop(region: 'fr').total(10) == 11
    }

    def 'reads a long name'() {
        expect:
        new Shop(region: \'\'\'a
b\'\'\').region.size() == 3
    }

    def 'reads a short name'() {
        expect:
        new Shop(region: 'ab').region.size() == 2
    }
}
''')
        def junit = bloat([(JUNIT): 'package org.junit.jupiter.api\n@interface Test {}\n', (MAIN): SHOP, (TEST): '''\
package app

import org.junit.jupiter.api.Test

class ShopTest {

    @Test
    void chargesUkTaxOnTen() {
        assert new Shop(region: 'uk').total(10) == 12
    }

    @Test
    void chargesUkTaxOnTwenty() {
        assert new Shop(region: 'uk').total(20) == 24
    }
}
'''])

        then:
        lines(commented, 'GS044') == [10, 21]
        commented.findAll { it.diagnostic().code() == 'GS044' }.every { !it.fixable() }
        lines(junit, 'GS044') == [13]
        !junit[0].fixable()
        junit.size() == 1
    }

    def 'GS044: a data driven feature and tests of other shapes are no repeats'() {
        expect:
        bloat([(TEST): '''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    def 'charges tax by region'() {
        expect:
        new Shop(region: region).total(10) == expected

        where:
        region | expected
        'uk'   | 12
        'fr'   | 11
    }

    def 'charges one more outside the uk'() {
        given:
        def shop = new Shop(region: 'fr')

        expect:
        shop.total(1) == 2
        shop.region == 'fr'
    }
}
''']).isEmpty()
    }

    def 'GS045: a constructor that does not throw, a getter echoing a named argument, a setter or a constructor parameter'() {
        when:
        def found = bloat([(JUNIT): 'package org.junit.jupiter.api\n@interface Test {}\n', (MAIN): SHOP,
                'file:///w/src/main/groovy/app/Point.groovy': '''\
package app

class Point {
    final int x
    final int y

    Point(int x, int y) {
        this.x = x
        this.y = y
    }

    int getManhattan() { Math.abs(x) + Math.abs(y) }
}
''', (TEST): '''\
package app

import org.junit.jupiter.api.Test

class ShopTest {

    @Test
    void keepsTheRegionItIsGiven() {
        assert new Shop(region: 'uk').region == 'uk'
    }

    @Test
    void storesTheRegionItIsSet() {
        def shop = new Shop()
        shop.setRegion('fr')
        assertEquals('fr', shop.getRegion())
    }

    @Test
    void keepsTheCoordinates() {
        def point = new Point(1, 2)
        assert point.x == 1
        assert point.y == 2
    }

    @Test
    void createsAShopSafely() {
        assertDoesNotThrow { new Shop() }
    }

    @Test
    void createsAShopAndLooksNoFurther() {
        new Shop(region: 'uk')
    }

    @Test
    void addsTheCoordinatesForTheManhattanDistance() {
        assert new Point(1, -2).manhattan == 3
    }

    @Test
    void chargesTheRegionalRateItWasGiven() {
        def shop = new Shop(region: 'uk')
        assert shop.region == 'uk'
        assert shop.total(10) == 12
    }
}
'''])

        then:
        lines(found, 'GS045') == [8, 13, 20, 27, 32]
        found.findAll { it.diagnostic().code() == 'GS045' }*.diagnostic()*.message()*.replaceAll(/.*: the test/, 'the test')
                *.replaceAll(/;.*/, '') == [
                'the test only checks that region returns what the constructor or a setter was given',
                'the test only checks that region returns what the constructor or a setter was given',
                'the test only checks that x returns what the constructor or a setter was given',
                'the test only checks that new Shop() does not throw',
                'the test creates Shop and asserts nothing']
    }

    def 'GS045: a constructed object checked for null or truth, an echo through a local, and a JUnit 3 test'() {
        when:
        def found = spec('''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    def 'builds a shop'() {
        given:
        def shop = new Shop()

        expect:
        shop != null
        shop
    }

    def 'keeps the region it was set to'() {
        given:
        def region = 'uk'
        def shop = new Shop()
        shop.region = region

        expect:
        shop.region == region
    }
}
''')
        def legacy = bloat([(MAIN): SHOP, 'file:///w/src/test/groovy/app/ShopTests.groovy': '''\
package app

class ShopTests extends junit.framework.TestCase {

    void testTotal() {
        def shop = new Shop()
        assertNotNull(shop)
    }

    void helper() {
    }
}
'''], 'file:///w/src/test/groovy/app/ShopTests.groovy')

        then:
        lines(found, 'GS045') == [7, 16]
        message(found, 'GS045').contains('the test only checks that new Shop() does not throw')
        found.findAll { it.diagnostic().code() == 'GS045' }[1].diagnostic().message()
                .contains('the test only checks that region returns what the constructor or a setter was given')
        lines(legacy, 'GS045') == [5]
        lines(legacy, 'GS048') == [5]
    }

    def 'GS046: a test class over the ratio or the method count, the class under test from the same compile'() {
        given:
        String text = '''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    def 'charges a fifth more in the uk'() {
        expect:
        new Shop(region: 'uk').total(10) == 12
    }

    def 'charges one more elsewhere'() {
        given:
        def shop = new Shop(region: 'fr')

        expect:
        shop.total(10) == 11
    }
}
'''

        expect: 'a proportionate spec: 19 lines for 8'
        spec(text).isEmpty()

        when:
        def strict = spec(text, with(new EngineConfig.Tests(1.5, 1, null, null)))
        def small = bloat([(MAIN): 'package app\nclass Shop { int total(int price) { price } }\n', (TEST): text])

        then:
        lines(strict, 'GS046') == [5]
        message(strict, 'GS046').contains('ShopSpec has 19 lines for the 8 lines of Shop (2.4 times, at most 1.5) and 2 '
                + 'test methods (at most 1)')
        message(small, 'GS046').contains('ShopSpec has 19 lines for the 2 lines of Shop (9.5 times, at most 3.0)')
        message(bloat([(MAIN): SHOP, 'file:///w/src/test/groovy/app/web/ShopSpec.groovy':
                text.replace('package app\n', 'package app.web\n\nimport app.Shop\n')],
                'file:///w/src/test/groovy/app/web/ShopSpec.groovy', with(new EngineConfig.Tests(1.5, 40, null, null))),
                'GS046').contains('ShopSpec has 21 lines for the 8 lines of Shop')
    }

    def 'GS046: nothing when the class under test is not found, or the class is not named after one'() {
        given:
        String text = 'package app\nclass ShopSpec extends spock.lang.Specification {\n    def \'charges a fifth more in the uk\'() {\n'
        String tooBig = text + '        expect:\n        true\n    }\n' + ('\n' * 40) + '}\n'

        expect:
        bloat([(TEST): tooBig]).isEmpty()
        bloat([(MAIN): SHOP, 'file:///w/src/test/groovy/app/Checkout.groovy': tooBig.replace('ShopSpec', 'Checkout')],
                'file:///w/src/test/groovy/app/Checkout.groovy').isEmpty()
        TestSize.subjectOf('ShopSpec') == 'Shop'
        TestSize.subjectOf('ShopTests') == 'Shop'
        TestSize.subjectOf('ShopITCase') == 'Shop'
        TestSize.subjectOf('ShopIntegrationTest') == 'Shop'
        TestSize.subjectOf('Spec') == null
        TestSize.lineCount('') == 0
        TestSize.lineCount('a\nb') == 2
        TestSize.lineCount('a\nb\n') == 2
    }

    def 'GS047: the same call and assertion with other setup, or another expectation, is a new case'() {
        when:
        def found = spec('''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    def 'charges a fifth more in the uk'() {
        given:
        Shop shop = new Shop(region: 'uk')

        expect:
        shop.total(10) == 12
    }

    def 'charges one more in france'() {
        given:
        Shop shop = new Shop(region: 'fr')

        expect:
        shop.total(10) == 11
    }

    def 'charges the uk rate on a second visit'() {
        given:
        Shop shop = new Shop(region: 'uk')

        expect: 'the same total'
        shop.total(10) == 12
    }
}
''')

        then:
        lines(found, 'GS044') == [15, 23]
        lines(found, 'GS047') == [23]
        message(found, 'GS047').contains("calls total(10) with the same arguments, and asserts the same, as 'charges a "
                + "fifth more in the uk' (line 7)")
    }

    def 'GS048: names that say no behaviour or miss the pattern, and names that say what is expected'() {
        given:
        String text = '''\
package app

import spock.lang.Specification

class ShopSpec extends Specification {

    def testTotal() {
        expect:
        new Shop(region: 'uk').total(10) == 12
    }

    def 'works fine'() {
        expect:
        new Shop(region: 'fr').total(1) == 2
    }

    def chargesTax() {
        expect:
        new Shop(region: 'fr').total(2) == 3
    }

    def 'a uk shop charges a fifth more'() {
        expect:
        new Shop(region: 'uk').total(5) == 6
    }

    def shouldChargeOneMoreOutsideTheUk() {
        expect:
        new Shop(region: 'fr').total(3) == 4
    }
}
'''

        when:
        def found = spec(text).findAll { it.diagnostic().code() == 'GS048' }
        def custom = spec(text, with(new EngineConfig.Tests(3.0, 40, '^should[A-Z].*', ['test'])))
                .findAll { it.diagnostic().code() == 'GS048' }
        def broken = spec(text, with(new EngineConfig.Tests(3.0, 40, '(', null)))
                .findAll { it.diagnostic().code() == 'GS048' }

        then:
        lines(found, 'GS048') == [7, 12]  // chargesTax names a behaviour in two words
        found[0].diagnostic().message().contains('the name testTotal says no behaviour')
        found[1].diagnostic().message().contains("the name 'works fine' says no behaviour")
        lines(custom, 'GS048') == [7, 12, 17, 22]
        custom[1].diagnostic().message().endsWith("the name 'works fine' does not match tests.namePattern: name the "
                + 'behaviour and what is expected')
        lines(broken, 'GS048') == [7, 12]
    }

    def 'tests in main sources are not checked, and a main class named like a test is not taken for one'() {
        given:
        String repeated = '''\
package app

class Pricing {
    def test1() { assert new Shop(region: 'uk').total(10) == 12 }
    def test2() { assert new Shop(region: 'uk').total(10) == 12 }
}
'''

        expect:
        bloat([(MAIN): SHOP, 'file:///w/src/main/groovy/app/Pricing.groovy': repeated],
                'file:///w/src/main/groovy/app/Pricing.groovy').isEmpty()
    }
}
