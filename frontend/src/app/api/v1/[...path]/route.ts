import type { NextRequest } from "next/server";

// Server-side proxy to the exchange API. It keeps the API key out of the browser and lets the UI
// stay same-origin (no CORS). It forwards /api/v1/* only. A real deployment would put user
// authentication here and map each user to their own key or account.
const BACKEND = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
const API_KEY = process.env.LUMINA_API_KEY;

export const dynamic = "force-dynamic";

const FORWARDED_REQUEST_HEADERS = ["content-type", "idempotency-key", "accept"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "cache-control", "retry-after"];

async function proxy(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await context.params;
  const target = `${BACKEND}/api/v1/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`;

  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  if (API_KEY) headers.set("x-api-key", API_KEY);

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.text(),
      cache: "no-store",
      signal: request.signal,
    });
  } catch {
    return Response.json({ code: "UPSTREAM_UNAVAILABLE", message: "exchange API unreachable" }, { status: 502 });
  }

  const responseHeaders = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  responseHeaders.set("x-accel-buffering", "no");
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export { proxy as GET, proxy as POST, proxy as DELETE };
