package com.pipeline.infrastructure;

import com.pipeline.application.CandidateSearch;
import com.pipeline.application.SearchBudgetExceededException;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    /**
     * A conditional aggregate per query, so all of them are answered by one pass over the
     * candidates rather than by one round trip each.
     */
    @Override
    public List<Long> counts(UUID jobId, List<SearchQuery> queries) {
        if (queries.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        CriteriaQuery<Tuple> criteria = builder.createTupleQuery();
        Root<CandidateEntity> candidate = criteria.from(CandidateEntity.class);

        List<Selection<?>> columns = new ArrayList<>();
        for (SearchQuery query : queries) {
            columns.add(builder.sum(builder.<Long>selectCase()
                    .when(specifications.predicate(query.ast(), candidate, criteria, builder), 1L)
                    .otherwise(0L)));
        }

        Tuple row = entities
                .createQuery(criteria.multiselect(columns).where(builder.equal(candidate.get("jobId"), jobId)))
                .getSingleResult();

        List<Long> counts = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            // SUM over no rows is null rather than zero, which would be an empty pipeline.
            Long count = row.get(i, Long.class);
            counts.add(count == null ? 0 : count);
        }
        return List.copyOf(counts);
    }

    /**
     * The same, with the database told to give up.
     *
     * <p>{@code SET LOCAL} rather than a JDBC query timeout, so the bound is enforced by
     * the server that is doing the work and released with the transaction whatever happens
     * next.
     *
     * <p>Its own transaction, and that is not tidiness. A statement cancelled by Postgres
     * leaves its transaction in an aborted state, where every later statement fails too —
     * so running this inside the caller's transaction would take the rest of the response
     * down with the suggestion it was trying to abandon. Suspended and separate, the abort
     * is contained: the caller loses one optional extra and keeps everything else.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Long> countsWithin(UUID jobId, List<SearchQuery> queries, Duration budget) {
        // Not a bound parameter: SET takes none. The value is a configured duration, never
        // anything a caller supplies.
        entities.createNativeQuery("SET LOCAL statement_timeout = " + budget.toMillis())
                .executeUpdate();
        try {
            return counts(jobId, queries);
        } catch (RuntimeException e) {
            if (cancelledByTimeout(e)) {
                throw new SearchBudgetExceededException(budget, e);
            }
            throw e;
        }
    }

    /**
     * 57014 is query_canceled. Matched on the SQLState rather than on an exception type
     * because the wrapper differs between Hibernate versions and drivers, while the state
     * is the wire protocol. A genuine failure carries a different code and is rethrown, so
     * a broken query cannot disguise itself as a slow one.
     */
    private static boolean cancelledByTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
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
