package com.example.search;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class App {
    private static final Gson gson = new Gson();
    private static SearchEngine searchEngine;

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7003"));
        String datamartPath = System.getenv().getOrDefault("DATAMART_PATH",
            System.getenv().getOrDefault("DB_PATH", "datamarts/datamart.db"));

        searchEngine = new SearchEngine(datamartPath);

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(port);

        app.get("/status", ctx -> {
            Map<String, Object> status = Map.of(
                "service", "search-service",
                "status", "running"
            );
            ctx.result(gson.toJson(status));
        });

        app.get("/search", App::handleSearch);
    }

    private static void handleSearch(Context ctx) {
        String query = ctx.queryParam("q");
        if (query == null) {
            sendError(ctx, 400, "Query parameter 'q' is required");
            return;
        }

        List<String> tokens = Tokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("tokens", Collections.emptyList());
            response.put("total_matches", 0);
            response.put("results", Collections.emptyList());
            ctx.result(gson.toJson(response));
            return;
        }

        int limit = -1;
        int offset = 0;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException ignored) {
            }
        }
        String offsetParam = ctx.queryParam("offset");
        if (offsetParam != null) {
            try {
                offset = Integer.parseInt(offsetParam);
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            int totalMatches = searchEngine.countMatches(tokens);
            List<SearchResult> results = searchEngine.search(tokens, limit, offset);

            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("tokens", tokens);
            response.put("total_matches", totalMatches);
            response.put("results", results);

            ctx.result(gson.toJson(response));
        } catch (Exception e) {
            sendError(ctx, 500, "Search query error: " + e.getMessage());
        }
    }

    private static void sendError(Context ctx, int status, String message) {
        ctx.status(status);
        ctx.result(gson.toJson(Map.of("error", message)));
    }
}