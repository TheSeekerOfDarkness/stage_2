package com.example.indexing;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

public class InvertedIndex {
    private final String url;

    public InvertedIndex(String dbPath) {
        File folder = new File(dbPath).getAbsoluteFile().getParentFile();
        folder.mkdirs();
        this.url = "jdbc:sqlite:" + dbPath;
        createTable();
    }

    private void createTable() {
        String sql = "CREATE TABLE IF NOT EXISTS inverted_index ("
            + "term TEXT NOT NULL, "
            + "book_id INTEGER NOT NULL, "
            + "PRIMARY KEY (term, book_id))";

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void add(Connection connection, int bookId, String text) throws SQLException {
        Set<String> terms = new HashSet<>(Tokenizer.tokenize(text));

        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM inverted_index WHERE book_id = ?")) {
            delete.setInt(1, bookId);
            delete.executeUpdate();
        }

        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO inverted_index (term, book_id) VALUES (?, ?)")) {
            for (String term : terms) {
                insert.setString(1, term);
                insert.setInt(2, bookId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
