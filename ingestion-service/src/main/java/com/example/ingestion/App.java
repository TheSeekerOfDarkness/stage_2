package com.example.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class App {
    private static final Gson gson = new Gson();
    private static final String datalakePath = System.getenv().getOrDefault("DATALAKE_PATH", "datalake");

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
    }

    private static void handleList(Context ctx) {
        List<Integer> books = listBooks();

        Map<String, Object> response = Map.of(
            "count", books.size(),
            "books", books
        );

        ctx.result(gson.toJson(response));
    }

    private static List<Integer> listBooks() {
        List<Integer> books = new ArrayList<>();
        File[] dateDirs = new File(datalakePath).listFiles();
        if (dateDirs == null) {
            return books;
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
                    String name = file.getName();
                    if (name.endsWith(".body.txt")) {
                        String id = name.substring(0, name.length() - ".body.txt".length());
                        books.add(Integer.parseInt(id));
                    }
                }
            }
        }

        Collections.sort(books);
        return books;
    }
}
