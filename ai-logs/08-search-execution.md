# 08 - Search part two: query building, ranking, zero-result relaxation

Prompt given: `prompts/08-*.md`

---

<!-- I'll start by reading the existing code so my plan is grounded in what's actually there rather than what I'd guess.

  Searched for 1 pattern, read 20 files, listed 1 directory, ran 10 shell commands

Good — Docker is available, so I can measure rather than assume. Before I write a plan I want to settle the empirical questions it depends on (%> operand order and index usage, whether reached: is hot, and the unfiltered-sort numbers). Spinning up a throwaway Postgres for that.

  Ran 6 shell commands

Confirmed the carry-over facts and found something new: at 0.5, word_similarity('sharam', 'Vikram Sharma') = 0.857 beats 'Priya Sharma' = 0.571. Now the plan measurements.

  Ran 6 shell commands

Found something important: with the index, the deep page gets slower (29ms vs 8ms) because the hand-written OR keyset predicate can't be an index condition. Testing the row-value form.

  Ran 3 shell commands

E2 is a problem: OR-ing an unindexed email predicate costs 310 ms vs 11.8 ms for name alone. Testing the two fixes.

  Ran 2 shell commands

Spike done — I have real numbers. Cleaning up later; keeping the container for the implementation EXPLAIN pass.

What I measured (50k candidates, 152k events, postgres:16, parallelism off)

Carry-overs confirmed exactly as written. similarity('sharam','Priya Sharma') = 0.25 (default threshold 0.3 → no match). word_similarity('sharam','Priya Sharma') = 0.571, and the default word_similarity_threshold is 0.6, so 0.5 is genuinely required. Both immutable_unaccent(full_name) %> 'sharam' and the commuted <% form hit candidate_name_trgm_idx; accent folding works (immutable_unaccent('Zoë Müller') %> 'muller' → true).

NOT is_terminal is load-bearing, as V5 warns. With it: candidate_active_since_idx, 4.7 ms. Written as current_stage NOT IN ('HIRED','REJECTED'): falls back to candidate_stage_idx and re-filters the date, 4.8 ms but scanning 8333 rows to keep 7777. So the builder must emit AND NOT is_terminal whenever in_stage_for appears — which means mapping is_terminal read-only on CandidateEntity.

reached: is not hot. Seq scan 10.0 ms, against 4.1 ms for stage:, 4.7 ms for in_stage_for, 11.8 ms for the trigram name match. The enumerated alternative does work — reached_mask = ANY(ARRAY[15,31,47]) with a btree gives an index-only scan at 4.6 ms — but 10 ms is mid-pack, not the bottleneck. Recommendation: leave it, record the number.

Two findings that change the design:

1. The list index is a trap in the current query shape. At 50k, adding (job_id, created_at DESC, id DESC): page 1 goes 16.5 ms → 0.19 ms, but a deep keyset page goes 7.9 ms → 29.3 ms (45,286 buffers vs 1,724), because the longhand OR form of the keyset predicate can only be a Filter, never an Index Cond. Row-value (created_at, id) < (?, ?) is an Index Cond: 0.13 ms, flat at any depth. At seed scale (200 rows) everything is 0.12–0.17 ms and indistinguishable.
2. Bare-term email matching costs 26x. name %> term OR email LIKE '%term%' = 310 ms — the unindexed OR arm forces a seq scan. A trigram GIN on email does not fix it (240 ms): email is citext, so LIKE is the citext operator and gin_trgm_ops never applies. name %> term OR email = term is 24 ms with a clean BitmapOr across both existing indexes.

Decisions I'm making myself (tell me if you disagree)

