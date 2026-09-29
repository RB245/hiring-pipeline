# ADR 0003 — Append-only history, enforced by the database

Status: accepted

## Context

`stage_event` is the audit trail. Its value depends entirely on being trustworthy: a
history that could have been edited is not evidence of anything.

Enforcing that in application code is the normal approach and it is worth roughly nothing.
Anyone with the connection string can `UPDATE`, and so can a future bug in a code path
nobody reviewed.

## Decision

Three layers, each of which holds when the one above it fails.

1. **Hibernate `@Immutable`** on the entity, so the ORM never generates an `UPDATE`.
2. **A trigger** (`V6`) that raises on `UPDATE`, `DELETE` and `TRUNCATE`. Row-level
   triggers do not fire on `TRUNCATE`, so there is a separate statement-level trigger for
   it — without which the guarantee has a hole big enough to empty the table through.
3. **Role separation** (`V7`). Flyway migrates as an identity that owns the schema. The
   application connects as `pipeline_app`, which holds `SELECT, INSERT` on `stage_event`,
   `SELECT, INSERT, UPDATE` on `candidate`, and no DDL.

The third layer is what makes the second one mean something: the application cannot drop
the trigger, because dropping it is DDL that its role does not have.

## Consequences

The claim "this history cannot be edited" is true of the database, not of the code, and
the integration tests run as `pipeline_app` so a missing grant fails in CI rather than in
production.

Correcting a mistake means appending a compensating event, never editing one. That is the
right behaviour for an audit trail and it is occasionally inconvenient.

Deleting a candidate is impossible while they have history, because the foreign key is
`RESTRICT` rather than `CASCADE` in both directions. Retiring a candidate would be a flag
on `candidate`, not a `DELETE`. This is deliberate: `CASCADE` would let a routine cleanup
reach through and erase the audit trail.

The UI says so too — the drawer carries an "append-only" badge explaining that the
database refuses the edit, not just the screen.
