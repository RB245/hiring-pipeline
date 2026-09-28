import "server-only";

/**
 * The only thing in this application that knows the API key exists.
 *
 * <p>`server-only` is not decoration: it turns "the browser must never talk to Spring
 * directly" from a convention into a build error. Import this from a client component and
 * the bundle fails rather than shipping the key to a browser, which is the one mistake in
 * this design that would be both silent and unrecoverable.
 */

const BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

/**
 * No default, deliberately, matching the backend's own rule about this: a default key is a
 * key in the repository. Failing at the first request with a clear message beats starting
 * and returning 401s that look like a backend problem.
 */
function apiKey(): string {
  const key = process.env.API_KEY;
  if (!key) {
    throw new Error("API_KEY is not set. The BFF cannot authenticate to the backend without it.");
  }
  return key;
}

/** One request to Spring, with the key attached. Returns the raw response; callers parse. */
export async function spring(path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      ...init?.headers,
      "X-API-Key": apiKey(),
      Accept: "application/json",
    },
    // The board and every search are per-request truth; a cached board would show a
    // recruiter a candidate she has just moved.
    cache: "no-store",
  });
}
