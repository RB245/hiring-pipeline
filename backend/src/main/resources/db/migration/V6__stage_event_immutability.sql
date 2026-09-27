-- Layer 1 of three. The other two are the privilege split in V7 and the absence of any
-- ON DELETE CASCADE reaching this table in V4. Each is meant to hold if the others are
-- removed by mistake.
--
-- Raised without an explicit ERRCODE so it surfaces as P0001, distinct from the 42501
-- that the V7 revoke produces. The two layers are therefore distinguishable in tests,
-- which is the only way to know both are actually working rather than one masking the
-- other.
CREATE FUNCTION stage_event_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'stage_event is append-only; % is not permitted', TG_OP;
END
$$;

CREATE TRIGGER stage_event_no_update_or_delete
    BEFORE UPDATE OR DELETE ON stage_event
    FOR EACH ROW EXECUTE FUNCTION stage_event_reject_mutation();

-- TRUNCATE does not fire row-level UPDATE or DELETE triggers, so the trigger above
-- would let it through. Statement-level, because TRUNCATE has no rows to iterate.
CREATE TRIGGER stage_event_no_truncate
    BEFORE TRUNCATE ON stage_event
    FOR EACH STATEMENT EXECUTE FUNCTION stage_event_reject_mutation();
