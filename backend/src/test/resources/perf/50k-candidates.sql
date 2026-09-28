-- The dataset every performance number in docs/search.md was measured against.
--
-- Here because a reproduction step that says "generate 50k candidates" is not a
-- reproduction step. Run it against a database that has had V1-V9 applied, as the
-- migration role.
--
-- It is not a fixture and nothing in the suite loads it. Seeding 50k rows on every build
-- to check numbers that change only when the query shapes do is the wrong trade; see
-- ExplainPassTest for how it is actually used.
--
-- The shape mirrors what SeedPipeline produces, because the planner's choices depend on
-- it: a linear path of one to four non-terminal stages, optionally ending in HIRED or
-- REJECTED, with one stage_event per stage entered. That yields about ten distinct
-- reached_mask values and roughly three events per candidate, which is what makes the
-- reached: and moved_to: measurements mean anything.

INSERT INTO candidate (id, job_id, full_name, email, current_stage, current_stage_since,
                       reached_mask, created_at, version)
SELECT
    md5('seed-' || i)::uuid,
    '00000000-0000-0000-0000-000000000001'::uuid,
    (ARRAY['Priya','Rahul','Ananya','Vikram','Meera','Arjun','Divya','Karthik','Sneha','Rohit',
           'Aisha','Tanvi','Nikhil','Farah','Imran','Lakshmi','Sanjay','Neha','Aditya','Kavya',
           'Zoe','Mateo','Chidi','Yuki','Elena','Omar','Ingrid','Tomas','Amara','Sofia'])[1 + (i % 30)]
      || ' ' ||
    (ARRAY['Sharma','Verma','Iyer','Nair','Reddy','Kulkarni','Banerjee','Chatterjee','Pillai','Rao',
           'Khan','Singh','Mehta','Bose','Gupta','Joshi','Menon','Desai','Kaur','Patel',
           'Muller','Garcia','Okafor','Tanaka','Rossi','Haddad','Larsen','Novak','Diallo','Costa'])[1 + ((i / 30 + i) % 30)],
    'c' || i || '@example.com',
    st.current_stage,
    now() - make_interval(days => (i % 90)),
    st.mask,
    -- Spread across a 90-day window at second resolution rather than by whole days. With
    -- only 90 distinct values the unfiltered-list measurement would be about breaking ties
    -- on id rather than about sorting, which is not the thing being measured.
    now() - make_interval(secs => (('x' || substr(md5('seed-' || i), 1, 8))::bit(32)::bigint % 7776000)),
    0
FROM generate_series(0, 49999) AS i
CROSS JOIN LATERAL (
    SELECT
        CASE d
            WHEN 0 THEN 'APPLIED'   WHEN 1 THEN 'SCREENING' WHEN 2 THEN 'INTERVIEW'
            WHEN 3 THEN 'OFFER'     WHEN 4 THEN 'HIRED'     ELSE 'REJECTED'
        END::stage AS current_stage,
        CASE d
            WHEN 0 THEN 1   WHEN 1 THEN 3   WHEN 2 THEN 7
            WHEN 3 THEN 15  WHEN 4 THEN 31  ELSE (CASE WHEN i % 7 = 0 THEN 15 + 32 ELSE 3 + 32 END)
        END::smallint AS mask
    FROM (SELECT (i * 7919) % 6 AS d) AS pick
) AS st;

-- One event per bit set in reached_mask, in pipeline order.
INSERT INTO stage_event (candidate_id, seq, from_stage, to_stage, event_type,
                         occurred_at, actor_id, actor_name)
SELECT c.id,
       row_number() OVER (PARTITION BY c.id ORDER BY s.ord),
       lag(s.stage) OVER (PARTITION BY c.id ORDER BY s.ord),
       s.stage,
       CASE WHEN s.stage = 'APPLIED'  THEN 'APPLIED'
            WHEN s.stage = 'HIRED'    THEN 'HIRED'
            WHEN s.stage = 'REJECTED' THEN 'REJECTED'
            ELSE 'ADVANCED' END::event_type,
       c.created_at + make_interval(days => s.ord * 3),
       'recruiter-1', 'Asha Menon'
FROM candidate c
JOIN LATERAL (
    VALUES ('APPLIED'::stage, 1, 1), ('SCREENING', 2, 2), ('INTERVIEW', 4, 3),
           ('OFFER', 8, 4), ('HIRED', 16, 5), ('REJECTED', 32, 6)
) AS s(stage, bit, ord) ON (c.reached_mask & s.bit) = s.bit;

-- Without this the planner is working from defaults and every plan below is a guess.
ANALYZE candidate;
ANALYZE stage_event;
