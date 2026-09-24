package com.ayush.leetcodecoach.domain;

import java.util.List;

/** Problems whose review is due. See {@link ProblemSearchResult} for why this is not a bare list. */
public record DueReviewList(List<DueReview> reviews, int count) {

    public static DueReviewList of(List<DueReview> reviews) {
        return new DueReviewList(reviews, reviews.size());
    }
}