- Threshold GUC: database level, new migration V9, via EXECUTE format('ALTER DATABASE %I SET pg_trgm.word_similarity_threshold = 0.5', current_database()). Verified: a brand-new connection as pipeline_app reads 0.5 and 'Priya Sharma' %> 'sharam' is true. I picked this over pool init SQL because the threshold is a property of how this schema's trigram index must be queried — it belongs beside the index in V5/V9, not in the pool — and because it covers raw JDBC, psql and Testcontainers, where connection-init-sql covers only connections Hikari opened. Bonus: if the ALTER is ever removed, SHOW pg_trgm.word_similarity_threshold errors on a fresh connection rather than quietly reporting 0.6, so the test fails loudly.
- Score computed in SQL, not Java — otherwise ranked results can't be keyset-paginated (you'd have to fetch every match to sort it), and "stable across runs" stops being free. Ties break on (score DESC, created_at DESC, id DESC); the cursor carries all three.
- Recency reads current_stage_since, not max(occurred_at) — they are the same value by construction (every transition stamps the projection with the event's occurredAt), so this drops a correlated subquery per row. I'll add a test pinning that invariant.
- Zero-result relaxation is one query, not N. sum(CASE WHEN <all predicates except i> THEN 1 ELSE 0 END) as N columns over one scan, instead of N round trips. Strictly cheaper than the capped version you described, and the cap becomes purely presentational (show the best 3).
- predicateSpecificity = fraction of the query's leaf predicates a row satisfies. For an AND-only query that's 1.0 for everyone (contributes nothing to ordering — correctly, they all matched equally); it only separates rows under OR. Flagging it because it means that term is a constant for most queries.
- Criteria per your brief, with a small Hibernate FunctionContributor so %>, word_similarity and levenshtein are reachable; CandidateEntity becomes public so handlers can return Specification<CandidateEntity>.

One ranking observation worth your attention: at threshold 0.5, word_similarity('sharam','Vikram Sharma') = 0.857, higher than 'Priya Sharma' = 0.571. Trigram alone ranks the wrong person first; the levenshtein tiebreak in your formula is what fixes it, so it's doing real work, not decoration.

● User answered Claude's questions:
· Your formula leaves nameMatch at 0 for every row when the query has no text term, so `stage:interview in_stage_for:>7d` tops out at a score of 0.50 and a perfect match displays as weak. Renormalise? → Renormalise over live components (Recommended)
· The unfiltered-list sort (file 04 carry-over). At seed scale there is nothing to fix. At 50k the index helps page 1 enormously but regresses deep pages unless `pageAfter` also moves to row-value comparison, which JPQL cannot express. → No index, document the numbers (Recommended)
· File 07 says a bare word searches name and email fuzzily. Fuzzy email is measurably not viable: `email` is citext, so a trigram GIN never applies to it. → Fuzzy name + exact email (Recommended)

All three recommendations accepted. Here's the plan I'll work to.

┌─────┬───────────────────────────────────────────────────────────┬─────────────────────────────────────────────┐
│  #  │                           Step                            │              How it's verified              │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ Migration V9 sets word_similarity_threshold = 0.5 at      │ Test opens a fresh connection outside the   │
│ 1   │ database level                                            │ pool: SHOW returns 0.5 and 'Priya Sharma'   │
│     │                                                           │ %> 'sharam' is true                         │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ Map is_terminal read-only; CandidateEntity public;        │ ddl-auto=validate still passes existing     │
│ 2   │ Hibernate FunctionContributor for %>, word_similarity,    │ persistence suite                           │
│     │ levenshtein                                               │                                             │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 3   │ FieldHandler.specification(...) on all nine handlers      │ Per-field integration tests against real    │
│     │                                                           │ Postgres                                    │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 4   │ SpecificationBuilder composes and/or/not only             │ ArchUnit: builder names no field; eight     │
│     │                                                           │ acceptance queries return correct rows      │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │                                                           │ Weight math unit-tested; sharam finds Priya │
│ 5   │ Ranking in SQL, renormalised weights, matchedOn           │  Sharma; identical order across repeated    │
│     │                                                           │ runs                                        │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 6   │ Search port + JPA adapter, keyset on (score, created_at,  │ Paging test: no row repeated or skipped     │
│     │ id)                                                       │ across pages                                │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 7   │ Zero-result relaxation, one scan, N sum(case) columns     │ Test asserting the two suggestion lines     │
│     │                                                           │ from your example                           │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 8   │ /search/explain, /search/suggest, q= on candidates,       │ API tests + rate-limit tier assertion       │
│     │ SEARCH tier                                               │                                             │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 9   │ EXPLAIN pass over the SQL Hibernate actually emits, at    │ Plans captured; any index miss reported to  │
│     │ 50k                                                       │ you                                         │
├─────┼───────────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 10  │ Record measurements in docs                               │ —                                           │
└─────┴───────────────────────────────────────────────────────────┴─────────────────────────────────────────────┘

Reading the last few parser files I need for /suggest.
Now implementing. First, measuring the threshold trade-off so the comment in the migration is backed by numbers rather than a guess.

  Ran 1 shell command

pryia scores 0.333 — trigram misses short-word transpositions at any usable threshold. Checking whether a levenshtein fallback is affordable.

  Ran 1 shell command

Writing the migration. Testing two things that would change the design if they fail: whether ALTER DATABASE ... SET survives Flyway's transaction, and whether wrapping %> in an inlinable SQL function keeps the GIN index.

● Write(backend\src\main\resources\db\migration\V9__search.sql)
Wrote 109 lines to backend\src\main\resources\db\migration\V9__search.sql
-- Everything the search layer needs from the database: the threshold its name matching
-- depends on, and the two functions that express "does this name match" and "how well".
-- Both live here rather than in the application because they are properties of how the
-- trigram index in V5 has to be queried, not of how Java happens to call it.


-- The word-similarity threshold, set where every connection inherits it.
--
-- It has to be set at all because the motivating query does not work at the default.
-- pg_trgm ships pg_trgm.word_similarity_threshold at 0.6 and
… +99 lines

  Ran 2 shell commands

Function inlines — %> stays visible to the planner, so no Hibernate function registration is needed. Testing whether the same trick works for moved_to, which would keep JPA entity classes out of the search package entirely.

  Ran 1 shell command

moved_to is the exception: wrapping it costs 609 ms vs 24 ms — the planner won't inline it into a semi-join. So it needs a real Criteria subquery, which means StageEventEntity has to be reachable from that one handler.

  Ran 1 shell command

Now the Java. Checking existing usages before I change a shared record.

  Read 1 file, ran 1 shell command

Now the Java. Starting with the search package core.

● Update(backend\src\main\java\com\pipeline\search\ResolvedValue.java)
Added 6 lines, removed 2 lines

    record StatusValue(Status status) implements ResolvedValue {}

    /** Fuzzy text: a name, or a bare term that will be matched against name and email. */
    record TextValue(String text) implements ResolvedValue {}
    /**
     * Fuzzy text. {@code alsoEmail} is the whole difference between a bare word, which
     * identifies a person by any of the ways she is written down, and {@code name:},
     * which means the name and only the name.
     */
    record TextValue(String text, boolean alsoEmail) implements ResolvedValue {}

    /** The literal is kept alongside the instant so {@code /explain} can show its working. */
    record DateValue(String literal, Instant instant) implements ResolvedValue {}

● Update(backend\src\main\java\com\pipeline\search\FieldHandler.java)
Added 5 lines
package com.pipeline.search;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**

● Update(backend\src\main\java\com\pipeline\search\FieldHandler.java)
Added 2 lines, removed 3 lines
 * and the guards are all written against this interface — so a new field is a new class
 * and a bean definition, and nothing else.
 *
 * <p>File 08 adds one more method here, turning a resolved value into a Specification.
 * That is the same bargain: the builder composes and/or/not and the handler supplies the
 * predicate.
 * <p>File 08 adds {@link #predicate} here, turning a resolved value into SQL. That is the
 * same bargain: the builder composes and/or/not and the handler supplies the predicate.
 */
public interface FieldHandler {


● Update(backend\src\main\java\com\pipeline\search\FieldHandler.java)
Added 28 lines
    ResolvedValue resolve(Node.Value value, Operator operator, Clock clock);

    /**
     * The SQL half: what this field filters on, given a value it has already resolved.
     *
     * <p>The signature is Spring's {@code Specification.toPredicate} without the entity
     * type parameter. That omission is deliberate and is what keeps a JPA entity class out
     * of this package: every column a handler needs is reached by attribute name, so the
     * search layer never has to import the persistence layer to describe a filter. The
     * adapter wraps whatever comes back into a {@code Specification} for the repository.
     *
     * <p>Abstract rather than defaulted, so that a new searchable field is a compile error
     * until it says how it filters. A default returning "true" would make a field that
     * silently matched everyone, which is the one failure the recruiter cannot see.
     */
    Predicate predicate(ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuil
der builder);

    /**
     * How a bare word with no field in front of it should be read, or empty if this field
     * does not claim them. At most one handler may; {@link FieldRegistry} checks that at
     * startup rather than letting two fields race for the same input.
     *
     * <p>Declared here rather than hard-wired in the builder for the same reason as
     * everything else on this interface: "a bare word means a name" is a decision about a
     * field, and the builder is not allowed to know any field's name.
     */
    default Optional<ResolvedValue> bareTerm(String text) {
        return Optional.empty();
    }

    /**
     * What kind of thing the value is, in the words an error message uses: "a date", "a
     * stage". Reads straight into "since: needs a date".
     */

Now the registry needs to resolve the bare-term claimant at startup.

● Update(backend\src\main\java\com\pipeline\search\FieldRegistry.java)
Added 10 lines

    private final Map<String, FieldHandler> handlers = new TreeMap<>();
    private final Set<String> modifierFields = new HashSet<>();
    private final FieldHandler bareTermHandler;

    public FieldRegistry(List<FieldHandler> handlers) {
        FieldHandler claimant = null;
        for (FieldHandler handler : handlers) {
            FieldHandler clash = this.handlers.put(handler.field(), handler);
            if (clash != null) {

                        + clash.getClass().getName() + " and " + handler.getClass().getName());
            }
            modifierFields.addAll(handler.modifiers());
            if (handler.bareTerm("probe").isPresent()) {
                if (claimant != null) {
                    throw new IllegalStateException("Two handlers claim bare terms: "
                            + claimant.getClass().getName() + " and " + handler.getClass().getName()
);
                }
                claimant = handler;
            }
        }
        this.bareTermHandler = claimant;
    }

    public Optional<FieldHandler> find(String field) {

● Update(backend\src\main\java\com\pipeline\search\FieldRegistry.java)
Added 9 lines
    public boolean isModifier(String field) {
        return modifierFields.contains(field);
    }

    /**
     * The field a bare word falls through to. Absent only in a registry assembled without
     * one, which the parser tests do; the running application always has a claimant, and
     * the builder says so rather than quietly dropping the term.
     */
    public Optional<FieldHandler> bareTermHandler() {
        return Optional.ofNullable(bareTermHandler);
    }
}

  Ran 1 shell command

Another gotcha caught: a bound varchar parameter against a citext column silently becomes a case-sensitive comparison. Checking the fix and its plan.

  Ran 1 shell command

candidate_name_matches inlines because it's a single operator; adding an OR breaks inlining (826 ms). So the OR belongs in Criteria, not in SQL. Adding the last helper function.

● Update(backend\src\main\resources\db\migration\V9__search.sql)
Added 15 lines
    AS $$ SELECT immutable_unaccent(full_name) %> immutable_unaccent(term) $$;


-- "Did they ever get this far?"
--
-- Exists because the Criteria API has no bitwise operator and the alternative is guessing
-- at whichever name the Hibernate dialect happens to register for one. A single-expression
-- SQL function inlines, so the planner sees the same masked predicate V5 measured and the
-- search layer does not have to know what SQL dialect it is speaking.
--
-- Not indexed, on purpose, and V5 explains why at length: & is not a searchable operator.
-- Measured again here at 50k rows, it is a 10.0ms sequential scan against 4.1ms for
-- stage: and 11.8ms for the trigram name match — mid-pack, so it stays as it is.
CREATE FUNCTION candidate_reached(reached_mask smallint, stage_bit integer) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT (reached_mask & stage_bit) = stage_bit $$;


-- "How well does it match?" — the nameMatch term of the ranking formula.
--
-- Written here rather than in Java so that it sits beside the operator and the index it

Now the handlers. Each owns its own SQL.

● Update(backend\src\main\java\com\pipeline\search\fields\StageField.java)
Added 14 lines
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

        return new ResolvedValue.StageValue(Stages.resolve(value));
    }

    /**
     * Straight off the projection, which is what candidate_stage_idx is for: measured at
     * 4.1ms against 50k rows, a bitmap index scan over one column of the board.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        return builder.equal(candidate.get("currentStage"), ((ResolvedValue.StageValue) value).stage
());
    }

    @Override
    public String valueKind() {
        return "a stage";
    }

● Update(backend\src\main\java\com\pipeline\search\fields\ReachedField.java)
Added 4 lines
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

● Update(backend\src\main\java\com\pipeline\search\fields\ReachedField.java)
Added 14 lines
        return new ResolvedValue.StageValue(Stages.resolve(value));
    }

    /**
     * A bit test on the projection rather than a semi-join against the event log. Both
     * answer the question; V5 measured the mask at 8.4ms against 11.3ms for the EXISTS
     * pair, and the gap widens as histories grow because the mask is one row however many
     * events a candidate accumulates.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        int bit = ((ResolvedValue.StageValue) value).stage().bit();
        return builder.isTrue(builder.function(
                "candidate_reached", Boolean.class, candidate.get("reachedMask"), builder.literal(bi
t)));
    }

    @Override
    public String valueKind() {
        return "a stage";
    }

● Update(backend\src\main\java\com\pipeline\search\fields\StatusField.java)
Added 4 lines
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Statuses;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

● Update(backend\src\main\java\com\pipeline\search\fields\StatusField.java)
Added 17 lines
        return new ResolvedValue.StatusValue(Statuses.resolve(value));
    }

    /**
     * Derived from the stage rather than stored, exactly as {@link com.pipeline.search.Status}
     * derives it: an outcome is its terminal stage, and active is the absence of one.
     * Active reads the generated is_terminal column rather than writing out
     * {@code NOT IN ('HIRED','REJECTED')} — V4 says why the column exists, and the
     * difference is measurable wherever a partial index is involved.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        return ((ResolvedValue.StatusValue) value)
                .status()
                .terminalStage()
                .map(stage -> builder.equal(candidate.get("currentStage"), stage))
                .orElseGet(() -> builder.isFalse(candidate.get("isTerminal")));
    }

    @Override
    public String valueKind() {
        return "one of " + String.join(", ", Statuses.names());
    }

● Write(backend\src\main\java\com\pipeline\search\fields\Ages.java)
Wrote 35 lines to backend\src\main\java\com\pipeline\search\fields\Ages.java
package com.pipeline.search.fields;

import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;

/**
 * The comparison that reads backwards, in the one place the two fields that need it can
 * share it.
… +26 lines

● Update(backend\src\main\java\com\pipeline\search\fields\InStageForField.java)
Added 5 lines
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

● Update(backend\src\main\java\com\pipeline\search\fields\InStageForField.java)
Added 22 lines
        return new ResolvedValue.AgeValue(effective, value.text(), Durations.threshold(value, clock)
);
    }

    /**
     * The "AND NOT is_terminal" is not decoration and not an optimisation bolted on
     * afterwards — it is half of what this field means. Time-in-stage is meaningless once
     * someone is hired or rejected: they are not stuck, they are finished.
     *
     * <p>It is also what makes candidate_active_since_idx reachable, and V5 is emphatic
     * about that for a measured reason. Re-measured here against 50k rows: with this
     * conjunct the planner takes the partial index and the cutoff is part of the index
     * condition (4.7ms); without it, it silently falls back to candidate_stage_idx and
     * re-checks the date as a filter, reading 8333 rows to keep 7777. Writing the same
     * thing as {@code current_stage NOT IN ('HIRED','REJECTED')} does not work either —
     * that was tried, and the planner's predicate prover cannot match it to the index.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        return builder.and(
                builder.isFalse(candidate.get("isTerminal")),
                Ages.matching(
                        (ResolvedValue.AgeValue) value, candidate.<Instant>get("currentStageSince"),
 builder));
    }

    @Override
    public String valueKind() {
        return "a length of time";
    }

● Update(backend\src\main\java\com\pipeline\search\fields\AppliedField.java)
Added 5 lines
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

● Update(backend\src\main\java\com\pipeline\search\fields\AppliedField.java)
Added 12 lines
        return new ResolvedValue.AgeValue(effective, value.text(), Durations.threshold(value, clock)
);
    }

    /**
     * On created_at, and with no {@code NOT is_terminal} companion: unlike time-in-stage,
     * when somebody applied stays true after they are hired or rejected. There is no index
     * behind this one and V5 does not offer it a question of its own; it is nearly always
     * written alongside a filter that does have one.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        return Ages.matching((ResolvedValue.AgeValue) value, candidate.<Instant>get("createdAt"), bu
ilder);
    }

    @Override
    public String valueKind() {
        return "a length of time";
    }

moved_to needs the entity class, so making it public — the one place search reaches into persistence.

● Update(backend\src\main\java\com\pipeline\infrastructure\StageEventEntity.java)
Added 8 lines, removed 1 line
 * Immutable so Hibernate never generates an UPDATE for it. That is a fourth line of
 * defence rather than the real one: the V6 trigger and the V7 revoke both stop a write
 * this annotation merely never attempts.
 *
 * <p>Public, alone among the entities, because {@code moved_to:} is a question about the
 * log rather than about the projection and the Criteria API needs a class to root a
 * subquery on. Wrapping the EXISTS in a SQL function to avoid that was tried and
 * measured: the planner will not inline it, so a semi-join that costs 24ms against 50k
 * rows becomes a per-row function call costing 609ms. Its fields stay package-private —
 * the search layer names columns, not members.
 */
@Entity
@Table(name = "stage_event")
@Immutable
class StageEventEntity {
public class StageEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

● Update(backend\src\main\java\com\pipeline\search\fields\MovedToField.java)
Added 8 lines
package com.pipeline.search.fields;

import com.pipeline.infrastructure.StageEventEntity;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

● Update(backend\src\main\java\com\pipeline\search\fields\MovedToField.java)
Added 29 lines
        return new ResolvedValue.MovedToValue(Stages.resolve(value), Optional.empty(), Optional.empt
y());
    }

    /**
     * The only field that reads the log instead of the projection, and it has to: the
     * projection knows where somebody is now, not that they passed through Interview on
     * Tuesday and were rejected on Thursday. EXISTS rather than a join so that a candidate
     * with three qualifying events is still one row.
     *
     * <p>The bounds are half-open. A date resolves to midnight, so {@code since:monday}
     * includes everything from Monday morning and {@code before:today} stops at last
     * midnight — which is what "before today" means to the person typing it.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        ResolvedValue.MovedToValue moved = (ResolvedValue.MovedToValue) value;

        Subquery<Integer> events = query.subquery(Integer.class);
        Root<StageEventEntity> event = events.from(StageEventEntity.class);

        List<Predicate> conditions = new ArrayList<>();
        conditions.add(builder.equal(event.get("candidateId"), candidate.get("id")));
        conditions.add(builder.equal(event.get("toStage"), moved.stage()));
        moved.since().ifPresent(since ->
                conditions.add(builder.greaterThanOrEqualTo(event.<Instant>get("occurredAt"), since.
instant())));
        moved.before().ifPresent(before ->
                conditions.add(builder.lessThan(event.<Instant>get("occurredAt"), before.instant()))
);

        return builder.exists(events.select(builder.literal(1)).where(conditions.toArray(Predicate[]
::new)));
    }

    @Override
    public Set<String> modifiers() {
        return Set.of("since", "before");
    }

Now NameField, which owns both name: and the bare-term path.

● Write(backend\src\main\java\com\pipeline\search\fields\NameField.java)
Added 56 lines, removed 2 lines
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The candidate's name, matched fuzzily in file 08. Nothing is validated here on purpose:
 * The candidate's name, matched fuzzily. Nothing is validated on the way in on purpose:
 * any string is a plausible name, and rejecting one because it looked odd would be the
 * parser deciding who exists.
 *
 * <p>This field also claims bare words, which is why it is the one place that knows a
 * person can be identified by something other than their name.
 */
@Component
class NameField implements FieldHandler {


    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.TextValue(value.text().strip());
        return new ResolvedValue.TextValue(value.text().strip(), false);
    }

    /**
     * A bare word means the same fuzzy name match, widened to the email. The widening is
     * the whole reason bare terms are a separate resolution rather than a rewrite into
     * {@code name:}: pasting an address into the search box has to find the person.
     */
    @Override
    public Optional<ResolvedValue> bareTerm(String text) {
        return Optional.of(new ResolvedValue.TextValue(text.strip(), true));
    }

    /**
     * Two arms, both index-backed, which is the only reason this is affordable. The name
     * arm is candidate_name_matches, a single-expression SQL function that Postgres
     * inlines so the %> operator stays visible and candidate_name_trgm_idx is still
     * chosen. The email arm is plain equality against the existing unique index.
     *
     * <p>The email comparison is wrapped in citext() rather than left to the driver. A
     * parameter bound as varchar makes Postgres resolve {@code citext = varchar} as an
     * ordinary text comparison, which is case-sensitive — so the column's whole point
     * would be quietly lost and "Priya@Example.com" would stop finding her. Measured, not
     * guessed: the uncast form returns zero rows for an address that exists.
     *
     * <p>Exact rather than fuzzy on the email arm, and that was measured too. Nobody
     * types a mangled fragment of an address hoping for a fuzzy hit; they paste the whole
     * thing. Making it fuzzy costs 310ms against 50k rows because the unindexed arm drags
     * the whole OR into a sequential scan, and a trigram index on the column does not
     * rescue it — email is citext, so LIKE is the citext operator and gin_trgm_ops never
     * applies. This form plans to a BitmapOr across two existing indexes at 24ms.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        ResolvedValue.TextValue text = (ResolvedValue.TextValue) value;
        Predicate byName = builder.isTrue(builder.function(
                "candidate_name_matches", Boolean.class, candidate.get("fullName"), builder.literal(
text.text())));
        if (!text.alsoEmail()) {
            return byName;
        }
        return builder.or(byName, builder.equal(candidate.get("email"), citext(text.text(), builder)
));
    }

    /** {@code citext(?)} is the function-call spelling of {@code ?::citext}. */
    static jakarta.persistence.criteria.Expression<String> citext(String term, CriteriaBuilder build
er) {
        return builder.function("citext", String.class, builder.literal(term));
    }

    @Override
    public String valueKind() {
        return "a name";
    }

Now the two modifier fields, which the validator folds away before the builder ever sees them.

● Update(backend\src\main\java\com\pipeline\search\fields\SinceField.java)
Added 16 lines
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

        return Dates.resolve(value, clock);
    }

    /**
     * Never called. The validator folds a modifier into the field it modifies and rejects
     * one that has nothing to modify, so no modifier survives into the tree the builder
     * walks. Throwing says that out loud; returning something harmless would turn a
     * broken validator into a filter that quietly matched everyone.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        throw new IllegalStateException(field() + ": is a modifier and has no predicate of its own")
;
    }

    @Override
    public String valueKind() {
        return "a date";
    }

● Update(backend\src\main\java\com\pipeline\search\fields\BeforeField.java)
Added 11 lines
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

        return Dates.resolve(value, clock);
    }

    /** Never called, for the reason given on {@link SinceField#predicate}. */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder)
 {
        throw new IllegalStateException(field() + ": is a modifier and has no predicate of its own")
;
    }

    @Override
    public String valueKind() {
        return "a date";
    }

