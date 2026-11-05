package com.example.search;

import java.util.ArrayList;
import java.util.List;

public class Tokenizer {
    public static List<String> tokenize(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }

        StringBuilder word = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                word.append(Character.toLowerCase(c));
            } else if (word.length() > 0) {
                words.add(word.toString());
                word.setLength(0);
            }
        }

        if (word.length() > 0) {
            words.add(word.toString());
        }

        return words;
    }
}
