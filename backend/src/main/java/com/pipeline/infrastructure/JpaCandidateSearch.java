package com.pipeline.infrastructure;

import com.pipeline.application.CandidateSearch;
import com.pipeline.application.SearchCursor;
import com.pipeline.application.SearchHit;
import com.pipeline.search.Leaf;
import com.pipeline.search.Ranking;
import com.pipeline.search.SearchQuery;
import com.pipeline.search.SpecificationBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One query, one scan, everything the answer needs: the rows, their score, and a column
 * per condition saying whether this row met it. The alternative — fetch, then ask the
 * database again why each row came back — would be a round trip per result and could
 * disagree with itself, because the second query would be scoring against a pipeline that
 * had moved on.
 */
@Component
class JpaCandidateSearch implements CandidateSearch {

    private final EntityManager entities;
    private final SpecificationBuilder specifications;
    private final Ranking ranking;
    private final Clock clock;

    JpaCandidateSearch(
            EntityManager entities, SpecificationBuilder specifications, Ranking ranking, Clock clock) {
        this.entities = entities;
        this.specifications = specifications;
        this.ranking = ranking;
        this.clock = clock;
    }

    @Override
    public SearchResultPage search(UUID jobId, SearchQuery query, SearchCursor after, int limit) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        CriteriaQuery<Tuple> criteria = builder.createTupleQuery();
        Root<CandidateEntity> candidate = criteria.from(CandidateEntity.class);

        List<Leaf> leaves = specifications.leaves(query.ast());
        Ranking.Scored scored = ranking.score(leaves, clock.instant(), candidate, criteria, builder);

        List<Predicate> where = new ArrayList<>();
        where.add(builder.equal(candidate.get("jobId"), jobId));
        where.add(specifications.predicate(query.ast(), candidate, criteria, builder));
        if (after != null) {
            where.add(keyset(after, scored.score(), candidate, builder));
        }

        List<Selection<?>> columns = new ArrayList<>();
        columns.add(candidate);
        columns.add(scored.score());
        columns.add(scored.nameMatch());
        columns.addAll(scored.satisfied());

        criteria.multiselect(columns)
                .where(builder.and(where.toArray(Predicate[]::new)))
                // The tie-break is not cosmetic. Six people called Sharma score identically
                // against "sharam", and without a total order the page boundary between
                // them would move between requests, dropping or repeating rows.
                .orderBy(
                        builder.desc(scored.score()),
                        builder.desc(candidate.get("createdAt")),
                        builder.desc(candidate.get("id")));

        // One more than asked for, purely to discover whether a next page exists, the same
        // trick the unfiltered list uses.
        List<Tuple> rows = entities.createQuery(criteria).setMaxResults(limit + 1).getResultList();
        boolean more = rows.size() > limit;
        List<Tuple> page = more ? rows.subList(0, limit) : rows;

        List<SearchHit> hits = page.stream().map(row -> hit(row, leaves)).toList();
        return new SearchResultPage(hits, more ? cursorOf(page.get(page.size() - 1)) : null);
    }

    /**
     * Every relaxation counted in a single pass, rather than one COUNT per condition.
     *
     * <p>A conditional aggregate per option reads the candidates once and answers all of
     * them at the same time, which is both cheaper than N round trips and immune to the
     * counts disagreeing with each other because a transition landed between two of them.
     * It also means the cap on how many suggestions to show is a presentation decision
     * rather than a cost one.
     */
    @Override
    public List<Relaxation> relaxations(UUID jobId, SearchQuery query) {
        List<com.pipeline.search.Relaxation> options = specifications.relaxations(query.ast());
        if (options.isEmpty()) {
            return List.of();
        }

        CriteriaBuilder builder = entities.getCriteriaBuilder();
        CriteriaQuery<Tuple> criteria = builder.createTupleQuery();
        Root<CandidateEntity> candidate = criteria.from(CandidateEntity.class);

        List<Selection<?>> counts = new ArrayList<>();
        for (com.pipeline.search.Relaxation option : options) {
            counts.add(builder.sum(builder.<Long>selectCase()
                    .when(specifications.predicate(option.remainder(), candidate, criteria, builder), 1L)
                    .otherwise(0L)));
        }

        Tuple row = entities
                .createQuery(criteria.multiselect(counts).where(builder.equal(candidate.get("jobId"), jobId)))
                .getSingleResult();

        List<Relaxation> relaxations = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            // SUM over no rows is null rather than zero, which would be an empty pipeline.
            Long count = row.get(i, Long.class);
            relaxations.add(new Relaxation(options.get(i).dropped(), count == null ? 0 : count));
        }
        return List.copyOf(relaxations);
    }

    private Predicate keyset(
            SearchCursor after, Expression<Double> score, Root<CandidateEntity> candidate, CriteriaBuilder builder) {
        Expression<Instant> createdAt = candidate.get("createdAt");
        Expression<UUID> id = candidate.get("id");
        return builder.or(
                builder.lessThan(score, after.score()),
                builder.and(
                        builder.equal(score, after.score()),
                        builder.or(
                                builder.lessThan(createdAt, after.createdAt()),
                                builder.and(
                                        builder.equal(createdAt, after.createdAt()),
                                        builder.lessThan(id, after.id())))));
    }

    /**
     * The explanation, assembled from the flags the same query returned. Only the
     * conditions this row actually met are listed: under an OR the others are exactly what
     * makes one hit a better answer than another, and claiming them all would turn the
     * explanation back into a copy of the query.
     */
    private SearchHit hit(Tuple row, List<Leaf> leaves) {
        CandidateEntity candidate = row.get(0, CandidateEntity.class);
        double score = row.get(1, Double.class);
        double nameMatch = row.get(2, Double.class);

        List<String> matchedOn = new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            if (row.get(3 + i, Double.class) >= 1.0) {
                Leaf leaf = leaves.get(i);
                matchedOn.add(leaf.isText()
                        ? "%s (%.2f)".formatted(leaf.describe(), nameMatch)
                        : leaf.describe());
            }
        }
        return new SearchHit(JpaCandidateReader.toSummary(candidate), round(score), List.copyOf(matchedOn));
    }

    private SearchCursor cursorOf(Tuple row) {
        CandidateEntity last = row.get(0, CandidateEntity.class);
        // The unrounded score, because this one is compared rather than read.
        return new SearchCursor(row.get(1, Double.class), last.createdAt, last.id);
    }

    /** Two decimals is all the precision the number carries as an explanation. */
    private static double round(double score) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", score));
    }
}
