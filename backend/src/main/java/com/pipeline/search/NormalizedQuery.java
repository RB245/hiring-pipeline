package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;

/**
 * The canonical DSL the rest of the pipeline parses, plus the map back to what the
 * recruiter typed.
 *
 * <p>The mapping is exact wherever the normaliser copied her text through untouched, which
 * is every character of an already-DSL query and every bare term. Where a rewrite fired,
 * an offset maps to the whole phrase the rule matched: "for more than a week" became
 * {@code in_stage_for:>7d}, and there is no honest character-level correspondence between
 * the two. That is deliberately coarse, and it costs nothing in practice because a rewrite
 * emits canonical text that validates by construction — every error the recruiter can
 * actually provoke lives in text she typed herself, and maps back character-exact.
 */
public record NormalizedQuery(String raw, String text, List<Piece> pieces) {

    /**
     * One run of output characters and where it came from. {@code rewritten} pieces have no
     * internal correspondence; the others map one to one, offset by the difference between
     * the two starts.
     */
    record Piece(int outStart, int outEnd, int sourceStart, int sourceEnd, boolean rewritten) {}

    public NormalizedQuery {
        pieces = List.copyOf(pieces);
    }

    /** Where in the raw query a span of the normalised DSL came from. */
    public Span sourceSpan(Span span) {
        List<Span> hits = new ArrayList<>();
        for (Piece piece : pieces) {
            if (overlaps(piece, span)) {
                hits.add(sourceOf(piece, span));
            }
        }
        if (hits.isEmpty()) {
            return nearest(span);
        }
        return hits.stream().reduce(Span::to).orElseThrow();
    }

    private static boolean overlaps(Piece piece, Span span) {
        // An empty span still belongs to the piece it sits inside, so the second test is
        // not a redundant special case: MISSING_VALUE points at a position, not a range.
        return span.start() < piece.outEnd() && span.end() > piece.outStart()
                || span.start() == span.end() && span.start() >= piece.outStart() && span.start() <= piece.outEnd();
    }

    private static Span sourceOf(Piece piece, Span span) {
        if (piece.rewritten()) {
            return new Span(piece.sourceStart(), piece.sourceEnd());
        }
        int width = piece.outEnd() - piece.outStart();
        int from = Math.max(0, span.start() - piece.outStart());
        int to = Math.min(width, span.end() - piece.outStart());
        return new Span(piece.sourceStart() + from, piece.sourceStart() + Math.max(from, to));
    }

    /**
     * Nothing overlapped, which happens when the query normalised away to nothing or the
     * span sits past the last piece. Point at the end of what she typed rather than at
     * offset zero, which would underline the wrong end of the box.
     */
    private Span nearest(Span span) {
        if (pieces.isEmpty()) {
            return new Span(0, raw.length());
        }
        Piece last = pieces.get(pieces.size() - 1);
        return span.start() >= last.outEnd()
                ? new Span(last.sourceEnd(), last.sourceEnd())
                : new Span(pieces.get(0).sourceStart(), pieces.get(0).sourceStart());
    }
}
