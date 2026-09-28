package com.pipeline.application;

import com.pipeline.search.Loosening;
import com.pipeline.search.Relaxation;
import com.pipeline.search.SearchQuery;
import com.pipeline.search.SearchQueryParser;
import com.pipeline.search.SpecificationBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parse, run, and — when it found nobody — work out what to say instead of nothing.
 *
 * <p>An empty result set is the failure this use case exists to soften. A query that
 * parsed, ran, and matched no one is indistinguishable to the recruiter from a query she
 * got wrong, and "0 results" tells her which of her four conditions to loosen only by
 * making her delete them one at a time.
 *
 * <p>Every suggestion it produces is a query she could have typed, counted by running that
 * very query. Nothing here builds a count one way and advertises it another.
 */
@Service
@EnableConfigurationProperties(SearchProperties.class)
public class SearchCandidates {

    private static final Logger log = LoggerFactory.getLogger(SearchCandidates.class);

    /**
     * Three. The counts are all taken in two passes however many there are, so the cap
     * costs nothing to raise, but a list of ten ways to loosen a query is a second puzzle
     * rather than an answer — she wants the one condition that is in the way.
     */
    public static final int MAX_SUGGESTIONS = 3;

    private final SearchQueryParser parser;
    private final SpecificationBuilder specifications;
    private final CandidateSearch search;
    private final SearchProperties properties;

    public SearchCandidates(
            SearchQueryParser parser,
            SpecificationBuilder specifications,
            CandidateSearch search,
            SearchProperties properties) {
        this.parser = parser;
        this.specifications = specifications;
        this.search = search;
        this.properties = properties;
    }

    /** What a search came back with, plus the query it was read as. */
    public record Results(
            SearchQuery query, List<SearchHit> hits, SearchCursor next, List<Suggestion> suggestions) {}

    @Transactional(readOnly = true)
    public Results search(UUID jobId, String raw, SearchCursor after, int limit) {
        SearchQuery query = parser.parse(raw);
        CandidateSearch.SearchResultPage page = search.search(jobId, query, after, limit);

        // Only on the first page. Page four of a search coming back empty means the
        // results ran out, which is not a query she needs help with.
        List<Suggestion> suggestions =
                page.hits().isEmpty() && after == null ? suggestionsFor(jobId, query) : List.of();

        return new Results(query, page.hits(), page.next(), suggestions);
    }

    /** Only the ones that would actually unlock something, most generous first. */
    private List<Suggestion> suggestionsFor(UUID jobId, SearchQuery query) {
        List<Suggestion> suggestions = new ArrayList<>(dropping(jobId, query));
        suggestions.addAll(loosening(jobId, query));
        return suggestions.stream()
                .filter(suggestion -> suggestion.results() > 0)
                .sorted(Comparator.comparingLong(Suggestion::results).reversed())
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /** Everything she asked for, minus one condition. Cheap, so all of them at once. */
    private List<Suggestion> dropping(UUID jobId, SearchQuery query) {
        List<Relaxation> options = specifications.relaxations(query.ast());
        if (options.isEmpty()) {
            return List.of();
        }
        List<Long> counts = search.counts(jobId, parseAll(options.stream().map(Relaxation::query).toList()));

        List<Suggestion> suggestions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            suggestions.add(new Suggestion(options.get(i).label(), options.get(i).query(), counts.get(i)));
        }
        return suggestions;
    }

    /**
     * The same condition asked more generously — {@code name:pryia} as
     * {@code name_like:pryia}.
     *
     * <p>Two things make this different from dropping a condition. It is counted under a
     * time budget, because edit distance cannot use an index and its cost grows with the
     * pipeline; if the budget is spent, the suggestions are dropped and the recruiter gets
     * the rest of her answer rather than a hanging request. And it is compared against the
     * condition as she wrote it, so that loosening is only offered when it would find
     * somebody new.
     */
    private List<Suggestion> loosening(UUID jobId, SearchQuery query) {
        List<Loosening> options = specifications.looseners(query.ast());
        if (options.isEmpty()) {
            return List.of();
        }

        List<Long> asWritten = search.counts(jobId, parseAll(options.stream().map(Loosening::asWritten).toList()));
        List<Long> wider;
        try {
            wider = search.countsWithin(
                    jobId,
                    parseAll(options.stream().map(Loosening::query).toList()),
                    properties.relaxationBudget());
        } catch (SearchBudgetExceededException e) {
            // Deliberate, and worth a line: it means this pipeline has outgrown an
            // unindexable retry, which is a capacity fact rather than a bug.
            log.info("Dropping {} looser-match suggestion(s): {}", options.size(), e.getMessage());
            return List.of();
        }

        List<Suggestion> suggestions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            // Withheld when loosening changes nothing. Offering "6 results with a looser
            // name match" when the name already matched those six would blame her spelling
            // for an empty result some other condition caused.
            if (wider.get(i) > asWritten.get(i)) {
                suggestions.add(new Suggestion(options.get(i).label(), options.get(i).query(), wider.get(i)));
            }
        }
        return suggestions;
    }

    /**
     * Suggestions are built as DSL and parsed back before being run, rather than carried
     * around as trees. It costs a parse of a string the renderer just produced, and buys
     * the guarantee the whole feature rests on: what she is offered and what was counted
     * are the same query, because there is only one of them.
     */
    private List<SearchQuery> parseAll(List<String> queries) {
        return queries.stream().map(parser::parse).toList();
    }

    /** {@code /explain}: the parse, with nothing run. */
    public SearchQuery explain(String raw) {
        return parser.parse(raw);
    }
}
