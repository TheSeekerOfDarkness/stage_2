package com.example.ingestion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class IngestionTest {

    @TempDir
    Path tempDir;

    private HttpServer mockServer;
    private int mockPort;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockServer.getAddress().getPort();
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void testBookSplitterValid() {
        String raw = "The Project Gutenberg eBook\nTitle: Test Book\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK TEST BOOK ***\n"
                + "Line 1 of body.\nLine 2 of body.\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK TEST BOOK ***\n"
                + "Footer text.";

        BookSplitter.SplitResult result = BookSplitter.split(raw);
        assertTrue(result.header.contains("Title: Test Book"));
        assertEquals("Line 1 of body.\nLine 2 of body.", result.body);
    }

    @Test
    void testBookSplitterMissingStartMarker() {
        String raw = "Header without start marker\n"
                + "Line 1 of body.\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK ***\n";

        assertThrows(IllegalArgumentException.class, () -> BookSplitter.split(raw));
    }

    @Test
    void testBookSplitterMissingEndMarker() {
        String raw = "*** START OF THE PROJECT GUTENBERG EBOOK ***\n"
                + "Line 1 of body without end marker.\n";

        assertThrows(IllegalArgumentException.class, () -> BookSplitter.split(raw));
    }

    @Test
    void testBookSplitterInvertedMarkers() {
        String raw = "*** END OF THE PROJECT GUTENBERG EBOOK ***\n"
                + "Text\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK ***\n";

        assertThrows(IllegalArgumentException.class, () -> BookSplitter.split(raw));
    }

    @Test
    void testBookSplitterNull() {
        assertThrows(IllegalArgumentException.class, () -> BookSplitter.split(null));
    }

    @Test
    void testAtomicSaveBook() throws IOException {
        Path folder = tempDir.resolve("20261105").resolve("18");
        App.saveBook(folder, 101, "Test Header", "Test Body");

        Path headerFile = folder.resolve("101.header.txt");
        Path bodyFile = folder.resolve("101.body.txt");

        assertTrue(Files.exists(headerFile));
        assertTrue(Files.exists(bodyFile));
        assertEquals("Test Header", Files.readString(headerFile));
        assertEquals("Test Body", Files.readString(bodyFile));

        // Ensure temp files do not linger
        assertFalse(Files.exists(folder.resolve("101.header.txt.tmp")));
        assertFalse(Files.exists(folder.resolve("101.body.txt.tmp")));
    }

    @Test
    void testDownloader404NotFound() {
        mockServer.createContext("/book/999", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        String urlTemplate = "http://localhost:" + mockPort + "/book/%d";
        GutenbergDownloader downloader = new GutenbergDownloader(
                urlTemplate,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                3,
                50);

        assertThrows(GutenbergDownloader.BookNotFoundException.class, () -> downloader.download(999));
    }

    @Test
    void testDownloaderRetryAndSucceed() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        String expectedBody = "Success after retry";

        mockServer.createContext("/book/123", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                // First attempt fails with 503 Service Unavailable
                exchange.sendResponseHeaders(503, -1);
            } else {
                // Second attempt succeeds with 200 OK
                byte[] bytes = expectedBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });

        String urlTemplate = "http://localhost:" + mockPort + "/book/%d";
        GutenbergDownloader downloader = new GutenbergDownloader(
                urlTemplate,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                3,
                50);

        String text = downloader.download(123);
        assertEquals(expectedBody, text);
        assertEquals(2, attempts.get());
    }

    @Test
    void testDownloaderRetriesExhausted() {
        mockServer.createContext("/book/500", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        String urlTemplate = "http://localhost:" + mockPort + "/book/%d";
        GutenbergDownloader downloader = new GutenbergDownloader(
                urlTemplate,
                HttpClient.newHttpClient(),
                Duration.ofSeconds(2),
                2,
                30);

        GutenbergDownloader.DownloaderException ex = assertThrows(
                GutenbergDownloader.DownloaderException.class,
                () -> downloader.download(500));
        assertEquals(500, ex.getStatusCode());
    }

    @Test
    void testDownloaderTimeoutHandling() {
        mockServer.createContext("/book/timeout", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        String urlTemplate = "http://localhost:" + mockPort + "/book/timeout";
        GutenbergDownloader downloader = new GutenbergDownloader(
                urlTemplate,
                HttpClient.newHttpClient(),
                Duration.ofMillis(100),
                1,
                10);

        GutenbergDownloader.DownloaderException ex = assertThrows(
                GutenbergDownloader.DownloaderException.class,
                () -> downloader.download(1));
        assertEquals(504, ex.getStatusCode());
    }
}
