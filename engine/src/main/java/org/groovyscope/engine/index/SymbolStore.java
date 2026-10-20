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
package org.groovyscope.engine.index;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.groovyscope.engine.model.Location;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.model.SymbolKind;

/**
 * Declarations persisted in SQLite, keyed by the content hash of their document, so a warm restart can
 * answer symbol searches before the first compile finishes. Only rows whose hash still matches the current
 * text are trusted; everything else is ignored and overwritten by the next save.
 */
public final class SymbolStore implements Closeable {

    /** How long a writer waits for another connection's lock before giving up. */
    static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Connection connection;

    private SymbolStore(Connection connection) {
        this.connection = connection;
    }

    /** Opens (creating when needed) the database at {@code file}. */
    public static SymbolStore open(Path file) throws IOException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            // Readers do not block the writer (WAL), and a second process (a CLI next to the daemon) waits for the
            // lock instead of failing with SQLITE_BUSY: a save takes the write lock when it begins (IMMEDIATE), so it
            // never has to upgrade a read lock, which SQLite refuses at once rather than waiting.
            org.sqlite.SQLiteConfig settings = new org.sqlite.SQLiteConfig();
            settings.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
            settings.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
            settings.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(),
                    settings.toProperties());
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS symbols (
                          uri TEXT NOT NULL, hash TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
                          container TEXT, detail TEXT,
                          start_line INTEGER, start_col INTEGER, end_line INTEGER, end_col INTEGER,
                          sel_start_line INTEGER, sel_start_col INTEGER, sel_end_line INTEGER, sel_end_col INTEGER)""");
                statement.execute("CREATE INDEX IF NOT EXISTS symbols_uri ON symbols(uri)");
            }
            return new SymbolStore(connection);
        } catch (SQLException e) {
            throw new IOException("cannot open symbol store " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Stores {@code symbols} (children are flattened), hashed by document: the rows of documents whose text changed
     * or that are gone are replaced, the rest are left as they are.
     */
    public synchronized void save(Map<String, String> sources, List<Symbol> symbols) throws IOException {
        try {
            connection.setAutoCommit(false);
            Map<String, String> hashes = new HashMap<>();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                hashes.put(source.getKey(), hash(source.getValue()));
            }
            Map<String, String> stored = new HashMap<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT DISTINCT uri, hash FROM symbols")) {
                while (rows.next()) {
                    stored.put(rows.getString(1), rows.getString(2));
                }
            }
            Set<String> stale = new HashSet<>();
            for (Map.Entry<String, String> entry : stored.entrySet()) {
                if (!entry.getValue().equals(hashes.get(entry.getKey()))) {
                    stale.add(entry.getKey());
                }
            }
            Set<String> changed = new HashSet<>(hashes.keySet());
            changed.removeIf(uri -> hashes.get(uri).equals(stored.get(uri)));
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM symbols WHERE uri = ?")) {
                for (String uri : stale) {
                    delete.setString(1, uri);
                    delete.addBatch();
                }
                delete.executeBatch();
            }
            String sql = "INSERT INTO symbols VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (Symbol symbol : symbols) {
                    insertTree(insert, hashes, changed, symbol);
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IOException("cannot save symbols: " + e.getMessage(), e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                // nothing useful to do
            }
        }
    }

    private static void insertTree(PreparedStatement insert, Map<String, String> hashes, Set<String> changed,
            Symbol symbol) throws SQLException {
        if (symbol.location() != null && changed.contains(symbol.location().uri())) {
            String uri = symbol.location().uri();
            insert.setString(1, uri);
            insert.setString(2, hashes.get(uri));
            insert.setString(3, symbol.name());
            insert.setString(4, symbol.kind().name());
            insert.setString(5, symbol.container());
            insert.setString(6, symbol.detail());
            Range range = symbol.location().range();
            insert.setInt(7, range.start().line());
            insert.setInt(8, range.start().column());
            insert.setInt(9, range.end().line());
            insert.setInt(10, range.end().column());
            Range selection = symbol.selectionRange() == null ? range : symbol.selectionRange();
            insert.setInt(11, selection.start().line());
            insert.setInt(12, selection.start().column());
            insert.setInt(13, selection.end().line());
            insert.setInt(14, selection.end().column());
            insert.addBatch();
        }
        for (Symbol child : symbol.children()) {
            insertTree(insert, hashes, changed, child);
        }
    }

    /** Symbols of the documents whose stored hash matches the current text; flat, without children. */
    public synchronized List<Symbol> load(Map<String, String> sources) throws IOException {
        List<Symbol> symbols = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM symbols WHERE uri = ? AND hash = ?")) {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                select.setString(1, source.getKey());
                select.setString(2, hash(source.getValue()));
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        symbols.add(row(rows));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("cannot load symbols: " + e.getMessage(), e);
        }
        return symbols;
    }

    private static Symbol row(ResultSet rows) throws SQLException {
        Range range = Range.of(rows.getInt("start_line"), rows.getInt("start_col"), rows.getInt("end_line"), rows.getInt("end_col"));
        Range selection = Range.of(rows.getInt("sel_start_line"), rows.getInt("sel_start_col"),
                rows.getInt("sel_end_line"), rows.getInt("sel_end_col"));
        SymbolKind kind;
        try {
            kind = SymbolKind.valueOf(rows.getString("kind"));
        } catch (IllegalArgumentException e) {
            kind = SymbolKind.VARIABLE;
        }
        return new Symbol(rows.getString("name"), kind, rows.getString("container"),
                new Location(rows.getString("uri"), range), selection, rows.getString("detail"), List.of());
    }

    /** The journal mode the database runs in ({@code wal} once opened). */
    public synchronized String journalMode() throws IOException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA journal_mode")) {
            return rows.next() ? rows.getString(1).toLowerCase(java.util.Locale.ROOT) : "";
        } catch (SQLException e) {
            throw new IOException("cannot read the journal mode: " + e.getMessage(), e);
        }
    }

    public synchronized long count() throws IOException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM symbols")) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IOException("cannot count symbols: " + e.getMessage(), e);
        }
    }

    static String hash(String text) {
        return org.groovyscope.engine.util.Hashes.sha256Hex(text);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            // nothing useful to do
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IOException("cannot close symbol store: " + e.getMessage(), e);
        }
    }
}
