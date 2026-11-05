package com.example.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "INSERT INTO books VALUES (1, 'Pride and Prejudice', 'Jane Austen', 'en', 1813, 'datalake/1')");
                stmt.execute(
                        "INSERT INTO books VALUES (2, 'Sense and Sensibility', 'Jane Austen', 'en', 1811, 'datalake/2')");
                stmt.execute("INSERT INTO books VALUES (3, 'Frankenstein', 'Mary Shelley', 'en', 1818, 'datalake/3')");
            }

            try (PreparedStatement insert = conn.prepareStatement("INSERT INTO inverted_index VALUES (?, ?)")) {
                for (String t : List.of("novel", "pride", "prejudice", "darcy", "classic")) {
                    insert.setString(1, t);
                    insert.setInt(2, 1);
                    insert.executeUpdate();
                }

                for (String t : List.of("novel", "sense", "sensibility", "dashwood", "classic")) {
                    insert.setString(1, t);
                    insert.setInt(2, 2);
                    insert.executeUpdate();
                }

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
        // Pasamos null a author, language y year
        List<SearchResult> results = engine.search(List.of("darcy"), null, null, null, -1, 0);
        assertEquals(1, results.size());
        assertEquals(1, results.get(0).book_id);
        assertEquals("Pride and Prejudice", results.get(0).title);
        assertEquals("Jane Austen", results.get(0).author);

        int count = engine.countMatches(List.of("darcy"), null, null, null);
        assertEquals(1, count);
    }

    @Test
    void testBooleanAndConjunction() throws SQLException {
        assertEquals(3, engine.countMatches(List.of("novel"), null, null, null));
        assertEquals(3, engine.search(List.of("novel"), null, null, null, -1, 0).size());

        List<SearchResult> results = engine.search(List.of("novel", "darcy"), null, null, null, -1, 0);
        assertEquals(1, results.size());
        assertEquals(1, results.get(0).book_id);

        results = engine.search(List.of("novel", "dashwood"), null, null, null, -1, 0);
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).book_id);

        assertEquals(0, engine.countMatches(List.of("darcy", "dashwood"), null, null, null));
        assertTrue(engine.search(List.of("darcy", "dashwood"), null, null, null, -1, 0).isEmpty());
    }

    @Test
    void testFilters() throws SQLException {
        // Buscar "novel" (3 resultados), pero filtrar por author "Shelley" (debería dar 1)
        List<SearchResult> shelley = engine.search(List.of("novel"), "Shelley", null, null, -1, 0);
        assertEquals(1, shelley.size());
        assertEquals(3, shelley.get(0).book_id);

        // Buscar "classic" (3 resultados), pero filtrar por year 1811 (debería dar 1)
        int matches1811 = engine.countMatches(List.of("classic"), null, null, 1811);
        assertEquals(1, matches1811);
    }

    @Test
    void testEmptyAndAbsentTerms() throws SQLException {
        assertTrue(engine.search(List.of(), null, null, null, -1, 0).isEmpty());
        assertEquals(0, engine.countMatches(List.of(), null, null, null));

        assertTrue(engine.search(List.of("nonexistentword"), null, null, null, -1, 0).isEmpty());
        assertEquals(0, engine.countMatches(List.of("nonexistentword"), null, null, null));
    }

    @Test
    void testPagination() throws SQLException {
        int total = engine.countMatches(List.of("classic"), null, null, null);
        assertEquals(3, total);

        List<SearchResult> page1 = engine.search(List.of("classic"), null, null, null, 2, 0);
        assertEquals(2, page1.size());
        assertEquals(1, page1.get(0).book_id);
        assertEquals(2, page1.get(1).book_id);

        List<SearchResult> page2 = engine.search(List.of("classic"), null, null, null, 2, 2);
        assertEquals(1, page2.size());
        assertEquals(3, page2.get(0).book_id);
    }
}