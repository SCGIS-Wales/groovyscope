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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

    private final Connection connection;

    private SymbolStore(Connection connection) {
        this.connection = connection;
    }

    /** Opens (creating when needed) the database at {@code file}. */
    public static SymbolStore open(Path file) throws IOException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
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

    /** Replaces the stored symbols with {@code symbols} (children are flattened), hashed by document. */
    public synchronized void save(Map<String, String> sources, List<Symbol> symbols) throws IOException {
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM symbols");
            }
            String sql = "INSERT INTO symbols VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (Symbol symbol : symbols) {
                    insertTree(insert, sources, symbol);
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

    private static void insertTree(PreparedStatement insert, Map<String, String> sources, Symbol symbol)
            throws SQLException {
        if (symbol.location() != null && sources.containsKey(symbol.location().uri())) {
            String uri = symbol.location().uri();
            insert.setString(1, uri);
            insert.setString(2, hash(sources.get(uri)));
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
            insertTree(insert, sources, child);
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

    public synchronized long count() throws IOException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM symbols")) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IOException("cannot count symbols: " + e.getMessage(), e);
        }
    }

    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
