package com.example.indexing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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
