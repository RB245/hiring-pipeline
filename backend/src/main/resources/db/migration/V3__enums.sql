-- Declaration order is load-bearing and permanent: Postgres sorts enum values by the
-- order they were declared, and that order cannot be changed later without rewriting
-- the type. Declared here in pipeline order so ORDER BY current_stage sorts the board
-- left to right for free. REJECTED sits last because it is an exit, not a position.
CREATE TYPE stage AS ENUM (
    'APPLIED',
    'SCREENING',
    'INTERVIEW',
    'OFFER',
    'HIRED',
    'REJECTED'
);

CREATE TYPE event_type AS ENUM (
    'APPLIED',
    'ADVANCED',
    'REJECTED',
    'HIRED'
);
