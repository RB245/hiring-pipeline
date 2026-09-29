# Hiring pipeline — architecture

One job opening, six stages, an append-only history, and a search box that explains itself.

Source: <https://github.com/RB245/hiring-pipeline>

This document is the source for `architecture.pdf`. The diagrams are Mermaid, so they are
diffable and editable rather than pasted images; `cd docs/tooling && npm run pdf` rebuilds
the PDF from this file.

---

## 1. Context

Who talks to what, and where the secret lives. The browser never reaches Spring: the
Next.js BFF holds the API key, and an endpoint the UI does not use is not reachable
through it.

```mermaid
graph LR
    R["Recruiter<br/>(browser)"]
    F["Next.js BFF<br/>Route Handler<br/>holds API key<br/>per-session budget"]
    B["Spring Boot API<br/>tiered rate limits<br/>API-key auth"]
    P[("PostgreSQL 16<br/>pg_trgm · unaccent<br/>fuzzystrmatch · citext")]
    X[("Redis<br/>shared rate-limit buckets")]

    R -->|"HTTPS, no key"| F
    F -->|"X-API-Key"| B
    B -->|"pipeline_app<br/>SELECT/INSERT/UPDATE only"| P
    B -.->|"optional backend"| X

    classDef secret fill:#fff3cd,stroke:#b8860b
    class F,B secret
```

The two roles matter. Flyway migrates as `pipeline`, which owns the schema; the
application connects as `pipeline_app`, which holds `SELECT, INSERT, UPDATE` on
`candidate`, `SELECT, INSERT` on `stage_event`, and no DDL at all. The application cannot
drop the trigger that stops it rewriting history, because dropping it is DDL.

---

## 2. Components

Ports point inward. The domain knows nothing about Spring, JPA or HTTP, and an ArchUnit
test fails the build if that stops being true.

```mermaid
graph TB
    subgraph api["com.pipeline.api — adapters in"]
        CC[CandidateController]
        SC[SearchController]
        PC[PipelineController]
        PD[ProblemDetails<br/>RFC 9457]
        RL[RateLimitFilter<br/>write · read · search]
    end

    subgraph app["com.pipeline.application — use cases and ports"]
        RC[RegisterCandidate]
        TC[TransitionCandidate]
        SCand[SearchCandidates]
        direction TB
        PORTS["ports:<br/>CandidateReader · CandidateWriter<br/>CandidateSearch · EventReader"]
    end

    subgraph dom["com.pipeline.domain — no framework"]
        ST[StageTransitions]
        TR[TransitionRules<br/>AdvanceRule · RejectRule]
        CA[Candidate · StageEvent]
    end

    subgraph srch["com.pipeline.search — the query language"]
        NZ[Normalizer → Lexer → Parser → Validator]
        SB[SpecificationBuilder]
        RK[Ranking]
        FH["FieldHandler ×11<br/>one class per field"]
    end

    subgraph inf["com.pipeline.infrastructure — adapters out"]
        JR[JpaCandidateReader/Writer]
        JS[JpaCandidateSearch]
        RLA[Caffeine / Lettuce limiter]
    end

    CC --> RC & TC & SCand
    SC --> SCand
    PC --> PORTS
    RC & TC --> ST --> TR
    SCand --> NZ
    SCand --> PORTS
    SB --> FH
    JS --> SB & RK
    JR & JS -.implements.-> PORTS

    classDef pure fill:#e8f5e9,stroke:#2e7d32
    class dom pure
```

`CandidateSearch` is a port. Swapping Postgres trigram for OpenSearch is a new class in
`infrastructure` implementing that interface — no change to the domain, the use cases, or
the parser.

---

## 3. Data model

Three tables. `stage_event` is the source of truth; the projection columns on `candidate`
are a cache of it that can be rebuilt at any time.

```mermaid
erDiagram
    job ||--o{ candidate : "opens"
    candidate ||--|{ stage_event : "accumulates"

    job {
        uuid id PK
        text title
        timestamptz created_at
    }

    candidate {
        uuid id PK
        uuid job_id FK
        text full_name
        citext email "unique per job"
        text phone
        text source
        stage current_stage "projection"
        timestamptz current_stage_since "projection"
        smallint reached_mask "projection, bitfield"
        boolean is_terminal "generated"
        timestamptz created_at
        integer version "optimistic lock"
    }

    stage_event {
        bigserial id PK
        uuid candidate_id FK
        integer seq "per candidate, from 1"
        stage from_stage "null only when seq = 1"
        stage to_stage
        event_type event_type
        timestamptz occurred_at
        text actor_id
        text actor_name
        text reason
        text idempotency_key "partial unique"
    }
```

`reached_mask` is a bitfield of every stage ever entered: APPLIED 1, SCREENING 2,
INTERVIEW 4, OFFER 8, HIRED 16, REJECTED 32. It turns "reached Offer but was never hired"
into a predicate on one row instead of a semi-join against the whole event log.

---

## 4. The search pipeline

No model anywhere in this path. Every stage is deterministic, and every token and node
carries two spans — one into the normalised DSL, one into the characters she typed — which
is what lets the input box underline the exact wrong word.