● Update(backend\src\main\java\com\pipeline\infrastructure\CandidateEntity.java)
Added 9 lines, removed 2 lines
import org.hibernate.type.SqlTypes;

/**
 * is_terminal is deliberately unmapped: it is a generated column and the database owns
 * it. Mapping it would invite Hibernate to try to write it.
 * is_terminal is mapped read-only. It was unmapped until file 08, on the grounds that the
 * database generates it and mapping it would invite Hibernate to try to write it; the
 * second half of that is handled by insertable=false, updatable=false, and the first half
 * turned out to be a reason to map it rather than not.
 *
 * <p>Search needs to be able to say "AND NOT is_terminal" in a query, and that conjunct is
 * not optional: V5's partial index is only reachable through it, and re-measuring at 50k
 * rows confirms the planner falls back to a broader index and a filter without it. The
 * column is the only spelling its predicate prover will match.
 */
@Entity
@Table(name = "candidate")

● Update(backend\src\main\java\com\pipeline\infrastructure\CandidateEntity.java)
Added 5 lines

    Instant currentStageSince;
    short reachedMask;

    /** Generated by the database; readable so search can filter on it, never written. */
    @Column(insertable = false, updatable = false)
    boolean isTerminal;

    Instant createdAt;

    @Version

Now the builder, which composes and nothing else.

