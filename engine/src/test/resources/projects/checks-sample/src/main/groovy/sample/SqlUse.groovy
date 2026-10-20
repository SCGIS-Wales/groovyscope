package sample

import groovy.sql.Sql

class SqlUse {
    List rowsByConcatenation(Sql sql, String name) {
        sql.rows("SELECT * FROM users WHERE name = '" + name + "'") // GS035
    }

    List rowsByToString(Sql sql, String name) {
        sql.rows("SELECT * FROM users WHERE name = '${name}'".toString()) // GS035
    }

    List rowsByVariable(Sql sql, String name) {
        String query = "SELECT * FROM users WHERE name = '${name}'"
        sql.rows(query) // GS035
    }

    List rowsBound(Sql sql, String name) {
        sql.rows("SELECT * FROM users WHERE name = $name")
    }

    List rowsFromConstants(Sql sql) {
        sql.rows('SELECT * FROM users ' + 'ORDER BY name')
    }

    List rowsLike(Sql sql, String part) {
        sql.rows("SELECT * FROM users WHERE name LIKE '%${part}%'") // GS012
    }

    List leaks(String url) {
        Sql sql = Sql.newInstance(url) // GS036
        sql.rows('SELECT 1')
    }

    List closes(String url) {
        Sql sql = Sql.newInstance(url)
        try {
            return sql.rows('SELECT 1')
        } finally {
            sql.close()
        }
    }

    List scoped(String url) {
        List found = []
        Sql.withInstance(url) { Sql sql -> found = sql.rows('SELECT 1') }
        found
    }

    Sql opens(String url) {
        Sql.newInstance(url)
    }

    List chained(String url) {
        Sql.newInstance(url).rows('SELECT 1') // GS036
    }
}
