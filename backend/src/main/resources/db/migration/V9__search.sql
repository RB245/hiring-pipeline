-- Everything the search layer needs from the database: the threshold its name matching
-- depends on, and the two functions that express "does this name match" and "how well".
-- Both live here rather than in the application because they are properties of how the
-- trigram index in V5 has to be queried, not of how Java happens to call it.


-- The word-similarity threshold, set where every connection inherits it.
--
-- It has to be set at all because the motivating query does not work at the default.
-- pg_trgm ships pg_trgm.word_similarity_threshold at 0.6 and
-- word_similarity('sharam', 'Priya Sharma') is 0.571, so "sharam" would find nobody.
-- (Plain similarity() is worse still: 0.25 against a 0.3 default. Both numbers are in
-- V5.) An unset threshold does not fail, it silently returns nothing, which is the worst
-- way this feature could break.
--
-- 0.5 rather than 0.55, which would also admit the motivating case. Measured against
-- 50k candidates, the interesting boundary is elsewhere: 0.45, 0.50 and 0.55 all admit
-- exactly the same 1667 rows for "sharam", 0.60 collapses to 111, and 0.40 starts
-- letting in genuine noise ("Vikram Singh" at 0.429). So the whole band 0.45-0.55
-- behaves identically and 0.5 sits in the middle of it, furthest from both cliffs.
--
-- Set at database level, not in the pool's init SQL, because it governs how this
-- schema's index must be read and so belongs next to the index. Every connection
-- inherits it: the pool, Testcontainers, a raw JDBC fixture, a psql session debugging a
-- plan. Hikari's connection-init-sql would cover only the first of those. A test opens a
-- connection outside the pool and asserts the value is in force.
--
-- The name is a placeholder GUC belonging to pg_trgm, so it is only recognised once the
-- extension has been loaded into the session. That is a feature here: if this statement
-- is ever removed, SHOW raises "unrecognized configuration parameter" rather than
-- quietly reporting the default, and the test fails loudly instead of subtly.
--
-- format() because the database name is not knowable at authoring time: Flyway migrates
-- the Compose database, the Testcontainers database and every throwaway database the
-- test fixture creates, and this has to apply to whichever one it is running against.
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET pg_trgm.word_similarity_threshold = 0.5', current_database());
END
$$;


-- "Is this the person she is looking for?"
--
-- A one-statement SQL function on purpose. Postgres inlines a function shaped like this
-- into the calling query, so the %> operator stays visible to the planner and the GIN
-- index in V5 is still chosen. Wrapping it in PL/pgSQL, or in anything with more than
-- one statement, would hide the operator and turn every name search into a sequential
-- scan. Verified with EXPLAIN, not assumed.
--
-- %> and not %: the plain similarity operator dilutes the match across the whole string,
-- which is exactly how "sharam" loses against "Priya Sharma". Word similarity scores the
-- best-matching run of words instead.
--
-- The indexed expression is on the left because that is the side the GIN index is built
-- on. The commuted form ('sharam' <% immutable_unaccent(full_name)) also plans to an
-- index scan, but reads backwards.
--
-- levenshtein is deliberately absent. It is not indexable, and putting it here costs
-- 141ms against 50k rows where the trigram arm costs 12ms. It belongs in the score
-- below, which only ever runs on rows this function already returned.
CREATE FUNCTION candidate_name_matches(full_name text, term text) RETURNS boolean
    LANGUAGE sql STABLE STRICT PARALLEL SAFE
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
-- depends on, and so a reviewer can score a name from psql without starting the
-- application. The weights that combine this with the other three terms stay in Java,
-- in Ranking.
--
--   exact      1.00  the whole name, accent- and case-folded
--   prefix     0.85  some word of the name starts with what she typed
--   trigram          word_similarity, the same measure the filter above thresholds on
--   typo       0.80  within the edit-distance budget of some word of the name
--
-- The typo floor is a floor, not a replacement: the score is the better of it and the
-- trigram figure, so a strong trigram match is never dragged down to 0.80 by also
-- happening to be within two edits.
--
-- The budget mirrors Levenshtein.budgetFor in the parser, which offers spelling
-- corrections on the same rule: two edits normally, one for a term of four characters or
-- fewer, so that a short typo does not match half the alphabet.
--
-- Worth knowing, because it is the case this floor exists for: trigram alone cannot see
-- a transposition in a short word. word_similarity('pryia', 'Priya Sharma') is 0.333, so
-- no usable threshold admits it, while levenshtein('pryia', 'priya') is 2.
CREATE FUNCTION candidate_name_score(full_name text, term text) RETURNS double precision
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$
    WITH folded AS (
        SELECT lower(immutable_unaccent(full_name)) AS name,
               lower(immutable_unaccent(term))      AS needle
    )
    SELECT CASE
        WHEN f.name = f.needle THEN 1.0::double precision
        WHEN f.name LIKE f.needle || '%' OR f.name LIKE '% ' || f.needle || '%' THEN 0.85::double precision
        ELSE greatest(
            word_similarity(f.needle, f.name)::double precision,
            CASE WHEN EXISTS (
                SELECT 1 FROM unnest(string_to_array(f.name, ' ')) AS word
                WHERE levenshtein_less_equal(word, f.needle, 2)
                      <= CASE WHEN length(f.needle) <= 4 THEN 1 ELSE 2 END)
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
