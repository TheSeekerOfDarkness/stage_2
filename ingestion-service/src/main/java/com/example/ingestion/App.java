package com.example.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class App {
    private static final Gson gson = new Gson();
    private static final String datalakePath = System.getenv().getOrDefault("DATALAKE_PATH", "datalake");
    private static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    private static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7001"));

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(port);

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
    }

    private static void handleList(Context ctx) {
        List<Integer> books = listBooks();

        Map<String, Object> response = Map.of(
            "count", books.size(),
            "books", books
        );

        ctx.result(gson.toJson(response));
    }

    private static void handleStatus(Context ctx) {
        int bookId = readBookId(ctx);
        if (bookId == -1) {
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("book_id", bookId);

        if (findBodyFile(bookId) != null) {
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

        File existing = findBodyFile(bookId);
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
            text = downloadBook(bookId);
        } catch (Exception e) {
            sendError(ctx, 502, "Could not download book " + bookId + " from Project Gutenberg");
            return;
        }

        int start = text.indexOf(START_MARKER);
        int end = text.indexOf(END_MARKER);
        int bodyStart = text.indexOf("\n", start) + 1;
        if (start == -1 || end == -1 || end < start || bodyStart > end) {
            sendError(ctx, 422, "Book " + bookId + " does not contain the Project Gutenberg markers");
            return;
        }

        String header = text.substring(0, start).trim();
        String body = text.substring(bodyStart, end).trim();

        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String hour = now.format(DateTimeFormatter.ofPattern("HH"));
        Path folder = Paths.get(datalakePath, date, hour);

        try {
            saveBook(folder, bookId, header, body);
        } catch (IOException e) {
            sendError(ctx, 500, "Could not save book " + bookId);
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("book_id", bookId);
        response.put("status", "downloaded");
        response.put("path", datalakePath.replace("\\", "/") + "/" + date + "/" + hour + "/" + bookId);
        ctx.result(gson.toJson(response));
    }

    private static String downloadBook(int bookId) throws Exception {
        String url = "https://www.gutenberg.org/cache/epub/" + bookId + "/pg" + bookId + ".txt";

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "ULPGC-BigData-Bot/1.0 (Educational Search Engine)")
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Project Gutenberg returned " + response.statusCode());
        }

        return response.body();
    }

    private static void saveBook(Path folder, int bookId, String header, String body) throws IOException {
        Files.createDirectories(folder);

        Path headerTemp = folder.resolve(bookId + ".header.txt.tmp");
        Path bodyTemp = folder.resolve(bookId + ".body.txt.tmp");
        Files.writeString(headerTemp, header, StandardCharsets.UTF_8);
        Files.writeString(bodyTemp, body, StandardCharsets.UTF_8);

        Files.move(headerTemp, folder.resolve(bookId + ".header.txt"));
        Files.move(bodyTemp, folder.resolve(bookId + ".body.txt"));
    }

    private static int readBookId(Context ctx) {
        try {
            int bookId = Integer.parseInt(ctx.pathParam("book_id"));
            if (bookId > 0) {
                return bookId;
            }
        } catch (NumberFormatException e) {
        }

        sendError(ctx, 400, "book_id must be a positive integer");
        return -1;
    }

    private static void sendError(Context ctx, int status, String message) {
        ctx.status(status);
        ctx.result(gson.toJson(Map.of("error", message)));
    }

    private static List<Integer> listBooks() {
        List<Integer> books = new ArrayList<>();

        for (File bodyFile : listBodyFiles()) {
            String name = bodyFile.getName();
            String id = name.substring(0, name.length() - ".body.txt".length());
            books.add(Integer.parseInt(id));
        }

        Collections.sort(books);
        return books;
    }

    private static File findBodyFile(int bookId) {
        for (File bodyFile : listBodyFiles()) {
            if (bodyFile.getName().equals(bookId + ".body.txt")) {
                return bodyFile;
            }
        }
        return null;
    }

    private static List<File> listBodyFiles() {
        List<File> bodyFiles = new ArrayList<>();
        File[] dateDirs = new File(datalakePath).listFiles();
        if (dateDirs == null) {
            return bodyFiles;
        }

        for (File dateDir : dateDirs) {
            File[] hourDirs = dateDir.listFiles();
            if (!dateDir.isDirectory() || hourDirs == null) {
                continue;
            }
            for (File hourDir : hourDirs) {
                File[] files = hourDir.listFiles();
                if (!hourDir.isDirectory() || files == null) {
                    continue;
                }
                for (File file : files) {
                    if (file.getName().endsWith(".body.txt")) {
                        bodyFiles.add(file);
                    }
                }
            }
        }

        return bodyFiles;
    }
}
