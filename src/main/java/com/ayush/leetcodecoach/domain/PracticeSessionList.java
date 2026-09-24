package com.ayush.leetcodecoach.domain;

import java.util.List;

/** Recent practice sessions. See {@link ProblemSearchResult} for why this is not a bare list. */
public record PracticeSessionList(List<PracticeSession> sessions, int count) {

    public static PracticeSessionList of(List<PracticeSession> sessions) {
        return new PracticeSessionList(sessions, sessions.size());
    }
}
