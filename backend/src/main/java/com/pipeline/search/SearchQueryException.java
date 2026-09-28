package com.pipeline.search;

import java.util.List;

/**
 * A query the recruiter cannot be given results for, with everything needed to tell her
 * why: a code to switch on, a sentence to read, the characters to underline, and the
 * alternatives we could work out. Carries both spans because the normaliser rewrites her
 * sentence before it is parsed, so the offsets the parser knows about are not the offsets
 * her cursor understands.
 */
public class SearchQueryException extends RuntimeException {

    private final ErrorCode code;
    private final Span span;
    private final Span source;
    private final List<String> didYouMean;

    public SearchQueryException(ErrorCode code, String message, Span span, Span source, List<String> didYouMean) {
        super(message);
        this.code = code;
        this.span = span;
        this.source = source;
        this.didYouMean = List.copyOf(didYouMean);
    }

    public SearchQueryException(ErrorCode code, String message, Span span, Span source) {
        this(code, message, span, source, List.of());
    }

    public ErrorCode code() {
        return code;
    }

    /** Into the normalised DSL. */
    public Span span() {
        return span;
    }

    /** Into the raw query, which is the one the input box is showing. */
    public Span source() {
        return source;
    }

    public List<String> didYouMean() {
        return didYouMean;
    }
}
