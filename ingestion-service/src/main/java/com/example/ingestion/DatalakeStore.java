package com.example.ingestion;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatalakeStore {
    private final String datalakePath;

    public DatalakeStore(String datalakePath) {
        this.datalakePath = datalakePath;
    }

    public String getDatalakePath() {
        return datalakePath;
    }

    public boolean isAvailable(int bookId) {
        return findBodyFile(bookId) != null;
    }

    public File findBodyFile(int bookId) {
        for (File bodyFile : listBodyFiles()) {
            if (bodyFile.getName().equals(bookId + ".body.txt")) {
                return bodyFile;
            }
        }
        return null;
    }

    public List<Integer> listBooks() {
        List<Integer> books = new ArrayList<>();
        for (File bodyFile : listBodyFiles()) {
            String name = bodyFile.getName();
            String id = name.substring(0, name.length() - ".body.txt".length());
            try {
                books.add(Integer.parseInt(id));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(books);
        return books;
    }

    public List<File> listBodyFiles() {
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

    public String save(int bookId, String header, String body) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String hour = now.format(DateTimeFormatter.ofPattern("HH"));
        Path folder = Paths.get(datalakePath, date, hour);

        saveToFolder(folder, bookId, header, body);

        return datalakePath.replace("\\", "/") + "/" + date + "/" + hour + "/" + bookId;
    }

    public static void saveToFolder(Path folder, int bookId, String header, String body) throws IOException {
        Files.createDirectories(folder);

        Path headerTemp = folder.resolve(bookId + ".header.txt.tmp");
        Path bodyTemp = folder.resolve(bookId + ".body.txt.tmp");
        Files.writeString(headerTemp, header, StandardCharsets.UTF_8);
        Files.writeString(bodyTemp, body, StandardCharsets.UTF_8);

        Files.move(headerTemp, folder.resolve(bookId + ".header.txt"));
        Files.move(bodyTemp, folder.resolve(bookId + ".body.txt"));
    }
}
