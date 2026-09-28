package com.pipeline.application;

import com.pipeline.search.SearchQuery;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Running parsed queries. Separate from {@link CandidateReader} because the list and the
 * board answer "show me everyone, in this order" while this answers "who matches, best
 * first" — different keys, different cursors, and only one of them ranks.
 *
 * <p>A port, so the promise in file 08's brief stays honest: if Postgres trigram ever
 * stops being enough, the replacement implements this interface and nothing above it
 * changes.
 */
public interface CandidateSearch {

    /** Best match first. Pass a null cursor for the first page. */
    SearchResultPage search(UUID jobId, SearchQuery query, SearchCursor after, int limit);

    /**
     * How many candidates each query matches, in the order given, in one pass over the
     * candidates. One pass rather than one query each: cheaper, and it cannot produce
     * counts that disagree with each other because a transition landed between two of them.
     */
    List<Long> counts(UUID jobId, List<SearchQuery> queries);

    /**
     * The same, abandoned if it takes longer than {@code budget}.
     *
     * <p>For queries that are worth asking but not worth waiting for. The edit-distance
     * retry behind a zero-result suggestion is the case: it cannot use an index, so its
     * cost grows with the pipeline, and it is already being run on top of a search that
     * failed. A missing suggestion costs her a line of text; a hang costs her the request,
     * and on a 60-per-minute tier, several more behind it.
     *
     * @throws SearchBudgetExceededException if the budget was spent before an answer
     */
    List<Long> countsWithin(UUID jobId, List<SearchQuery> queries, Duration budget);

    /** {@code next} is null on the last page. */
    record SearchResultPage(List<SearchHit> hits, SearchCursor next) {}
}
