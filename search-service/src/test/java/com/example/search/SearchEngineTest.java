package com.example.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SearchEngineTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private SearchEngine engine;

    @BeforeEach
    void setUp() throws SQLException {
        dbPath = tempDir.resolve("datamart.db");
        engine = new SearchEngine(dbPath.toString());

        // Populate sample data in books and inverted_index
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO books VALUES (1, 'Pride and Prejudice', 'Jane Austen', 'en', 1813, 'datalake/1')");
                stmt.execute("INSERT INTO books VALUES (2, 'Sense and Sensibility', 'Jane Austen', 'en', 1811, 'datalake/2')");
                stmt.execute("INSERT INTO books VALUES (3, 'Frankenstein', 'Mary Shelley', 'en', 1818, 'datalake/3')");
            }

            try (PreparedStatement insert = conn.prepareStatement("INSERT INTO inverted_index VALUES (?, ?)")) {
                // Book 1: novel, pride, prejudice, darcy, classic
                for (String t : List.of("novel", "pride", "prejudice", "darcy", "classic")) {
                    insert.setString(1, t);
                    insert.setInt(2, 1);
                    insert.executeUpdate();
                }

                // Book 2: novel, sense, sensibility, dashwood, classic
                for (String t : List.of("novel", "sense", "sensibility", "dashwood", "classic")) {
                    insert.setString(1, t);
                    insert.setInt(2, 2);
                    insert.executeUpdate();
                }

                // Book 3: novel, monster, frankenstein, classic
                for (String t : List.of("novel", "monster", "frankenstein", "classic")) {
                    insert.setString(1, t);
                    insert.setInt(2, 3);
                    insert.executeUpdate();
                }
            }
        }
    }

    @Test
    void testTokenizer() {
        List<String> tokens = Tokenizer.tokenize("Hello, World! 123");
        assertEquals(List.of("hello", "world", "123"), tokens);

        assertTrue(Tokenizer.tokenize("").isEmpty());
        assertTrue(Tokenizer.tokenize("   !!! ??? ").isEmpty());
    }

    @Test
    void testSingleTermSearch() throws SQLException {
        List<SearchResult> results = engine.search(List.of("darcy"), -1, 0);
        assertEquals(1, results.size());
        assertEquals(1, results.get(0).book_id);
        assertEquals("Pride and Prejudice", results.get(0).title);
        assertEquals("Jane Austen", results.get(0).author);

        int count = engine.countMatches(List.of("darcy"));
        assertEquals(1, count);
    }

    @Test
    void testBooleanAndConjunction() throws SQLException {
        // "novel" is in all 3 books
        assertEquals(3, engine.countMatches(List.of("novel")));
        assertEquals(3, engine.search(List.of("novel"), -1, 0).size());

        // "novel" AND "darcy" is only in book 1
        List<SearchResult> results = engine.search(List.of("novel", "darcy"), -1, 0);
        assertEquals(1, results.size());
        assertEquals(1, results.get(0).book_id);

        // "novel" AND "dashwood" is only in book 2
        results = engine.search(List.of("novel", "dashwood"), -1, 0);
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).book_id);

        // "darcy" AND "dashwood" -> 0 matches
        assertEquals(0, engine.countMatches(List.of("darcy", "dashwood")));
        assertTrue(engine.search(List.of("darcy", "dashwood"), -1, 0).isEmpty());
    }

    @Test
    void testEmptyAndAbsentTerms() throws SQLException {
        assertTrue(engine.search(List.of(), -1, 0).isEmpty());
        assertEquals(0, engine.countMatches(List.of()));

        assertTrue(engine.search(List.of("nonexistentword"), -1, 0).isEmpty());
        assertEquals(0, engine.countMatches(List.of("nonexistentword")));
    }

    @Test
    void testPagination() throws SQLException {
        // "classic" matches all 3 books
        int total = engine.countMatches(List.of("classic"));
        assertEquals(3, total);

        // Page 1: limit 2, offset 0
        List<SearchResult> page1 = engine.search(List.of("classic"), 2, 0);
        assertEquals(2, page1.size());
        assertEquals(1, page1.get(0).book_id);
        assertEquals(2, page1.get(1).book_id);

        // Page 2: limit 2, offset 2
        List<SearchResult> page2 = engine.search(List.of("classic"), 2, 2);
        assertEquals(1, page2.size());
        assertEquals(3, page2.get(0).book_id);
    }
}