● Write(backend\src\main\java\com\pipeline\search\Leaf.java)
Wrote 59 lines to backend\src\main\java\com\pipeline\search\Leaf.java
package com.pipeline.search;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Locale;

/**
 * One condition of a query, with any enclosing negations already folded in, so that
… +51 lines

● Write(backend\src\main\java\com\pipeline\search\SpecificationBuilder.java)
Wrote 112 lines to backend\src\main\java\com\pipeline\search\SpecificationBuilder.java
package com.pipeline.search;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
… +105 lines

● Write(backend\src\main\java\com\pipeline\search\Relaxation.java)
Wrote 11 lines to backend\src\main\java\com\pipeline\search\Relaxation.java
package com.pipeline.search;

/**
 * One way of loosening a query that found nobody: the condition to drop, spelled the way
 * she wrote it, and the query that is left without it.
 *
 * <p>{@code dropped} is rendered from the tree rather than sliced out of her input, so it
 * comes back in the same canonical form {@code /explain} shows — which means the
 * suggestion is something she can paste back into the box.
 */
… +1 line

● Update(backend\src\main\resources\db\migration\V9__search.sql)
Added 20 lines, removed 5 lines
-- Worth knowing, because it is the case this floor exists for: trigram alone cannot see
-- a transposition in a short word. word_similarity('pryia', 'Priya Sharma') is 0.333, so
-- no usable threshold admits it, while levenshtein('pryia', 'priya') is 2.
CREATE FUNCTION candidate_name_score(full_name text, term text) RETURNS real
CREATE FUNCTION candidate_name_score(full_name text, term text) RETURNS double precision
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$
    WITH folded AS (

               lower(immutable_unaccent(term))      AS needle
    )
    SELECT CASE
        WHEN f.name = f.needle THEN 1.0::real
        WHEN f.name LIKE f.needle || '%' OR f.name LIKE '% ' || f.needle || '%' THEN 0.85::real
        WHEN f.name = f.needle THEN 1.0::double precision
        WHEN f.name LIKE f.needle || '%' OR f.name LIKE '% ' || f.needle || '%' THEN 0.85::double p
