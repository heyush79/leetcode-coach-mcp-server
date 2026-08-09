package com.ayush.leetcodecoach.domain;

import java.util.List;

public record HintResponse(String sessionId, int level, List<String> hints, String guardrail) {
}
