package com.example.indexing;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App {
    private static final Gson gson = new Gson();
    private static Indexer indexer;
    private static final boolean autoSyncEnabled = Boolean.parseBoolean(System.getenv().getOrDefault("AUTO_SYNC", "true"));
    private static final int syncIntervalSeconds = Integer.parseInt(System.getenv().getOrDefault("SYNC_INTERVAL_SECONDS", "30"));

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7002"));
        String datalakePath = System.getenv().getOrDefault("DATALAKE_PATH", "datalake");
        String datamartPath = System.getenv().getOrDefault("DATAMART_PATH",
            System.getenv().getOrDefault("DB_PATH", "datamarts/datamart.db"));

        indexer = new Indexer(datalakePath, datamartPath);

        if (autoSyncEnabled) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    Map<String, Object> summary = indexer.updateAll();
                    List<?> indexed = (List<?>) summary.get("indexed_books");
                    if (indexed != null && !indexed.isEmpty()) {
                        System.out.println("[COORDINATOR] Auto-indexed " + indexed.size() + " new books.");
                    }
                } catch (Exception e) {
                    System.err.println("[COORDINATOR] Error auto-indexing: " + e.getMessage());
                }
            }, 5, syncIntervalSeconds, TimeUnit.SECONDS);
        }

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(port);

        app.get("/status", ctx -> {
            Map<String, Object> status = Map.of(
                "service", "indexing-service",
                "status", "running"
            );
            ctx.result(gson.toJson(status));
        });

        app.get("/index/status/{book_id}", App::handleStatus);
        app.post("/index/all", App::handleIndexAll);
        app.post("/index/{book_id}", App::handleIndex);
        app.get("/index/list", App::handleList);
        app.get("/index/book/{book_id}", App::handleBook);
    }

    private static void handleStatus(Context ctx) {
        int bookId = readBookId(ctx);
        if (bookId == -1) return;

        Map<String, Object> response = new HashMap<>();
        response.put("book_id", bookId);

        if (indexer.getMetadata().isIndexed(bookId)) {
            response.put("status", "indexed");
            ctx.result(gson.toJson(response));
        } else {
            ctx.status(404);
            response.put("status", "not_indexed");
            ctx.result(gson.toJson(response));
        }
    }

    private static void handleIndex(Context ctx) {
        int bookId = readBookId(ctx);
        if (bookId == -1) return;

        try {
            boolean success = indexer.update(bookId);
            if (!success) {
                ctx.status(404);
                ctx.result(gson.toJson(Map.of(
                    "book_id", bookId,
                    "status", "not_found",
                    "error", "Book " + bookId + " not found in datalake"
                )));
                return;
            }

            Map<String, Object> response = Map.of(
                "book_id", bookId,
                "status", "indexed"
            );
            ctx.result(gson.toJson(response));
        } catch (Exception e) {
            sendError(ctx, 500, "Error indexing book " + bookId + ": " + e.getMessage());
        }
    }

    private static void handleList(Context ctx) {
        try {
            List<Integer> books = indexer.getMetadata().listIndexedBookIds();
            Map<String, Object> response = Map.of(
                "count", books.size(),
                "books", books
            );
            ctx.result(gson.toJson(response));
        } catch (Exception e) {
            sendError(ctx, 500, "Error listing indexed books: " + e.getMessage());
        }
    }

    private static void handleBook(Context ctx) {
        int bookId = readBookId(ctx);
        if (bookId == -1) return;

        try {
            Book book = indexer.getMetadata().get(bookId);
            if (book == null) {
                sendError(ctx, 404, "Book " + bookId + " is not indexed");
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("book_id", book.bookId);
            response.put("title", book.title);
            response.put("author", book.author);
            response.put("language", book.language);
            response.put("year", book.year);
            response.put("path", book.path);

            ctx.result(gson.toJson(response));
        } catch (Exception e) {
            sendError(ctx, 500, "Error retrieving book " + bookId + ": " + e.getMessage());
        }
    }

    private static void handleIndexAll(Context ctx) {
        try {
            Map<String, Object> summary = indexer.updateAll();
            ctx.result(gson.toJson(summary));
        } catch (Exception e) {
            sendError(ctx, 500, "Error indexing datalake books: " + e.getMessage());
        }
    }

    private static int readBookId(Context ctx) {
        try {
            int bookId = Integer.parseInt(ctx.pathParam("book_id"));
            if (bookId > 0) return bookId;
        } catch (NumberFormatException ignored) {}
        sendError(ctx, 400, "book_id must be a positive integer");
        return -1;
    }

    private static void sendError(Context ctx, int status, String message) {
        ctx.status(status);
        ctx.result(gson.toJson(Map.of("error", message)));
    }
}