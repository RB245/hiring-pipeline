CREATE TABLE job (
    id         uuid PRIMARY KEY,
    title      text        NOT NULL,
    -- No DEFAULT now() anywhere in this schema. The application owns a single injected
    -- Clock so that "how long has this candidate been sitting here" is deterministic in
    -- tests; a database-side default would be a second, unfakeable source of time.
    created_at timestamptz NOT NULL
);

CREATE TABLE candidate (
    id        uuid PRIMARY KEY,
    job_id    uuid NOT NULL,
    full_name text   NOT NULL,
    -- citext so "Priya@Example.com" and "priya@example.com" collide on the unique
    -- constraint below rather than becoming two candidates.
    email     citext NOT NULL,
    phone     text,
    source    text,

    -- Projection of the event log, written in the same transaction as the event that
    -- moves it. The log remains the source of truth; these columns exist so the board
    -- and the "stuck for a week" query do not aggregate stage_event on every request.
    current_stage       stage       NOT NULL,
    current_stage_since timestamptz NOT NULL,

    -- Bitmask of every stage ever entered: APPLIED 1, SCREENING 2, INTERVIEW 4,
    -- OFFER 8, HIRED 16, REJECTED 32. Turns "reached Offer but was never hired" into a
    -- predicate on this row instead of a semi-join against the whole event log.
    reached_mask smallint NOT NULL,

    -- Derived, not stored judgement: exists so partial indexes can say WHERE NOT
    -- is_terminal. Postgres can prove a query implies that predicate trivially; proving
    -- the equivalent current_stage NOT IN ('HIRED','REJECTED') is beyond its prover.
    is_terminal boolean GENERATED ALWAYS AS (current_stage IN ('HIRED', 'REJECTED')) STORED,

    created_at timestamptz NOT NULL,

    -- JPA optimistic locking. Two recruiters advancing the same candidate concurrently
    -- must not both win and write conflicting projections.
    version integer NOT NULL,

    CONSTRAINT candidate_job_email_uq UNIQUE (job_id, email),

    -- RESTRICT, never CASCADE: deleting a job must not be able to reach through and
    -- erase candidates, and through them the audit trail.
    CONSTRAINT candidate_job_fk FOREIGN KEY (job_id) REFERENCES job (id) ON DELETE RESTRICT
);

CREATE TABLE stage_event (
    id           bigserial PRIMARY KEY,
    candidate_id uuid NOT NULL,

    -- Per-candidate ordinal starting at 1. Gives the timeline a total order that does
    -- not depend on occurred_at, which two events in the same transaction can share.
    seq integer NOT NULL,

    from_stage stage,
    to_stage   stage      NOT NULL,
    event_type event_type NOT NULL,

    occurred_at timestamptz NOT NULL,
    actor_id    text        NOT NULL,
    actor_name  text        NOT NULL,
    reason      text,

    -- Set by the caller on a transition request. The partial unique index in V5 makes a
    -- retried or double-clicked transition a no-op rather than a second event.
    idempotency_key text,

    CONSTRAINT stage_event_seq_positive CHECK (seq >= 1),

    -- Exactly the first event has no origin stage, and every later one has one. Written
    -- as an equivalence so it catches both directions: a first event that claims an
    -- origin, and a later event missing one.
    CONSTRAINT stage_event_first_has_no_from CHECK ((seq = 1) = (from_stage IS NULL)),

    -- IS DISTINCT FROM, not <>, so the NULL from_stage on the first event passes.
    CONSTRAINT stage_event_moves_somewhere CHECK (from_stage IS DISTINCT FROM to_stage),

    CONSTRAINT stage_event_candidate_seq_uq UNIQUE (candidate_id, seq),

    -- RESTRICT for the same reason as above, and more strongly: this is the audit trail.
    -- Removing a candidate must fail loudly, not silently take their history with it.
    -- If a candidate ever genuinely needs removing, that is a flag on candidate, not a
    -- DELETE.
    CONSTRAINT stage_event_candidate_fk FOREIGN KEY (candidate_id) REFERENCES candidate (id) ON DELETE RESTRICT
);