recision
        ELSE greatest(
            word_similarity(f.needle, f.name),
            word_similarity(f.needle, f.name)::double precision,
            CASE WHEN EXISTS (
                SELECT 1 FROM unnest(string_to_array(f.name, ' ')) AS word
                WHERE levenshtein_less_equal(word, f.needle, 2)
                      <= CASE WHEN length(f.needle) <= 4 THEN 1 ELSE 2 END)
                 THEN 0.80::real ELSE 0.0::real END)
                 THEN 0.80::double precision ELSE 0.0::double precision END)
    END
    FROM folded f
    $$;


-- How old something is, in days, against the injected clock rather than now().
--
-- Exists only because the Criteria API cannot express timestamp subtraction, and the
-- recency term of the ranking needs it. The decay curve itself is deliberately not here:
-- it lives in Ranking beside the weights it is weighed against, so the formula can be
-- read in one place.
--
-- "at" is passed in, never defaulted to now(), for the same reason nothing in this schema
-- has a DEFAULT now(): the application owns a single injected Clock, so "seven days ago"
-- means the same thing in a test as it does in production.
CREATE FUNCTION candidate_days_since(moment timestamptz, at timestamptz) RETURNS double precision
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT greatest(0, extract(epoch FROM (at - moment)) / 86400.0)::double precision $$;

Now the ranking formula, in one place.

● Write(backend\src\main\java\com\pipeline\search\Ranking.java)
Wrote 211 lines to backend\src\main\java\com\pipeline\search\Ranking.java
package com.pipeline.search;

import com.pipeline.domain.Stage;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
… +206 lines

  Ran 1 shell command

