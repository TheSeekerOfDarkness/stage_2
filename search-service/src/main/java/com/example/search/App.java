package com.example.search;

import com.google.gson.Gson;
import io.javalin.Javalin;
import java.util.Map;

public class App {
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7003"));

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
    }
}
