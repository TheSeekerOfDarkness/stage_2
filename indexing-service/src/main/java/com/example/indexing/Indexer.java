package com.example.indexing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Indexer {
    private final String datalakePath;
    private final String url;
    private final MetadataStore metadata;
    private final InvertedIndex index;

    public Indexer(String datalakePath, String dbPath) {
        this.datalakePath = datalakePath;
        this.url = "jdbc:sqlite:" + dbPath + "?busy_timeout=60000";
        this.metadata = new MetadataStore(dbPath);
        this.index = new InvertedIndex(dbPath);
    }

    public MetadataStore getMetadata() {
        return metadata;
    }

    public InvertedIndex getInvertedIndex() {
        return index;
    }

    public boolean update(int bookId) throws IOException, SQLException {
        File bodyFile = findBodyFile(bookId);
        if (bodyFile == null) {
            return false;
        }

        String folder = bodyFile.getParent();
        String header = Files.readString(Paths.get(folder, bookId + ".header.txt"), StandardCharsets.UTF_8);
        String body = Files.readString(bodyFile.toPath(), StandardCharsets.UTF_8);
        Book book = HeaderParser.parse(bookId, header, folder.replace("\\", "/") + "/" + bookId);

        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                metadata.save(connection, book);
                index.add(connection, bookId, body);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }

        return true;
    }

    public List<Integer> listDatalakeBooks() {
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
                    if (file.getName().endsWith(".body.txt")) {
                        String name = file.getName();
                        String id = name.substring(0, name.length() - ".body.txt".length());
                        try {
                            books.add(Integer.parseInt(id));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        Collections.sort(books);
        return books;
    }

    public Map<String, Object> updateAll() {
        List<Integer> datalakeBooks = listDatalakeBooks();
        List<Integer> newlyIndexed = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();

        for (int bookId : datalakeBooks) {
            try {
                if (update(bookId)) {
                    newlyIndexed.add(bookId);
                } else {
                    failed.add(bookId);
                }
            } catch (Exception e) {
                failed.add(bookId);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total_datalake_books", datalakeBooks.size());
        result.put("indexed_count", newlyIndexed.size());
        result.put("indexed_books", newlyIndexed);
        result.put("failed_count", failed.size());
        result.put("failed_books", failed);
        return result;
    }

    private File findBodyFile(int bookId) {
        File[] dateDirs = new File(datalakePath).listFiles();
        if (dateDirs == null) {
            return null;
        }

        for (File dateDir : dateDirs) {
            File[] hourDirs = dateDir.listFiles();
            if (!dateDir.isDirectory() || hourDirs == null) {
                continue;
            }
            for (File hourDir : hourDirs) {
                File bodyFile = new File(hourDir, bookId + ".body.txt");
                if (hourDir.isDirectory() && bodyFile.exists()) {
                    return bodyFile;
                }
            }
        }

        return null;
    }
}
