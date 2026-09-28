package com.pipeline.application;

import com.pipeline.search.SearchQuery;
import com.pipeline.search.SearchQueryParser;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parse, run, and — when it found nobody — work out what to say instead of nothing.
 *
 * <p>An empty result set is the failure this use case exists to soften. A query that
 * parsed, ran, and matched no one is indistinguishable to the recruiter from a query she
 * got wrong, and "0 results" tells her which of her four conditions to loosen only by
 * making her delete them one at a time. The relaxation counts do that for her.
 */
@Service
public class SearchCandidates {

    /**
     * Three. The counts are all computed in one pass so the cap costs nothing to raise,
     * but a list of ten ways to loosen a query is a second puzzle rather than an answer —
     * she wants the one condition that is in the way, and the top few are where it is.
     */
    public static final int MAX_SUGGESTIONS = 3;

    private final SearchQueryParser parser;
    private final CandidateSearch search;

    public SearchCandidates(SearchQueryParser parser, CandidateSearch search) {
        this.parser = parser;
        this.search = search;
    }

    /** What a search came back with, plus the query it was read as. */
    public record Results(
            SearchQuery query,
            List<SearchHit> hits,
            SearchCursor next,
            List<CandidateSearch.Relaxation> suggestions) {}

    @Transactional(readOnly = true)
    public Results search(UUID jobId, String raw, SearchCursor after, int limit) {
        SearchQuery query = parser.parse(raw);
        CandidateSearch.SearchResultPage page = search.search(jobId, query, after, limit);

        // Only on the first page. Page four of a search coming back empty means the
        // results ran out, which is not a query she needs help with.
        List<CandidateSearch.Relaxation> suggestions =
                page.hits().isEmpty() && after == null ? suggestionsFor(jobId, query) : List.of();

        return new Results(query, page.hits(), page.next(), suggestions);
    }

    /** Only the conditions that would actually unlock something, most generous first. */
    private List<CandidateSearch.Relaxation> suggestionsFor(UUID jobId, SearchQuery query) {
        return search.relaxations(jobId, query).stream()
                .filter(relaxation -> relaxation.results() > 0)
                .sorted(Comparator.comparingLong(CandidateSearch.Relaxation::results).reversed())
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /** {@code /explain}: the parse, with nothing run. */
    public SearchQuery explain(String raw) {
        return parser.parse(raw);
    }
}
