package com.example.search;

import org.openjdk.jmh.annotations.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SearchBenchmark {

    private SearchEngine engine;
    private Path tempDir;

    @Setup(Level.Trial)
    public void setUp() throws IOException, SQLException {
        tempDir = Files.createTempDirectory("benchmark_search");
        String datamartPath = tempDir.resolve("datamart.db").toString();
        engine = new SearchEngine(datamartPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + datamartPath)) {
            try (Statement stmt = conn.createStatement()) {
                for (int i = 1; i <= 1000; i++) {
                    stmt.execute("INSERT INTO books VALUES (" + i + ", 'Title " + i + "', 'Author', 'en', 2000, 'path')");
                }
            }
            try (PreparedStatement insert = conn.prepareStatement("INSERT INTO inverted_index VALUES (?, ?)")) {
                for (int i = 1; i <= 1000; i++) {
                    insert.setString(1, "testterm");
                    insert.setInt(2, i);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    }

    @Benchmark
    public void benchmarkSearchQuery() throws SQLException {
        engine.search(List.of("testterm"), null, null, null, 20, 0);
    }
}