package com.example.search;

public class SearchResult {
    public int book_id;
    public String title;
    public String author;
    public String language;
    public int year;
    public String path;

    public SearchResult() {
    }

    public SearchResult(int bookId, String title, String author, String language, int year, String path) {
        this.book_id = bookId;
        this.title = title;
        this.author = author;
        this.language = language;
        this.year = year;
        this.path = path;
    }
}
