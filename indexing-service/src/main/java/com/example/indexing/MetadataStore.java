package com.example.indexing;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class MetadataStore {
    private final String url;

    public MetadataStore(String dbPath) {
        File folder = new File(dbPath).getAbsoluteFile().getParentFile();
        if (folder != null) {
            folder.mkdirs();
        }
        this.url = "jdbc:sqlite:" + dbPath;
        createTable();
    }

    private void createTable() {
        String sql = "CREATE TABLE IF NOT EXISTS books ("
                + "book_id INTEGER PRIMARY KEY, "
                + "title TEXT NOT NULL, "
                + "author TEXT NOT NULL, "
                + "language TEXT, "
                + "year INTEGER, "
                + "datalake_path TEXT)";

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA busy_timeout=60000;");
            statement.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void save(Connection connection, Book book) throws SQLException {
        String sql = "INSERT OR REPLACE INTO books (book_id, title, author, language, year, datalake_path) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, book.bookId);
            statement.setString(2, book.title);
            statement.setString(3, book.author);
            statement.setString(4, book.language);
            statement.setInt(5, book.year);
            statement.setString(6, book.path);
            statement.executeUpdate();
        }
    }

    public Book get(int bookId) {
        String sql = "SELECT * FROM books WHERE book_id = ?";

        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, bookId);
            ResultSet result = statement.executeQuery();

            if (!result.next()) {
                return null;
            }

            Book book = new Book();
            book.bookId = result.getInt("book_id");
            book.title = result.getString("title");
            book.author = result.getString("author");
            book.language = result.getString("language");
            book.year = result.getInt("year");
            book.path = result.getString("datalake_path");
            return book;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isIndexed(int bookId) {
        String sql = "SELECT 1 FROM books WHERE book_id = ?";
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, bookId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Integer> listIndexedBookIds() {
        List<Integer> list = new ArrayList<>();
        String sql = "SELECT book_id FROM books ORDER BY book_id ASC";
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                list.add(result.getInt(1));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM books";
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (result.next()) {
                return result.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
