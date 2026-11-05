package com.example.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class App {
    private static final Gson gson = new Gson();
    private static DatalakeStore datalakeStore = new DatalakeStore(
            System.getenv().getOrDefault("DATALAKE_PATH", "datalake"));
    private static GutenbergDownloader downloader = new GutenbergDownloader();

    public static void setDownloader(GutenbergDownloader customDownloader) {
        downloader = customDownloader;
    }


    public static void setDatalakeStore(DatalakeStore customStore) {
        datalakeStore = customStore;
    }

    public static Javalin createApp() {
        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        });

        app.get("/status", ctx -> {
            Map<String, Object> status = Map.of(
                "service", "ingestion-service",
                "status", "running"
            );
            ctx.result(gson.toJson(status));
        });

        app.get("/ingest/list", App::handleList);
        app.get("/ingest/status/{book_id}", App::handleStatus);
        app.post("/ingest/{book_id}", App::handleIngest);

        return app;
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7001"));
        String path = System.getenv().getOrDefault("DATALAKE_PATH", "datalake");
        datalakeStore = new DatalakeStore(path);
        createApp().start(port);
    }

    private static void handleList(Context ctx) {
        List<Integer> books = datalakeStore.listBooks();

        Map<String, Object> response = Map.of(
                "count", books.size(),
                "books", books);

        ctx.result(gson.toJson(response));
    }

    private static void handleStatus(Context ctx) {
        int bookId = readBookId(ctx);
        if (bookId == -1) {
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("book_id", bookId);

        if (datalakeStore.isAvailable(bookId)) {
            response.put("status", "available");
        } else {
            ctx.status(404);
            response.put("status", "not_found");
        }

        ctx.result(gson.toJson(response));
    }

    private static void handleIngest(Context ctx) {
        int bookId = readBookId(ctx);
        if (bookId == -1) {
            return;
        }

        File existing = datalakeStore.findBodyFile(bookId);
        if (existing != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("book_id", bookId);
            response.put("status", "already_available");
            response.put("path", existing.getParent().replace("\\", "/") + "/" + bookId);
            ctx.result(gson.toJson(response));
            return;
        }

        String text;
        try {
            text = downloader.download(bookId);
        } catch (GutenbergDownloader.BookNotFoundException e) {
            sendError(ctx, 404, e.getMessage());
            return;
        } catch (GutenbergDownloader.DownloaderException e) {
            int code = e.getStatusCode() >= 400 && e.getStatusCode() < 600 ? e.getStatusCode() : 502;
            sendError(ctx, code, e.getMessage());
            return;
        } catch (Exception e) {
            sendError(ctx, 502, "Could not download book " + bookId + " from Project Gutenberg: " + e.getMessage());
            return;
        }

        BookSplitter.SplitResult split;
        try {
            split = BookSplitter.split(text);
        } catch (IllegalArgumentException e) {
            sendError(ctx, 422, "Book " + bookId + " does not contain valid Gutenberg markers: " + e.getMessage());
            return;
        }

        String savedPath;
        try {
            savedPath = datalakeStore.save(bookId, split.header, split.body);
        } catch (IOException e) {
            sendError(ctx, 500, "Could not save book " + bookId + ": " + e.getMessage());
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("book_id", bookId);
        response.put("status", "downloaded");
        response.put("path", savedPath);
        ctx.result(gson.toJson(response));
    }

    private static int readBookId(Context ctx) {
        try {
            int bookId = Integer.parseInt(ctx.pathParam("book_id"));
            if (bookId > 0) {
                return bookId;
            }
        } catch (NumberFormatException ignored) {
        }

        sendError(ctx, 400, "book_id must be a positive integer");
        return -1;
    }

    private static void sendError(Context ctx, int status, String message) {
        ctx.status(status);
        ctx.result(gson.toJson(Map.of("error", message)));
    }
}