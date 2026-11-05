package com.example.ingestion;

public class BookSplitter {
    public static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    public static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

    public static class SplitResult {
        public final String header;
        public final String body;

        public SplitResult(String header, String body) {
            this.header = header;
            this.body = body;
        }
    }

    public static SplitResult split(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Book text cannot be null");
        }

        int start = text.indexOf(START_MARKER);
        int end = text.indexOf(END_MARKER);

        if (start == -1 || end == -1 || end <= start) {
            throw new IllegalArgumentException("Text does not contain valid Project Gutenberg delimiters");
        }

        int newlineAfterStart = text.indexOf("\n", start);
        int bodyStart;
        if (newlineAfterStart != -1 && newlineAfterStart < end) {
            bodyStart = newlineAfterStart + 1;
        } else {
            bodyStart = start + START_MARKER.length();
        }

        if (bodyStart > end) {
            throw new IllegalArgumentException("Delimiters are malformed or body is empty");
        }

        String header = text.substring(0, start).trim();
        String body = text.substring(bodyStart, end).trim();

        return new SplitResult(header, body);
    }
}
