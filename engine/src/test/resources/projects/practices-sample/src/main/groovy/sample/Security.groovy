package sample

import groovy.sql.Sql
import groovy.xml.XmlSlurper

@Grab('org.example:library:1.0')
class Security {
    void run(String script, String branch, Sql sql, String name) {
        Eval.me(script)
        Eval.me('1 + 1')
        new GroovyShell().evaluate(script)
        'git log --oneline'.execute()
        "git log $branch".execute()
        ['git', 'log'].execute()
        sql.rows("SELECT * FROM users WHERE name = '$name'")
        sql.rows("SELECT * FROM users WHERE name = $name")
        new XmlSlurper(false, true, true)
        new XmlSlurper()
    }
}
