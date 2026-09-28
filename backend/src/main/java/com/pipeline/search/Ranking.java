package com.pipeline.search;

import com.pipeline.domain.Stage;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Why one result sorts above another, written down rather than left to vibes.
 *
 * <pre>
 * score = 0.50 * nameMatch             how well the text matches the name
 *       + 0.20 * predicateSpecificity  how many of the conditions the row satisfies
 *       + 0.20 * recency               decayed by days since the last stage event
 *       + 0.10 * stagePriority         further along the pipeline ranks higher
 * </pre>
 *
 * <p>The weights are renormalised over the terms a given query actually exercises. A query
 * with no text in it — {@code stage:interview in_stage_for:>7d} — leaves nameMatch at zero
 * for every row, so a perfect answer would otherwise score 0.43 and read as a weak match.
 * The ratios between the surviving terms are untouched; only the scale changes, so
 * "0.86 out of 1" means "a good answer to the question you asked" whatever the question was.
 *
 * <p>All of it is computed in SQL. That is not an optimisation: ranked results have to be
 * keyset-paginated, and a score computed in Java could only be applied after fetching
 * every matching row. It also makes "stable across runs" free, since the ordering is a
 * deterministic function of the row and the query rather than of iteration order.
 */
@Component
public final class Ranking {

    public static final double NAME_MATCH = 0.50;
    public static final double PREDICATE_SPECIFICITY = 0.20;
    public static final double RECENCY = 0.20;
    public static final double STAGE_PRIORITY = 0.10;

    /**
     * Thirty days. A candidate who moved yesterday scores 1.0 on recency, one who moved a
     * month ago 0.5, a quarter ago 0.125. Chosen against the pipeline rather than the
     * maths: a month of silence is the point at which a recruiter starts calling somebody
     * stale, and the curve should have said so clearly by then.
     */
    public static final double RECENCY_HALF_LIFE_DAYS = 30.0;

    /** The weights in force for one query, already renormalised. */
    public record Weights(double nameMatch, double specificity, double recency, double stagePriority) {

        static Weights forQuery(boolean hasTextTerm) {
            double name = hasTextTerm ? NAME_MATCH : 0.0;
            double total = name + PREDICATE_SPECIFICITY + RECENCY + STAGE_PRIORITY;
            return new Weights(
                    name / total,
                    PREDICATE_SPECIFICITY / total,
                    RECENCY / total,
                    STAGE_PRIORITY / total);
        }
    }

    /**
     * Further along the pipeline ranks higher, with one deliberate exception. REJECTED is
     * the last stage declared, so an ordinal would make it the highest-priority stage
     * there is and float rejected candidates above everyone still in play. It is pinned to
     * the bottom instead — derived through {@link Status} rather than named here, so the
     * rule stays "the rejected outcome is not progress" rather than "the constant REJECTED
     * is special".
     */
    public static double stagePriority(Stage stage) {
        if (Status.REJECTED.terminalStage().filter(stage::equals).isPresent()) {
            return 0.0;
        }
        return (double) stage.ordinal() / (Stage.values().length - 1);
    }

    /**
     * The score expression, and the pieces of it the explanation needs back.
     *
     * @param score what the ordering and the cursor use
     * @param nameMatch kept separately because {@code matchedOn} quotes it
     * @param satisfied one per leaf, in the order {@link SpecificationBuilder#leaves} gave
     *     them, so an explanation can say which conditions this row actually met
     */
    public record Scored(
            Expression<Double> score, Expression<Double> nameMatch, List<Expression<Double>> satisfied) {}

    public Scored score(
            List<Leaf> leaves,
            Instant now,
            Root<?> candidate,
            CriteriaQuery<?> query,
            CriteriaBuilder builder) {

        List<Expression<Double>> satisfied = leaves.stream()
                .map(leaf -> flag(leaf, candidate, query, builder))
                .toList();

        Expression<Double> nameMatch = nameMatch(leaves, candidate, builder);
        Weights weights = Weights.forQuery(leaves.stream().anyMatch(Leaf::isText));

        Expression<Double> score = builder.sum(
                builder.sum(
                        builder.prod(nameMatch, weights.nameMatch()),
                        builder.prod(mean(satisfied, builder), weights.specificity())),
                builder.sum(
                        builder.prod(recency(candidate, now, builder), weights.recency()),
                        builder.prod(stagePriority(candidate, builder), weights.stagePriority())));

        return new Scored(score, nameMatch, satisfied);
    }