● Update(backend\src\main\java\com\pipeline\search\fields\NameField.java)
Added 6 lines, removed 6 lines
        if (!text.alsoEmail()) {
            return byName;
        }
        return builder.or(byName, builder.equal(candidate.get("email"), citext(text.text(), builder)
));
        // citext(?) is the function-call spelling of ?::citext.
        return builder.or(
                byName,
                builder.equal(
                        candidate.get("email"),
                        builder.function("citext", String.class, builder.literal(text.text()))));
    }

    /** {@code citext(?)} is the function-call spelling of {@code ?::citext}. */
    static jakarta.persistence.criteria.Expression<String> citext(String term, CriteriaBuilder build
er) {
        return builder.function("citext", String.class, builder.literal(term));
    }

    @Override
    public String valueKind() {
        return "a name";

● Update(backend\src\main\java\com\pipeline\search\Ranking.java)
Added 5 lines, removed 5 lines
            return byName;
        }
        Expression<Double> byEmail = builder.<Double>selectCase()
                .when(builder.equal(candidate.get("email"), citext(text.text(), builder)), 1.0)
                .when(
                        builder.equal(
                                candidate.get("email"),
                                builder.function("citext", String.class, builder.literal(text.text(
)))),
                        1.0)
                .otherwise(0.0);
        return builder.function("greatest", Double.class, byName, byEmail);
    }

    private static Expression<String> citext(String term, CriteriaBuilder builder) {
        return builder.function("citext", String.class, builder.literal(term));
    }

    /** Every stage in the order the priority above ranks them, for the docs and the tests. */
    public static List<Stage> byPriority() {
        return Arrays.stream(Stage.values())

● Update(backend\src\main\java\com\pipeline\search\Ranking.java)
Added 1 line
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Why one result sorts above another, written down rather than left to vibes.

● Update(backend\src\main\java\com\pipeline\search\Ranking.java)
Added 1 line
 * every matching row. It also makes "stable across runs" free, since the ordering is a
 * deterministic function of the row and the query rather than of iteration order.
 */
@Component
public final class Ranking {

    public static final double NAME_MATCH = 0.50;

Now the application-layer port and types.

● Write(backend\src\main\java\com\pipeline\application\SearchHit.java)
Wrote 18 lines to backend\src\main\java\com\pipeline\application\SearchHit.java
package com.pipeline.application;

import java.util.List;

/**
 * A candidate that matched, and why.
 *
 * <p>{@code matchedOn} is not decoration. A fuzzy search that cannot say why it returned
 * somebody is a search the recruiter has to take on trust, and the first time it returns a
 * surprise she has no way to tell a good match she did not expect from a bug. Reading
… +8 lines

● Write(backend\src\main\java\com\pipeline\application\SearchCursor.java)
Wrote 20 lines to backend\src\main\java\com\pipeline\application\SearchCursor.java
package com.pipeline.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Keyset position for a ranked result set: the same idea as {@link Cursor}, one column
 * deeper.
 *
 * <p>The score has to be part of the key because it is what the rows are ordered by, and
… +10 lines

● Write(backend\src\main\java\com\pipeline\application\CandidateSearch.java)
Wrote 32 lines to backend\src\main\java\com\pipeline\application\CandidateSearch.java
package com.pipeline.application;

import com.pipeline.search.SearchQuery;
import java.util.List;
import java.util.UUID;

/**
 * Running a parsed query. Separate from {@link CandidateReader} because the list and the
 * board answer "show me everyone, in this order" while this answers "who matches, best
 * first" — different keys, different cursors, and only one of them ranks.
… +23 lines

● Write(backend\src\main\java\com\pipeline\application\SearchCandidates.java)
Wrote 70 lines to backend\src\main\java\com\pipeline\application\SearchCandidates.java
package com.pipeline.application;

import com.pipeline.search.SearchQuery;
import com.pipeline.search.SearchQueryParser;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
… +60 lines

Now the adapter — the one place that knows this is SQL.

● Write(backend\src\main\java\com\pipeline\infrastructure\JpaCandidateSearch.java)
Wrote 178 lines to backend\src\main\java\com\pipeline\infrastructure\JpaCandidateSearch.java
package com.pipeline.infrastructure;

import com.pipeline.application.CandidateSearch;
import com.pipeline.application.SearchCursor;
import com.pipeline.application.SearchHit;
import com.pipeline.search.Leaf;
import com.pipeline.search.Ranking;
import com.pipeline.search.SearchQuery;
import com.pipeline.search.SpecificationBuilder;
import jakarta.persistence.EntityManager;
… +172 lines

● Update(backend\src\main\java\com\pipeline\infrastructure\JpaCandidateReader.java)
Added 2 lines, removed 1 line
        return new Candidate(entity.id, entity.currentStage, entity.currentStageSince, entity.reache
dMask);
    }

    private static CandidateSummary toSummary(CandidateEntity entity) {
    /** Shared with {@link JpaCandidateSearch}, which reads the same rows down a different query. */
    static CandidateSummary toSummary(CandidateEntity entity) {
        return new CandidateSummary(
                entity.id,
                entity.fullName,

Compiling before going further.

  Ran 1 shell command

● Update(backend\src\main\java\com\pipeline\infrastructure\JpaCandidateSearch.java)
Added 6 lines, removed 5 lines
        CriteriaQuery<Tuple> criteria = builder.createTupleQuery();
        Root<CandidateEntity> candidate = criteria.from(CandidateEntity.class);

        List<Selection<?>> counts = options.stream()
                .map(option -> (Selection<?>) builder.sum(builder.<Long>selectCase()
                        .when(specifications.predicate(option.remainder(), candidate, criteria, bui
lder), 1L)
                        .otherwise(0L)))
                .toList();
        List<Selection<?>> counts = new ArrayList<>();
        for (com.pipeline.search.Relaxation option : options) {
            counts.add(builder.sum(builder.<Long>selectCase()
                    .when(specifications.predicate(option.remainder(), candidate, criteria, builder
), 1L)
                    .otherwise(0L)));
        }

        Tuple row = entities
                .createQuery(criteria.multiselect(counts).where(builder.equal(candidate.get("jobId"
), jobId)))

  Ran 1 shell command

Now the API layer. First autocomplete, which belongs with the query language.

● Write(backend\src\main\java\com\pipeline\search\Suggester.java)
Wrote 100 lines to backend\src\main\java\com\pipeline\search\Suggester.java
package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Autocomplete: what she could type next, given what she has typed so far.
 *
… +91 lines

● Update(backend\src\main\java\com\pipeline\search\Suggester.java)
Added 8 lines, removed 6 lines
                .orElseGet(List::of);
    }

    /** Where the word she is in the middle of began. Quotes hold a word together. */
    /**
     * Where the word she is in the middle of began. An unclosed quote holds a word
     * together across the space inside it, so {@code name:"priya sh} completes as one
     * token rather than as a stray {@code sh}.
     */
    private static int tokenStart(String text) {
        boolean quoted = text.chars().filter(c -> c == '"').count() % 2 == 1;
        if (quoted) {
            return text.lastIndexOf('"');
        }
        for (int i = text.length() - 1; i >= 0; i--) {
        boolean insideQuote = text.chars().filter(character -> character == '"').count() % 2 == 1;
        int from = insideQuote ? text.lastIndexOf('"') : text.length();
        for (int i = from - 1; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }

Now the API responses and controller.

● Write(backend\src\main\java\com\pipeline\api\ExplainResponse.java)
Wrote 115 lines to backend\src\main\java\com\pipeline\api\ExplainResponse.java
package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pipeline.search.Node;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.SearchQuery;
import com.pipeline.search.Span;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;
… +108 lines

● Write(backend\src\main\java\com\pipeline\api\SuggestResponse.java)
Wrote 32 lines to backend\src\main\java\com\pipeline\api\SuggestResponse.java
package com.pipeline.api;

import com.pipeline.search.Suggester;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Autocomplete. {@code replacing} is the half-written token the completions stand in for,
 * so the caller splices rather than guessing where the word began — which it cannot do
 * safely, because a quoted value holds a space inside it.
… +23 lines

● Write(backend\src\main\java\com\pipeline\api\SearchController.java)
Wrote 70 lines to backend\src\main\java\com\pipeline\api\SearchController.java
package com.pipeline.api;

import com.pipeline.application.SearchCandidates;
import com.pipeline.search.Suggester;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
… +63 lines

● Write(backend\src\main\java\com\pipeline\api\CursorCodec.java)
Added 51 lines, removed 3 lines
package com.pipeline.api;

import com.pipeline.application.Cursor;
import com.pipeline.application.SearchCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

 * something to construct. The timestamp is encoded at full precision on purpose:
 * truncating to millis would let a row sharing the truncated instant be skipped or
 * repeated at a page boundary.
 *
 * <p>Two kinds of position, because the list and a search are ordered by different keys.
 * The search form is tagged so that feeding one to the other fails as a malformed cursor
 * rather than as a page from the wrong ordering — which would quietly return the wrong
 * rows instead of an error.
 */
final class CursorCodec {

    private static final String SEARCH_TAG = "s";

    static String encode(Cursor cursor) {
        String raw = cursor.createdAt().toString() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.
UTF_8));
        return pack(cursor.createdAt() + "|" + cursor.id());
    }

    /**
     * The score goes through {@link Double#toString}, which round-trips exactly. It has
     * to: the next page compares against this value for equality to break ties between
     * candidates that scored the same, and a decimal that lost a bit would land between
     * two rows rather than on one.
     */
    static String encode(SearchCursor cursor) {
        return pack(SEARCH_TAG + "|" + Double.toString(cursor.score()) + "|" + cursor.createdAt() +
"|"
                + cursor.id());
    }

    static Cursor decode(String encoded) {
        String raw = unpack(encoded);
        if (raw.startsWith(SEARCH_TAG + "|")) {
            throw new MalformedCursorException(encoded);
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            return new Cursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substr
ing(separator + 1)));
        } catch (RuntimeException e) {

        }
    }

    static SearchCursor decodeSearch(String encoded) {
        String raw = unpack(encoded);
        try {
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4 || !SEARCH_TAG.equals(parts[0])) {
                throw new MalformedCursorException(encoded);
            }
            return new SearchCursor(Double.parseDouble(parts[1]), Instant.parse(parts[2]), UUID.from
String(parts[3]));
        } catch (MalformedCursorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MalformedCursorException(encoded);
        }
    }

    private static String pack(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.
UTF_8));
    }

    private static String unpack(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new MalformedCursorException(encoded);
        }
    }

    private CursorCodec() {}
}

