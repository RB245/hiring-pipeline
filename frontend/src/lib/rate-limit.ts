import "server-only";

/**
 * A per-session budget in front of the backend's own tiers.
 *
 * <p>It is not a security boundary — the backend already limits by API key, and that is the
 * limit that protects the database. This one protects the *other* sessions: because every
 * browser shares one API key, a single tab stuck in a render loop would spend the whole
 * key's 60-per-minute search budget and every other recruiter would see 429s they did
 * nothing to cause. Splitting the budget per session turns that into one broken tab.
 *
 * <p>In process, so it resets on restart and is not shared between replicas. That is
 * honest for one recruiter and one container; a second replica would need the Redis the
 * backend already uses for the same job, and this comment is the tripwire for whoever adds
 * that replica.
 */

const WINDOW_MS = 60_000;

/**
 * 240 a minute. A settled keystroke costs three calls — explain, suggest, and the results —
 * so a fast typist against a 250ms debounce can legitimately spend around 180, and the
 * board costs a few more. Low enough to stop a loop within a second or two, high enough
 * that nobody types their way into it.
 */
const LIMIT = Number(process.env.SESSION_RATE_LIMIT ?? 240);

type Bucket = { tokens: number; resetAt: number };

const buckets = new Map<string, Bucket>();

export type Verdict = { allowed: boolean; limit: number; remaining: number; resetAfter: number };

export function takeToken(sessionId: string, now = Date.now()): Verdict {
  const bucket = buckets.get(sessionId);
  if (!bucket || now >= bucket.resetAt) {
    buckets.set(sessionId, { tokens: LIMIT - 1, resetAt: now + WINDOW_MS });
    return { allowed: true, limit: LIMIT, remaining: LIMIT - 1, resetAfter: WINDOW_MS / 1000 };
  }

  const resetAfter = Math.ceil((bucket.resetAt - now) / 1000);
  if (bucket.tokens <= 0) {
    return { allowed: false, limit: LIMIT, remaining: 0, resetAfter };
  }
  bucket.tokens -= 1;
  return { allowed: true, limit: LIMIT, remaining: bucket.tokens, resetAfter };
}

/**
 * Sessions that have gone quiet, dropped on write rather than on a timer. A Map that only
 * ever grows is a leak; a sweep on each miss keeps it proportional to who is actually here
 * without needing an interval nobody would remember to clear.
 */
export function forgetExpired(now = Date.now()): void {
  for (const [id, bucket] of buckets) {
    if (now >= bucket.resetAt) {
      buckets.delete(id);
    }
  }
}
