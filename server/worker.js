// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - telemetry endpoint (Cloudflare Worker, free tier)
// Version 1.0
// Purpose : Receive and store events from the installation page (page view,
//           download click) and from the app (register, install, update -
//           only after the user joined the tester programme). Stores one
//           JSON line per event in Workers KV, with the request IP, country,
//           user agent and a per-day unique-visitor hash. Exports CSV.
// Deploy  : see server/SERVER.md. Bindings: KV namespace "LOGS";
//           secret "EXPORT_TOKEN" (any long random string).
// Endpoints:
//   POST /event   JSON body {event, ...}  -> 204
//   GET  /export?token=...&from=YYYY-MM-DD&to=YYYY-MM-DD -> text/csv
//   GET  /stats?token=...  -> JSON summary (events per day, unique visitors, downloads)
// =============================================================

// ---------------- Parameters ----------------
const STORE_FULL_IP = true;        // false: last IPv4 octet / IPv6 tail zeroed (recommended where GDPR applies)
const MAX_BODY_BYTES = 4096;
const ALLOWED_EVENTS = new Set(["page_view", "download_click", "register", "install", "update", "unregister"]);
const ALLOWED_ORIGINS = ["https://eldadgalker-dev.github.io", "https://www.galker.com"];
const RETENTION_DAYS = 400;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }), request);
    if (url.pathname === "/event" && request.method === "POST") return cors(await onEvent(request, env), request);
    if (url.pathname === "/export" && request.method === "GET") return await onExport(url, env);
    if (url.pathname === "/stats" && request.method === "GET") return await onStats(url, env);
    return new Response("FlightInfo telemetry", { status: 200 });
  },
};

function cors(resp, request) {
  const origin = request.headers.get("Origin") || "";
  const h = new Headers(resp.headers);
  if (ALLOWED_ORIGINS.includes(origin) || origin === "") h.set("Access-Control-Allow-Origin", origin || "*");
  h.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  h.set("Access-Control-Allow-Headers", "Content-Type");
  return new Response(resp.body, { status: resp.status, headers: h });
}

function anonymize(ip) {
  if (STORE_FULL_IP || !ip) return ip || "";
  if (ip.includes(".")) { const p = ip.split("."); p[3] = "0"; return p.join("."); }
  const p = ip.split(":"); return p.slice(0, 4).join(":") + "::";
}

async function sha256(s) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

async function onEvent(request, env) {
  let body;
  try {
    const text = await request.text();
    if (text.length > MAX_BODY_BYTES) return new Response("too large", { status: 413 });
    body = JSON.parse(text);
  } catch (e) { return new Response("bad json", { status: 400 }); }
  const event = String(body.event || "");
  if (!ALLOWED_EVENTS.has(event)) return new Response("bad event", { status: 400 });

  const now = new Date();
  const day = now.toISOString().slice(0, 10);
  const ip = anonymize(request.headers.get("CF-Connecting-IP") || "");
  const ua = request.headers.get("User-Agent") || "";
  const cf = request.cf || {};
  // Per-day unique visitor key: hash of IP + UA + day (not reversible to the IP).
  const visitor = await sha256(`${ip}|${ua}|${day}`);

  const rec = {
    ts: now.toISOString(), event, ip, country: cf.country || "", city: cf.city || "", asn: cf.asOrganization || "",
    ua, lang: request.headers.get("Accept-Language") || "", referer: request.headers.get("Referer") || "", visitor,
    // Fields supplied by the page / app (only what the client chose to send)
    page: str(body.page), version: str(body.version), device: str(body.device), android: str(body.android),
    testerId: str(body.testerId), nickname: str(body.nickname), email: str(body.email), consent: body.consent === true,
    screen: str(body.screen), tz: str(body.tz), platform: str(body.platform),
  };
  const key = `ev:${day}:${now.getTime()}:${Math.random().toString(36).slice(2, 8)}`;
  await env.LOGS.put(key, JSON.stringify(rec), { expirationTtl: RETENTION_DAYS * 86400 });
  return new Response(null, { status: 204 });
}

function str(v) { return v === undefined || v === null ? "" : String(v).slice(0, 200); }

function auth(url, env) { return env.EXPORT_TOKEN && url.searchParams.get("token") === env.EXPORT_TOKEN; }

async function listAll(env, from, to) {
  const out = [];
  let cursor;
  do {
    const page = await env.LOGS.list({ prefix: "ev:", cursor, limit: 1000 });
    for (const k of page.keys) {
      const day = k.name.slice(3, 13);
      if ((from && day < from) || (to && day > to)) continue;
      const v = await env.LOGS.get(k.name);
      if (v) out.push(JSON.parse(v));
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  out.sort((a, b) => a.ts.localeCompare(b.ts));
  return out;
}

async function onExport(url, env) {
  if (!auth(url, env)) return new Response("forbidden", { status: 403 });
  const rows = await listAll(env, url.searchParams.get("from"), url.searchParams.get("to"));
  const cols = ["ts", "event", "ip", "country", "city", "asn", "ua", "lang", "referer", "visitor", "page", "version", "device", "android", "testerId", "nickname", "email", "consent", "screen", "tz", "platform"];
  const esc = (v) => { const s = v === undefined ? "" : String(v); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
  const csv = [cols.join(",")].concat(rows.map((r) => cols.map((c) => esc(r[c])).join(","))).join("\n") + "\n";
  return new Response(csv, { headers: { "Content-Type": "text/csv; charset=utf-8", "Content-Disposition": "attachment; filename=flightinfo-events.csv" } });
}

async function onStats(url, env) {
  if (!auth(url, env)) return new Response("forbidden", { status: 403 });
  const rows = await listAll(env, url.searchParams.get("from"), url.searchParams.get("to"));
  const days = {};
  for (const r of rows) {
    const d = r.ts.slice(0, 10); const s = (days[d] = days[d] || { views: 0, downloads: 0, installs: 0, updates: 0, registers: 0, visitors: new Set() });
    if (r.event === "page_view") s.views++; if (r.event === "download_click") s.downloads++; if (r.event === "install") s.installs++;
    if (r.event === "update") s.updates++; if (r.event === "register") s.registers++; s.visitors.add(r.visitor);
  }
  const out = Object.fromEntries(Object.entries(days).map(([d, s]) => [d, { ...s, visitors: s.visitors.size }]));
  const testers = {}; for (const r of rows) if (r.testerId) testers[r.testerId] = { nickname: r.nickname, email: r.email, last: r.ts, version: r.version, device: r.device };
  return new Response(JSON.stringify({ days: out, testers, total_events: rows.length }, null, 2), { headers: { "Content-Type": "application/json" } });
}
