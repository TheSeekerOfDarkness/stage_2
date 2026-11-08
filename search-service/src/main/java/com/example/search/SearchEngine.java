package com.example.search;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class SearchEngine {
    private final String url;
    private final String dbPath;

    public SearchEngine(String dbPath) {
        this.dbPath = dbPath;
        this.url = "jdbc:sqlite:" + dbPath + "?busy_timeout=60000";
        ensureTablesExist();
    }

    private void ensureTablesExist() {
        File file = new File(dbPath).getAbsoluteFile();
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        try (Connection connection = DriverManager.getConnection(url);
             Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=60000;");
            stmt.execute("CREATE TABLE IF NOT EXISTS books ("
                    + "book_id INTEGER PRIMARY KEY, "
                    + "title TEXT NOT NULL, "
                    + "author TEXT NOT NULL, "
                    + "language TEXT, "
                    + "year INTEGER, "
                    + "datalake_path TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS inverted_index ("
                    + "term TEXT NOT NULL, "
                    + "book_id INTEGER NOT NULL, "
                    + "PRIMARY KEY (term, book_id))");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inverted_book_id ON inverted_index(book_id)");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<SearchResult> search(List<String> terms, String author, String language, Integer year, int limit, int offset) throws SQLException {
        List<String> distinctTerms = new ArrayList<>(new LinkedHashSet<>(terms));
        if (distinctTerms.isEmpty()) {
            return Collections.emptyList();
        }

        int k = distinctTerms.size();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < k; i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("?");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT b.book_id, b.title, b.author, b.language, b.year, b.datalake_path ")
           .append("FROM books b ")
           .append("JOIN (")
           .append("  SELECT book_id FROM inverted_index ")
           .append("  WHERE term IN (").append(placeholders).append(") ")
           .append("  GROUP BY book_id ")
           .append("  HAVING COUNT(DISTINCT term) = ? ")
           .append(") m ON b.book_id = m.book_id ");
           
        if (author != null && !author.isBlank()) {
            sql.append("AND b.author LIKE ? ");
        }
        if (language != null && !language.isBlank()) {
            sql.append("AND b.language = ? ");
        }
        if (year != null) {
            sql.append("AND b.year = ? ");
        }

        sql.append("ORDER BY b.book_id ASC");

        if (limit > 0) {
            sql.append(" LIMIT ? OFFSET ?");
        }

        List<SearchResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            
            int paramIndex = 1;
            for (String term : distinctTerms) {
                stmt.setString(paramIndex++, term);
            }
            stmt.setInt(paramIndex++, k);

            if (author != null && !author.isBlank()) {
                stmt.setString(paramIndex++, "%" + author.trim() + "%");
            }
            if (language != null && !language.isBlank()) {
                stmt.setString(paramIndex++, language.trim());
            }
            if (year != null) {
                stmt.setInt(paramIndex++, year);
            }
            if (limit > 0) {
                stmt.setInt(paramIndex++, limit);
                stmt.setInt(paramIndex++, Math.max(0, offset));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new SearchResult(
                        rs.getInt("book_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("language"),
                        rs.getInt("year"),
                        rs.getString("datalake_path")
                    ));
                }
            }
        }
        return results;
    }

    public int countMatches(List<String> terms, String author, String language, Integer year) throws SQLException {
        List<String> distinctTerms = new ArrayList<>(new LinkedHashSet<>(terms));
        if (distinctTerms.isEmpty()) {
            return 0;
        }

        int k = distinctTerms.size();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < k; i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("?");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM books b JOIN (")
           .append("  SELECT book_id FROM inverted_index ")
           .append("  WHERE term IN (").append(placeholders).append(") ")
           .append("  GROUP BY book_id ")
           .append("  HAVING COUNT(DISTINCT term) = ?")
           .append(") m ON b.book_id = m.book_id ");

        if (author != null && !author.isBlank()) sql.append("AND b.author LIKE ? ");
        if (language != null && !language.isBlank()) sql.append("AND b.language = ? ");
        if (year != null) sql.append("AND b.year = ? ");

        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            
            int paramIndex = 1;
            for (String term : distinctTerms) {
                stmt.setString(paramIndex++, term);
            }
            stmt.setInt(paramIndex++, k);

            if (author != null && !author.isBlank()) stmt.setString(paramIndex++, "%" + author.trim() + "%");
            if (language != null && !language.isBlank()) stmt.setString(paramIndex++, language.trim());
            if (year != null) stmt.setInt(paramIndex++, year);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }
}