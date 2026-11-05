package com.example.indexing;

import org.openjdk.jmh.annotations.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IndexerBenchmark {

    private Indexer indexer;
    private Path tempDir;
    private String datamartPath;

    @Setup(Level.Trial)
    public void setUp() throws IOException, SQLException {
        tempDir = Files.createTempDirectory("benchmark_indexing");
        datamartPath = tempDir.resolve("datamart.db").toString();
        String datalakePath = "datalake"; 
        
        indexer = new Indexer(datalakePath, datamartPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + datamartPath);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM books");
            stmt.execute("DELETE FROM inverted_index");
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
    public void benchmarkIndexSingleBook() throws IOException, SQLException {
        indexer.update(1342); 
    }
}