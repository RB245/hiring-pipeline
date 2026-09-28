package com.pipeline.application;

import com.pipeline.search.SearchQuery;
import java.util.List;
import java.util.UUID;

/**
 * Running a parsed query. Separate from {@link CandidateReader} because the list and the
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
     * How many rows each of the query's conditions is costing her, for when it matched
     * nobody. One pass over the candidates rather than one query per condition.
     */
    List<Relaxation> relaxations(UUID jobId, SearchQuery query);

    /** "without in_stage_for:&gt;7d  -&gt; 6 results". Only ever built for a query that found nothing. */
    record Relaxation(String without, long results) {}

    /** {@code next} is null on the last page. */
    record SearchResultPage(List<SearchHit> hits, SearchCursor next) {}
}
