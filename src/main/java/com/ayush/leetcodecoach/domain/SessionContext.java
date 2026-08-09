package com.ayush.leetcodecoach.domain;

import java.util.List;

public record SessionContext(PracticeSession session, Problem problem, List<Attempt> attempts) {
}
