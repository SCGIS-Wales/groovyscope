package evil

import groovy.transform.ASTTest

@ASTTest(value = { new File(System.getProperty('groovyscope.marker')).text = 'asttest ran' })
class UsesAstTest {
}
