-- The single job opening, created by the migration rather than by the application.
--
-- pipeline_app holds SELECT and nothing else on this table, which is the point: an
-- application that manages one opening has no business creating openings. That leaves
-- the migrator as the only identity that can put it here, so here is where it goes.
--
-- Fixed id and a fixed created_at: JobReader resolves "the" job by earliest created_at,
-- and a stable answer is what lets seeding and the tests agree on which job they mean.
INSERT INTO job (id, title, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Backend Engineer', timestamptz '2024-01-01 00:00:00Z')
ON CONFLICT (id) DO NOTHING;