● Update(backend\src\main\java\com\pipeline\api\CandidateResponse.java)
Added 30 lines, removed 2 lines
package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.SearchHit;
import com.pipeline.domain.Stage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code score} and {@code matchedOn} are present only on a search and absent from a plain
 * list, rather than null or zero, because a candidate has no relevance outside a question.
 * Omitted per property rather than by annotating the record, which would also hide a null
 * phone number and change what the list has always returned.
 */
public record CandidateResponse(
        UUID id,
        String fullName,

        Instant currentStageSince,
        @Schema(description = "ISO-8601 duration", example = "PT144H") String timeInCurrentStage,
        @Schema(example = "6 days") String timeInCurrentStageHumanised,
        Instant createdAt) {
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(description = "Search only. 0 to 1, best first.", example = "0.86")
                Double score,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(
                        description = "Search only. Why this candidate came back.",
                        example = "[\"name ~ 'sharam' (0.80)\", \"stage = Interview\"]")
                List<String> matchedOn) {

    static CandidateResponse of(CandidateSummary summary, Clock clock) {
        return of(summary, clock, null, null);
    }

    static CandidateResponse of(SearchHit hit, Clock clock) {
        return of(hit.candidate(), clock, hit.score(), hit.matchedOn());
    }

    private static CandidateResponse of(
            CandidateSummary summary, Clock clock, Double score, List<String> matchedOn) {
        Duration inStage = summary.timeInCurrentStage(clock);
        return new CandidateResponse(
                summary.id(),

                summary.currentStageSince(),
                inStage.toString(),
                DurationFormat.humanise(inStage),
                summary.createdAt());
                summary.createdAt(),
                score,
                matchedOn);
    }
}

● Write(backend\src\main\java\com\pipeline\api\CandidatePageResponse.java)
Added 47 lines, removed 1 line
package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pipeline.application.CandidateSearch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page, whether it came from the list or from a search. The search-only fields are
 * absent rather than empty on a plain list, so a client can tell "this was not a search"
 * from "this search found nothing" — which is the whole distinction {@code suggestions}
 * exists to act on.
 */
