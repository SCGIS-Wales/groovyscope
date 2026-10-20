package demo

class Sql {
    static Sql newInstance(String url) {
        new Sql()
    }

    List rows(String query) {
        []
    }
}

class Ledger {
    List rows(String query) {
        []
    }
}

class Dao {
    List load(String table, other) {
        def sql = Sql.newInstance('jdbc:h2:mem:')
        new Ledger().rows('ignored')
        other.rows('unknown')
        sql.rows("SELECT * FROM ${table}")
    }
}