    /** One if the row meets this condition, zero if not. Summed, this is the specificity. */
    private Expression<Double> flag(
            Leaf leaf, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return builder.<Double>selectCase()
                .when(leaf.predicate(candidate, query, builder), 1.0)
                .otherwise(0.0);
    }

    /**
     * The fraction of conditions this row satisfies.
     *
     * <p>Worth knowing what this does and does not separate: under a plain conjunction
     * every returned row satisfies everything, so this is 1.0 across the board and
     * contributes nothing to the ordering — correctly, because those rows are equally
     * matched. It earns its weight under an OR, where a row matching both branches is a
     * better answer than one matching either.
     */
    private Expression<Double> mean(List<Expression<Double>> flags, CriteriaBuilder builder) {
        if (flags.isEmpty()) {
            return builder.literal(0.0);
        }
        Expression<Double> total = flags.get(0);
        for (Expression<Double> flag : flags.subList(1, flags.size())) {
            total = builder.sum(total, flag);
        }
        return builder.prod(total, 1.0 / flags.size());
    }

    /**
     * Read off current_stage_since rather than by aggregating the event log, because they
     * are the same instant: every transition stamps the projection with the occurred_at of
     * the event that caused it, so current_stage_since is max(stage_event.occurred_at) by
     * construction. That saves a correlated subquery per row. A test pins the invariant,
     * since it is the kind of thing that could quietly stop being true.
     */
    private Expression<Double> recency(Root<?> candidate, Instant now, CriteriaBuilder builder) {
        Expression<Double> days = builder.function(
                "candidate_days_since", Double.class, candidate.get("currentStageSince"), builder.literal(now));
        return builder.function(
                "power", Double.class, builder.literal(0.5), builder.prod(days, 1.0 / RECENCY_HALF_LIFE_DAYS));
    }

    private Expression<Double> stagePriority(Root<?> candidate, CriteriaBuilder builder) {
        CriteriaBuilder.Case<Double> branches = builder.selectCase();
        for (Stage stage : Stage.values()) {
            branches = branches.when(builder.equal(candidate.get("currentStage"), stage), stagePriority(stage));
        }
        return branches.otherwise(0.0);
    }

    /**
     * The best any text condition scores against this row. Zero when she asked no text
     * question, in which case the weight above is zero too and the term drops out rather
     * than dragging every score down by half.
     */
    private Expression<Double> nameMatch(List<Leaf> leaves, Root<?> candidate, CriteriaBuilder builder) {
        List<Expression<Double>> scores = leaves.stream()
                .filter(Leaf::isText)
                .map(leaf -> textScore(leaf.text(), candidate, builder))
                .toList();
        if (scores.isEmpty()) {
            return builder.literal(0.0);
        }
        Expression<Double> best = scores.get(0);
        for (Expression<Double> score : scores.subList(1, scores.size())) {
            best = builder.function("greatest", Double.class, best, score);
        }
        return best;
    }

    /**
     * An address she pasted in full identifies the person outright, so it scores 1.0 —
     * above any fuzzy name match, which is the right order: an exact identifier beats a
     * good guess.
     *
     * <p>A CASE rather than GREATEST of the two, which is the same answer because an exact
     * identifier is already the highest score there is, and one fewer way for the types to
     * go wrong. GREATEST resolves its arguments to a common type, so it only works while
     * both sides are bound as doubles; a CASE whose other branch is the score expression
     * takes its type from that expression and cannot drift.
     */
    private Expression<Double> textScore(
            ResolvedValue.TextValue text, Root<?> candidate, CriteriaBuilder builder) {
        Expression<Double> byName = builder.function(
                "candidate_name_score", Double.class, candidate.get("fullName"), builder.literal(text.text()));
        if (!text.alsoEmail()) {
            return byName;
        }
        return builder.<Double>selectCase()
                .when(
                        builder.equal(
                                candidate.get("email"),
                                builder.function("citext", String.class, builder.literal(text.text()))),
                        1.0)
                .otherwise(byName);
    }

    /** Every stage in the order the priority above ranks them, for the docs and the tests. */
    public static List<Stage> byPriority() {
        return Arrays.stream(Stage.values())
                .sorted((a, b) -> Double.compare(stagePriority(b), stagePriority(a)))
                .toList();
    }
}
