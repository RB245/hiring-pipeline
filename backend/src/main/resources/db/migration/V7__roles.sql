-- Layer 2 of three: the application cannot rewrite history even if the V6 trigger is
-- dropped, because dropping it is itself DDL that the application role does not have.
-- That separation is the whole point; a single role holding both would make the
-- revokes below decorative.
--
-- Roles are cluster-wide, not per-database, so these are guarded rather than created
-- blindly. A migration cannot be run by a role it is in the middle of creating, so the
-- bootstrap identity is whoever Flyway connects as; pipeline_migrator is the ownership
-- identity it hands the schema to.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_migrator') THEN
        -- NOLOGIN deliberately: nothing connects as the owner. DDL authority comes from
        -- owning the objects, and membership is how a migration run acquires it.
        CREATE ROLE pipeline_migrator NOLOGIN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_app') THEN
        CREATE ROLE pipeline_app LOGIN PASSWORD '${app_password}';
    ELSE
        ALTER ROLE pipeline_app LOGIN PASSWORD '${app_password}';
    END IF;
END
$$;

-- So that later migrations, run by whatever identity Flyway uses, can still alter
-- objects now owned by pipeline_migrator without needing superuser.
GRANT pipeline_migrator TO CURRENT_USER;

ALTER TABLE job         OWNER TO pipeline_migrator;
ALTER TABLE candidate   OWNER TO pipeline_migrator;
ALTER TABLE stage_event OWNER TO pipeline_migrator;
ALTER TYPE stage      OWNER TO pipeline_migrator;
ALTER TYPE event_type OWNER TO pipeline_migrator;
ALTER FUNCTION immutable_unaccent(text)       OWNER TO pipeline_migrator;
ALTER FUNCTION stage_event_reject_mutation()  OWNER TO pipeline_migrator;

GRANT USAGE ON SCHEMA public TO pipeline_app;

-- SELECT only. The single job opening is created by migration or seed, not by the app.
GRANT SELECT ON job TO pipeline_app;

-- UPDATE is required: the projection columns on candidate move with every transition.
-- DELETE is withheld, because removing a candidate would orphan or destroy their
-- history. Retiring a candidate is a flag, not a DELETE.
GRANT SELECT, INSERT, UPDATE ON candidate TO pipeline_app;

GRANT SELECT, INSERT ON stage_event TO pipeline_app;

-- Explicit and currently redundant: nothing granted these in the first place. Stated
-- anyway so that the intent survives someone later writing a broad GRANT ALL, and so
-- that reading this file tells you what the application may not do.
REVOKE UPDATE, DELETE, TRUNCATE ON stage_event FROM pipeline_app;
REVOKE DELETE, TRUNCATE ON candidate FROM pipeline_app;

-- bigserial is useless to an inserter without this.
GRANT USAGE ON SEQUENCE stage_event_id_seq TO pipeline_app;