```mermaid
graph LR
    S["“Who has been stuck in<br/>Screening for more than a week?”"]
    N["Normalizer<br/>ordered rewrite table"]
    L["Lexer<br/>tokens + spans"]
    G["QueryGuards<br/>512 chars · 12 predicates<br/>5 deep"]
    P["Parser<br/>recursive descent"]
    V["Validator<br/>resolves stages, dates,<br/>durations vs the Clock"]
    D["stage:screening<br/>in_stage_for:>7d"]
    SBd["SpecificationBuilder<br/>composes and/or/not only"]
    FHd["FieldHandler.predicate<br/>per field"]
    Rk["Ranking<br/>score in SQL"]
    Q["one query:<br/>rows + score + why"]

    S --> N --> L --> G --> P --> V --> D
    D --> SBd --> Q
    SBd -.asks.-> FHd
    Rk --> Q

    classDef err fill:#ffebee,stroke:#c62828
    E["SearchQueryException<br/>code · message · span · didYouMean<br/>→ 422 Problem Details"]
    L -.-> E
    G -.-> E
    P -.-> E
    V -.-> E
    class E err
```

A zero-result query is re-asked: each top-level condition dropped in turn, and any fuzzy
term retried against `name_like:`, all counted in two passes and offered as complete
queries she can run.

---

## 5. A transition

The only endpoint that mutates pipeline state, and the one place the transaction boundary
matters. The projection update and the event append are the same transaction: either both
happen or neither does, so the cache can never disagree with the log.

```mermaid
sequenceDiagram
    autonumber
    participant UI as Browser
    participant BFF as Next.js BFF
    participant C as CandidateController
    participant UC as TransitionCandidate
    participant D as StageTransitions (domain)
    participant W as CandidateWriter
    participant DB as PostgreSQL

    UI->>BFF: POST /candidates/{id}/transitions<br/>{expectedCurrentStage, toStage}
    Note over UI: card already moved<br/>(optimistic)
    BFF->>C: + X-API-Key
    C->>UC: transition(id, expected, to, actor, key)

    rect rgb(232, 245, 233)
        Note over UC,DB: one transaction
        UC->>DB: SELECT candidate FOR read
        UC->>UC: expected == current?
        alt stale view
            UC-->>C: StaleCandidateStateException
            C-->>BFF: 409 + actualCurrentStage
            BFF-->>UI: 409
            Note over UI: roll back, say who moved it
        else
            UC->>D: transition(candidate, to)
            D->>D: rules.isLegal(from, to)?
            D-->>UC: TransitionDecision<br/>event + newStage + reachedMask
            UC->>W: appendEvent(event)
            W->>DB: INSERT stage_event (seq, idempotency_key)
            UC->>W: updateProjection(stage, since, mask)
            W->>DB: UPDATE candidate (version++)
        end
    end

    DB-->>UC: commit
    UC-->>C: TransitionOutcome
    C-->>BFF: 201 + the event
    BFF-->>UI: 201
```

Three things are load-bearing here. `expectedCurrentStage` is what makes the optimistic
board safe. The unique index on `(candidate_id, idempotency_key)` makes a retry a no-op
rather than a second event. And `version` on `candidate` makes two simultaneous
transitions resolve as one winner and one 409.

---

## 6. Scalability

What this design does and does not hold, with the numbers that are actually measured. All
figures come from `ExplainPassTest`, which is **skipped in a normal build** — see the
caveat in `docs/search.md`.

**Measured at 50k candidates and 152k events**, on the SQL Hibernate emits:

| Query | Plan | Time |
|---|---|---|
| `stage:interview` | `candidate_stage_idx` | 44 ms |
| `stage:interview in_stage_for:>7d` | `candidate_active_since_idx` | 34 ms |
| `moved_to:interview since:monday` | `stage_event_to_stage_occurred_idx`, semi-join | 0.1 ms |
| `name:sharma` | `candidate_name_trgm_idx` | 149 ms |
| `reached:offer -status:hired` | sequential scan, by design | 65 ms |

**Where it bends first.** Name search is dominated by scoring, not filtering: the indexed
filter is 16 ms and the rest is `candidate_name_score` over every match. Ranking has to
score every match before the top-N sort, so this grows with the size of the result set
rather than the table. The edit-distance retry behind a zero-result suggestion is worse —
3.0 s at 50k — which is why it runs under a one-second statement timeout and is dropped
rather than allowed to hang.

**What the shape already allows.** `stage_event` is append-only and always queried by
`candidate_id` or by `(to_stage, occurred_at)`, so monthly range partitioning is a
migration rather than a redesign. Reads go through `CandidateReader` and `CandidateSearch`
and never write, so routing them to a replica is a connection choice. And the search port
is the reason an OpenSearch adapter would be a new class in `infrastructure` and nothing
else: the domain has never heard of trigrams.

**What it will not hold.** One job opening is assumed throughout — `JobReader.singleJobId()`
is called by every read path. Multi-tenancy means a tenant column, a filter in every
handler, and row-level security; it is a schema change, not a scaling knob.
