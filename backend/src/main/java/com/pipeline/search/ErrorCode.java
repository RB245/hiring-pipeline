package com.pipeline.search;

/**
 * Machine-readable reasons a query was rejected. The recruiter sees the message, the UI
 * switches on the code, and the span tells it what to underline.
 */
public enum ErrorCode {
    EMPTY_QUERY,
    UNKNOWN_FIELD,
    UNKNOWN_STAGE,
    UNKNOWN_STATUS,
    UNSUPPORTED_OPERATOR,
    MISSING_VALUE,
    BAD_DURATION,
    BAD_DATE,
    UNATTACHED_MODIFIER,
    UNCLOSED_GROUP,
    UNCLOSED_QUOTE,
    UNEXPECTED_TOKEN,
    QUERY_TOO_LONG,
    TOO_MANY_PREDICATES,
    TOO_DEEPLY_NESTED,
    QUERY_TOO_COMPLEX;

    /** The tail of the Problem Details type URI, in the kebab-case the rest of the API uses. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
