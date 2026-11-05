package com.example.indexing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class IndexerTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private Path datalakePath;

    @BeforeEach
    void setUp() throws IOException {
        dbPath = tempDir.resolve("datamart.db");
        datalakePath = tempDir.resolve("datalake");
        Files.createDirectories(datalakePath);
    }

    @Test
    void testHeaderParser() {
        String header = "The Project Gutenberg eBook of Pride and Prejudice\n"
            + "Title: Pride and Prejudice\n"
            + "   and Other Stories\n"
            + "Author: Jane Austen\n"
            + "Language: English\n"
            + "Release date: November 12, 1998 [eBook #1342]\n"
            + "*** START OF THE PROJECT GUTENBERG EBOOK ***\n";

        Book book = HeaderParser.parse(1342, header, "datalake/20260916/13/1342");

        assertEquals(1342, book.bookId);
        assertEquals("Pride and Prejudice and Other Stories", book.title);
        assertEquals("Jane Austen", book.author);
        assertEquals("en", book.language);
        assertEquals(1998, book.year);
        assertEquals("datalake/20260916/13/1342", book.path);
    }

    @Test
    void testTokenizer() {
        String text = "It is a truth universally acknowledged, that a single man in possession of a good fortune...";
        List<String> tokens = Tokenizer.tokenize(text);

        assertFalse(tokens.isEmpty());
        assertEquals("it", tokens.get(0));
        assertEquals("is", tokens.get(1));
        assertEquals("acknowledged", tokens.get(5));
        assertTrue(tokens.contains("universally"));
        assertTrue(tokens.contains("fortune"));
    }

    @Test
    void testMetadataStore() throws SQLException {
        MetadataStore store = new MetadataStore(dbPath.toString());

        Book book = new Book();
        book.bookId = 42;
        book.title = "The Hitchhiker's Guide to the Galaxy";
        book.author = "Douglas Adams";
        book.language = "en";
        book.year = 1979;
        book.path = "datalake/20261105/17/42";

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            store.save(conn, book);
        }

        assertTrue(store.isIndexed(42));
        assertFalse(store.isIndexed(99));
        assertEquals(1, store.count());
        assertEquals(List.of(42), store.listIndexedBookIds());

        Book fetched = store.get(42);
        assertNotNull(fetched);
        assertEquals("The Hitchhiker's Guide to the Galaxy", fetched.title);
        assertEquals("Douglas Adams", fetched.author);
        assertEquals(1979, fetched.year);
    }

    @Test
    void testInvertedIndex() throws SQLException {
        InvertedIndex index = new InvertedIndex(dbPath.toString());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            index.add(conn, 1, "hello world search engine");
            index.add(conn, 2, "hello database query");
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM inverted_index WHERE term = 'hello'")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1)); // Present in book 1 and book 2
        }
    }

    @Test
    void testIndexerEndToEnd() throws Exception {
        Path hourFolder = datalakePath.resolve("20261105").resolve("10");
        Files.createDirectories(hourFolder);

        String sampleHeader = "Title: Moby Dick\nAuthor: Herman Melville\nLanguage: English\nRelease date: 2001\n";
        String sampleBody = "Call me Ishmael. Some years ago never mind how long precisely...";

        Files.writeString(hourFolder.resolve("2701.header.txt"), sampleHeader);
        Files.writeString(hourFolder.resolve("2701.body.txt"), sampleBody);

        Indexer indexer = new Indexer(datalakePath.toString(), dbPath.toString());

        // Missing book should return false
        assertFalse(indexer.update(9999));

        // Existing book should index successfully
        assertTrue(indexer.update(2701));
        assertTrue(indexer.getMetadata().isIndexed(2701));

        Book book = indexer.getMetadata().get(2701);
        assertNotNull(book);
        assertEquals("Moby Dick", book.title);
        assertEquals("Herman Melville", book.author);

        // Verify terms are in inverted_index
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM inverted_index WHERE book_id = 2701")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) > 0);
        }

        // Test updateAll
        Map<String, Object> summary = indexer.updateAll();
        assertEquals(1, summary.get("total_datalake_books"));
        assertEquals(1, summary.get("indexed_count"));
    }
}
