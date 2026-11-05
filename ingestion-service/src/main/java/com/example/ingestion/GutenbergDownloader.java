package com.example.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class GutenbergDownloader {
    public static final String DEFAULT_URL_TEMPLATE = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";
    public static final String DEFAULT_USER_AGENT = "ULPGC-BigData-Bot/1.0 (Educational Search Engine)";

    private final String urlTemplate;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final long backoffBaseMs;

    public static class BookNotFoundException extends Exception {
        public BookNotFoundException(String message) {
            super(message);
        }
    }

    public static class DownloaderException extends Exception {
        private final int statusCode;

        public DownloaderException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public DownloaderException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    public GutenbergDownloader() {
        this(
            System.getenv().getOrDefault("GUTENBERG_URL_TEMPLATE", DEFAULT_URL_TEMPLATE),
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
            Duration.ofSeconds(15),
            3,
            300
        );
    }

    public GutenbergDownloader(String urlTemplate, HttpClient client, Duration requestTimeout, int maxRetries, long backoffBaseMs) {
        this.urlTemplate = urlTemplate;
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.backoffBaseMs = backoffBaseMs;
    }

    public String download(int bookId) throws BookNotFoundException, DownloaderException {
        String url;
        if (urlTemplate.contains("%d") && urlTemplate.indexOf("%d") != urlTemplate.lastIndexOf("%d")) {
            url = String.format(urlTemplate, bookId, bookId);
        } else if (urlTemplate.contains("%d")) {
            url = String.format(urlTemplate, bookId);
        } else {
            url = urlTemplate + "/" + bookId;
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .build();

        Exception lastException = null;
        int lastStatusCode = 0;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();

                if (status == 200) {
                    return response.body();
                }

                if (status == 404) {
                    throw new BookNotFoundException("Book " + bookId + " not found on Project Gutenberg (HTTP 404)");
                }

                lastStatusCode = status;
                lastException = new IOException("Server responded with HTTP " + status);

                // For 4xx errors other than 429, don't retry
                if (status >= 400 && status < 500 && status != 429) {
                    break;
                }

            } catch (BookNotFoundException e) {
                throw e;
            } catch (HttpTimeoutException e) {
                lastStatusCode = 504;
                lastException = e;
            } catch (IOException | InterruptedException e) {
                lastStatusCode = 502;
                lastException = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (attempt < maxRetries && backoffBaseMs > 0) {
                try {
                    Thread.sleep(backoffBaseMs * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String msg = "Failed to download book " + bookId + " after " + maxRetries + " attempts: "
            + (lastException != null ? lastException.getMessage() : "HTTP " + lastStatusCode);
        throw new DownloaderException(msg, lastStatusCode != 0 ? lastStatusCode : 502, lastException);
    }
}
