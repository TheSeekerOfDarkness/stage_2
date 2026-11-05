package com.example.search;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        }).start(port);

        app.get("/status", ctx -> {
            Map<String, Object> status = Map.of("service", "search-service", "status", "running");
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

        String author = ctx.queryParam("author");
        String language = ctx.queryParam("language");
        Integer year = null;
        String yearParam = ctx.queryParam("year");
        if (yearParam != null && !yearParam.trim().isEmpty()) {
            try {
                year = Integer.parseInt(yearParam);
            } catch (NumberFormatException e) {
                sendError(ctx, 400, "Parameter 'year' must be a valid integer");
                return;
            }
        }

        List<String> tokens = Tokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("total_matches", 0);
            response.put("results", Collections.emptyList());
            ctx.result(gson.toJson(response));
            return;
        }

        int limit = -1;
        int offset = 0;
        if (ctx.queryParam("limit") != null) {
            try { limit = Integer.parseInt(ctx.queryParam("limit")); } catch (NumberFormatException ignored) {}
        }
        if (ctx.queryParam("offset") != null) {
            try { offset = Integer.parseInt(ctx.queryParam("offset")); } catch (NumberFormatException ignored) {}
        }

        try {
            int totalMatches = searchEngine.countMatches(tokens, author, language, year);
            List<SearchResult> results = searchEngine.search(tokens, author, language, year, limit, offset);

            Map<String, Object> appliedFilters = new LinkedHashMap<>();
            if (author != null) appliedFilters.put("author", author);
            if (language != null) appliedFilters.put("language", language);
            if (year != null) appliedFilters.put("year", year);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("filters", appliedFilters);
            response.put("total_matches", totalMatches);
            response.put("offset", offset);
            response.put("limit", limit);
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