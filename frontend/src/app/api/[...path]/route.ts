import { NextRequest, NextResponse } from "next/server";
import { randomUUID } from "node:crypto";
import { spring } from "@/lib/api";
import { forgetExpired, takeToken } from "@/lib/rate-limit";

/**
 * The BFF. Everything the browser asks for comes through here, gets the API key attached,
 * and is spent against a per-session budget on the way.
 *
 * <p>One handler rather than one per endpoint, because seven near-identical files would be
 * seven places to forget the key or the budget. What keeps that from being a hole punched
 * through to the whole backend is the allowlist below: a path that is not on it is a 404
 * here and never reaches Spring.
 */

const SESSION_COOKIE = "pipeline_session";

/**
 * Exactly what the three screens need, and nothing adjacent.
 *
 * <p>Notably absent: /admin/rebuild-projections, which would let any browser rewrite every
 * projection in the database. It is a real endpoint with real authority and nothing this UI
 * does, so it is not reachable from it. An allowlist rather than a denylist, so the next
 * endpoint added to the backend is closed until somebody opens it — POST /candidates was
 * closed here until the board grew a way to add one.
 */
const ALLOWED: ReadonlyArray<{ method: string; path: RegExp }> = [
  { method: "GET", path: /^\/api\/v1\/pipeline$/ },
  { method: "GET", path: /^\/api\/v1\/candidates$/ },
  { method: "POST", path: /^\/api\/v1\/candidates$/ },
  { method: "GET", path: /^\/api\/v1\/candidates\/[0-9a-f-]{36}$/ },
  { method: "GET", path: /^\/api\/v1\/candidates\/[0-9a-f-]{36}\/events$/ },
  { method: "POST", path: /^\/api\/v1\/candidates\/[0-9a-f-]{36}\/transitions$/ },
  { method: "GET", path: /^\/api\/v1\/search\/explain$/ },
  { method: "GET", path: /^\/api\/v1\/search\/suggest$/ },
];

function isAllowed(method: string, path: string): boolean {
  return ALLOWED.some((rule) => rule.method === method && rule.path.test(path));
}

/** RFC 9457, the same envelope the backend uses, so a client has one error shape to read. */
function problem(status: number, title: string, detail: string): NextResponse {
  return NextResponse.json(
    { type: `https://pipeline.example/problems/${title}`, title, status, detail },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

async function proxy(request: NextRequest, path: string[]): Promise<NextResponse> {
  // The catch-all sits at /api, so `path` already begins with the API version.
  const target = `/api/${path.join("/")}`;
  if (!isAllowed(request.method, target)) {
    return problem(404, "not-found", `${request.method} ${target} is not exposed by this application`);
  }

  // Set before anything can fail, so a session that is being rate-limited still has an
  // identity to be limited by rather than getting a fresh budget on every retry.
  const existing = request.cookies.get(SESSION_COOKIE)?.value;
  const sessionId = existing ?? randomUUID();

  forgetExpired();
  const verdict = takeToken(sessionId);

  const response = verdict.allowed
    ? await forward(request, target)
    : problem(429, "session-rate-limited", `This session may make ${verdict.limit} requests a minute.`);

  response.headers.set("X-Session-RateLimit-Limit", String(verdict.limit));
  response.headers.set("X-Session-RateLimit-Remaining", String(verdict.remaining));
  if (!verdict.allowed) {
    response.headers.set("Retry-After", String(verdict.resetAfter));
  }
  if (!existing) {
    response.cookies.set(SESSION_COOKIE, sessionId, {
      httpOnly: true,
      sameSite: "lax",
      path: "/",
      maxAge: 60 * 60 * 24,
    });
  }
  return response;
}

async function forward(request: NextRequest, target: string): Promise<NextResponse> {
  const query = request.nextUrl.search;
  const body = request.method === "POST" ? await request.text() : undefined;

  let upstream: Response;
  try {
    upstream = await spring(`${target}${query}`, {
      method: request.method,
      body,
      headers: body ? { "Content-Type": "application/json" } : {},
    });
  } catch {
    // The backend being down is not the recruiter's mistake and should not look like one.
    return problem(502, "backend-unreachable", "The pipeline service is not responding.");
  }

  // Passed through rather than re-shaped. A 422 from the search parser carries the span the
  // input box underlines and the alternatives it offers as chips; rewriting it here would
  // mean maintaining a second copy of an error contract that is already precise.
  const text = await upstream.text();
  return new NextResponse(text, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("Content-Type") ?? "application/json",
    },
  });
}

export async function GET(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  return proxy(request, (await context.params).path);
}

export async function POST(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  return proxy(request, (await context.params).path);
}