public record CandidatePageResponse(
        List<CandidateResponse> candidates,
        @Schema(description = "Pass back as ?cursor= for the next page. Null on the last page.")
                String nextCursor) {}
                String nextCursor,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(
                        description = "Search only. How the query was read; run it through /search/e
xplain for more.",
                        example = "stage:screening in_stage_for:>7d")
                String query,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(description = "Search only, and only when it found nobody: what to loosen.")
                List<Relaxation> suggestions) {

    static CandidatePageResponse list(List<CandidateResponse> candidates, String nextCursor) {
        return new CandidatePageResponse(candidates, nextCursor, null, null);
    }

    /**
     * Suggestions are a list even when empty, because on a search "we found nothing and
     * have nothing to suggest" is a real answer and should not look like a plain list.
     */
    static CandidatePageResponse search(
            List<CandidateResponse> candidates,
            String nextCursor,
            String query,
            List<CandidateSearch.Relaxation> suggestions) {
        return new CandidatePageResponse(
                candidates, nextCursor, query, suggestions.stream().map(Relaxation::of).toList());
    }

    /** "without in_stage_for:&gt;7d — 6 results". */
    record Relaxation(
            @Schema(description = "The condition to drop, in canonical form", example = "in_stage_fo
r:>7d")
                    String without,
            @Schema(description = "How many candidates the rest of the query matches", example = "6"
)
                    long results) {

        static Relaxation of(CandidateSearch.Relaxation relaxation) {
            return new Relaxation(relaxation.without(), relaxation.results());
        }
    }
}

Now wiring q= into the candidates list.

● Update(backend\src\main\java\com\pipeline\api\CandidateController.java)
Added 37 lines, removed 2 lines
    }

    @GetMapping
    @Operation(summary = "List candidates, newest first, paged by cursor rather than offset")
    @Operation(
            summary = "List candidates, newest first, or search them with ?q=",
            description =
                    """
                    Without q, the whole pipeline newest first. With q, whatever matches, best firs
t,
                    each result carrying its score and the conditions it met.

                    Both are keyset-paged, but they are ordered by different keys, so a cursor from
 one
                    is not a cursor for the other and will be rejected rather than silently paging
                    through the wrong ordering.

                    A search that matches nobody comes back with suggestions naming which single
                    condition to drop and how many candidates that would find.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A page of candidates"),
        @ApiResponse(responseCode = "422", description = "q could not be parsed; body carries the s
pan to underline")
    })
    CandidatePageResponse list(
            @Parameter(description = "Natural language or DSL", example = "stage:interview in_stage
_for:>7d")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "Opaque token from a previous page's nextCursor") @RequestPara
m(required = false)
                    String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {

        UUID jobId = jobs.singleJobId().orElseThrow(NoJobConfiguredException::new);
        if (q != null && !q.isBlank()) {
            return search(jobId, q, cursor, limit);
        }

        CandidatePage page = candidates.page(jobId, cursor == null ? null : CursorCodec.decode(curs
or), limit);
        return new CandidatePageResponse(
        return CandidatePageResponse.list(
                page.candidates().stream().map(summary -> CandidateResponse.of(summary, clock)).toL
ist(),
                page.next() == null ? null : CursorCodec.encode(page.next()));
    }

    private CandidatePageResponse search(UUID jobId, String q, String cursor, int limit) {
        SearchCandidates.Results results =
                search.search(jobId, q, cursor == null ? null : CursorCodec.decodeSearch(cursor), l
imit);
        return CandidatePageResponse.search(
                results.hits().stream().map(hit -> CandidateResponse.of(hit, clock)).toList(),
                results.next() == null ? null : CursorCodec.encode(results.next()),
                results.query().dsl(),
                results.suggestions());
    }

    @GetMapping("/{id}")
    CandidateResponse get(@PathVariable UUID id) {
        return read(id);

● Update(backend\src\main\java\com\pipeline\api\CandidateController.java)
Added 1 line
import com.pipeline.application.JobReader;
import com.pipeline.application.NoJobConfiguredException;
import com.pipeline.application.RegisterCandidate;
import com.pipeline.application.SearchCandidates;
import com.pipeline.application.TransitionCandidate;
import com.pipeline.application.TransitionOutcome;
import io.swagger.v3.oas.annotations.Operation;

● Update(backend\src\main\java\com\pipeline\api\CandidateController.java)
Added 3 lines
    private final RegisterCandidate registerCandidate;
    private final TransitionCandidate transitionCandidate;
    private final CandidateReader candidates;
    private final SearchCandidates search;
    private final EventReader events;
    private final JobReader jobs;
    private final CurrentActor actor;

            RegisterCandidate registerCandidate,
            TransitionCandidate transitionCandidate,
            CandidateReader candidates,
            SearchCandidates search,
            EventReader events,
            JobReader jobs,
            CurrentActor actor,

        this.registerCandidate = registerCandidate;
        this.transitionCandidate = transitionCandidate;
        this.candidates = candidates;
        this.search = search;
        this.events = events;
        this.jobs = jobs;
        this.actor = actor;

Now routing search traffic to the 60/min bucket from file 06.

● Update(backend\src\main\java\com\pipeline\api\RateLimitFilter.java)
Added 18 lines, removed 1 line

    private static RateLimitTier tierOf(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) ? RateLimitTier.READ : RateLimitTier.W
RITE;
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return RateLimitTier.WRITE;
        }
        return isSearch(request) ? RateLimitTier.SEARCH : RateLimitTier.READ;
    }

    /**
     * By what the request does, not by where it is mapped. A search reaches the API two
     * ways — its own endpoints, and a q= on the candidates list — and both cost a trigram
     * scan, so tiering only the tidy-looking one would leave the expensive path on the
     * 300/min read bucket.
     *
     * <p>getParameter is safe to call here because this only runs for GET and HEAD. On a
     * POST it would read the body to look for form parameters, and the controller would
     * then find nothing left to parse.
     */
    private static boolean isSearch(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/search/") || request.getParameter("q") !
= null;
    }

    /** By key where there is one, by address otherwise, so an anonymous caller is still bounded. *
/
    private static String keyOf(HttpServletRequest request) {
        String apiKey = request.getHeader(ApiKeyAuthFilter.HEADER);

  Ran 1 shell command

● Update(backend\src\main\java\com\pipeline\infrastructure\RateLimitTier.java)
Added 1 line, removed 1 line
    WRITE,
    /** Board, list, detail, timeline. */
    READ,
    /** Nothing routes here yet; the query parser in files 07 and 08 will. */
    /** Anything that parses a query and scans for matches: /search/*, and the list with a q=. */
    SEARCH
}

● Update(backend\src\test\java\com\pipeline\search\FieldRegistryTest.java)
Added 13 lines, removed 1 line
        assertThat(query.dsl()).isEqualTo("stage:interview source:referral");
        Node.And and = (Node.And) query.ast();
        assertThat(((Node.Predicate) and.children().get(1)).resolved())
                .isEqualTo(new ResolvedValue.TextValue("referral"));
                .isEqualTo(new ResolvedValue.TextValue("referral", false));
    }

    /**
     * The other half of the open/closed claim, now that a field also has to say how it
     * filters: only one handler may answer a bare word, and a second one claiming them is
     * a startup failure rather than a race decided by bean ordering.
     */
    @Test
    void twoHandlersClaimingBareTermsFailAtStartup() {
        assertThatThrownBy(() -> new FieldRegistry(List.of(new SourceField(), new GreedyField())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bare terms");
    }

    @Test
    void anUnregisteredFieldIsRejectedWithTheListOfRealOnes() {
        assertThat(SearchFixture.registry().find("source")).isEmpty();
    }

● Update(backend\src\test\java\com\pipeline\search\FieldRegistryTest.java)
Added 26 lines, removed 2 lines
        assertThat(SearchFixture.registry().find("source")).isEmpty();
    }

    private static final class SourceField implements FieldHandler {
    /**
     * Everything a new searchable field has to be, and nothing else: a name, how to read
     * its value, how to filter on it, and what to say when she leaves the value off.
     */
    private static class SourceField implements FieldHandler {

        @Override
        public String field() {


        @Override
        public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
            return new ResolvedValue.TextValue(value.text());
            return new ResolvedValue.TextValue(value.text(), false);
        }

        @Override
        public Predicate predicate(
                ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder buil
der) {
            return builder.equal(candidate.get("source"), ((ResolvedValue.TextValue) value).text());
        }

        @Override
        public String valueKind() {
            return "a source";
        }

            return List.of("referral");
        }
    }

    /** A second field that also wants bare words, which the registry must refuse. */
    private static final class GreedyField extends SourceField {

        @Override
        public String field() {
            return "greedy";
        }

        @Override
        public Optional<ResolvedValue> bareTerm(String text) {
            return Optional.of(new ResolvedValue.TextValue(text, false));
        }
    }
}

● Update(backend\src\test\java\com\pipeline\search\FieldRegistryTest.java)
Added 1 line, removed 1 line
     */
    @Test
    void twoHandlersClaimingBareTermsFailAtStartup() {
        assertThatThrownBy(() -> new FieldRegistry(List.of(new SourceField(), new GreedyField())))
        assertThatThrownBy(() -> new FieldRegistry(List.of(new GreedyField("first"), new GreedyField
("second"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bare terms");
    }

● Update(backend\src\test\java\com\pipeline\search\FieldRegistryTest.java)
Added 8 lines, removed 2 lines
        }
    }

    /** A second field that also wants bare words, which the registry must refuse. */
    /** Fields that both want bare words, which the registry must refuse to assemble. */
    private static final class GreedyField extends SourceField {

        private final String name;

        private GreedyField(String name) {
            this.name = name;
        }

        @Override
        public String field() {
            return "greedy";
            return name;
        }

        @Override

● Update(backend\src\test\java\com\pipeline\search\FieldRegistryTest.java)
Added 5 lines
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipeline.search.fields.SearchFixture;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;

 First, thank you for coming back on the 141ms. I'd rather re-decide on a true number than have a
  decision resting on a wrong one. I'm re-affirming the trade — 49ms at the scale this runs at is
  fine — but bound it rather than just documenting it. Run the loosened retry under SET LOCAL
  statement_timeout, and if it trips, drop the suggestion from the response. A missing suggestion
  costs her very little; a three-second hang costs her a lot, and with a 60/min search tier that's
  a real ceiling rather than a theoretical one. Note the enforced bound in docs/search.md next to
  the corrected figure.

  Second, on making suggestions actionable: make it a field, not a flag. Not loose= on the list
  endpoint, not candidates returned inline. A flag puts state outside the query string, which means
  the box would stop describing its own results, the interpretation chips would have nothing to show,
  and /explain would stop being the whole truth. Inline candidates make this suggestion a different
  kind of thing from the others and duplicate the paging. A field costs one more handler and touches
  neither lexer nor parser, so the extensibility claim survives. Pick the spelling you think reads
  best in a search box and tell me what you chose.
 -->

