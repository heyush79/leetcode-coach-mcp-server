package com.ayush.leetcodecoach.domain;

public enum Difficulty {
    EASY,
    MEDIUM,
    HARD,
    UNKNOWN;

    public static Difficulty from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return Difficulty.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
