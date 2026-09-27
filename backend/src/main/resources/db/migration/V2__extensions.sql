CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
CREATE EXTENSION IF NOT EXISTS citext;

-- unaccent() is declared STABLE, not IMMUTABLE, because a dictionary can be reloaded
-- underneath it. Postgres therefore refuses it in an index expression. Pinning the
-- dictionary by regdictionary removes that variability, which is what lets us promise
-- IMMUTABLE honestly and build the trigram index in V5.
--
-- The schema qualification is not cosmetic: an index expression is re-resolved with
-- whatever search_path is active at query time, so an unqualified call could silently
-- bind to a different unaccent() and quietly stop matching the index.
CREATE FUNCTION immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;
