# FlightInfo — Telemetry endpoint (zero-cost) — advanced option

> **Simpler alternative without any server: [GOOGLE-FORM.md](GOOGLE-FORM.md)** (a Google Form + Sheet; 5 minutes). Use the Worker below only if you want token-protected export, unique-visitor hashing and retention control.

**English** · (הוראות בעברית למטה)

The endpoint receives events from the installation page (page views, download clicks — with IP, country, browser) and from the app (register / install / update — only after the user joined the tester programme). It runs on Cloudflare Workers' free tier (100,000 requests/day, KV 1 GB) with no server to maintain.

## Deploy (10 minutes, once)
1. Create a free account at https://dash.cloudflare.com (no domain needed).
2. **Workers & Pages → Create → Worker**. Name it `flightinfo-telemetry`. Deploy the hello-world, then **Edit code**, replace everything with `server/worker.js`, **Deploy**.
3. **Storage & Databases → KV → Create namespace** `flightinfo-logs`.
4. Worker → **Settings → Bindings → Add → KV namespace**: variable name `LOGS`, namespace `flightinfo-logs`.
5. Worker → **Settings → Variables and Secrets → Add**: type *Secret*, name `EXPORT_TOKEN`, value: a long random string (e.g. 32 characters). Save. This token protects the export.
6. Copy the worker URL (`https://flightinfo-telemetry.<your-subdomain>.workers.dev`).
7. Put that URL in three places and publish:
   - `app/src/main/java/org/skytrack/Parameters.kt` → `TELEMETRY_URL`
   - `docs/index.html` and `docs/index.he.html` → `data-telemetry` attribute on `<body>`
   - `desktop/src/app.js` is not involved (the desktop app sends nothing).

## Reading the data
- `GET https://…workers.dev/stats?token=EXPORT_TOKEN` — JSON: per day views, download clicks, installs, updates, registrations, unique visitors; list of registered testers with last version and device.
- `GET https://…workers.dev/export?token=EXPORT_TOKEN&from=2026-09-01&to=2026-09-30` — CSV of every event with all fields (timestamp, event, IP, country, city, ISP, browser, language, referer, visitor hash, version, device, Android, testerId, nickname, e-mail, consent, screen, timezone).

## What is stored
| Source | Event | Fields | Consent |
|---|---|---|---|
| Installation page | `page_view`, `download_click` | time, IP, country, city, ISP, browser (UA), language, referer, screen size, timezone, platform, unique-visitor hash (IP+UA+day) | Privacy notice on the page (no cookies). Set `STORE_FULL_IP = false` in `worker.js` to store anonymised IPs where GDPR applies |
| App | `register`, `install`, `update`, `unregister` | time, IP, country, app version, device model, Android version, testerId, nickname, e-mail (optional) | **Explicit opt-in**: Settings → Tester programme → consent checkbox. Nothing is sent otherwise |

Retention: 400 days (KV expiration). Delete a tester: remove their rows from the export; the app sends `unregister` when they leave.

## Legal note
IP addresses and identifiers are personal data (GDPR; Israeli Privacy Protection Law, Amendment 13). The page shows a notice; the app collects only with consent; the export is token-protected. Do not publish the export.

---

<div dir="rtl">

## הוראות בעברית
1. חשבון חינמי ב־dash.cloudflare.com (לא נדרש דומיין).
2. Workers & Pages → Create → Worker בשם `flightinfo-telemetry` → Edit code → הדבק את `server/worker.js` → Deploy.
3. Storage & Databases → KV → Create namespace בשם `flightinfo-logs`.
4. ב־Worker: Settings → Bindings → Add → KV namespace: שם המשתנה `LOGS`, ה־namespace שנוצר.
5. Settings → Variables and Secrets → Add → Secret בשם `EXPORT_TOKEN` עם מחרוזת אקראית ארוכה (זו הסיסמה לייצוא).
6. העתק את כתובת ה־Worker והכנס אותה ל־`Parameters.TELEMETRY_URL` באפליקציה ול־`data-telemetry` בשני עמודי ההתקנה; פרסם.
7. קריאה: `/stats?token=…` (סיכום JSON) או `/export?token=…` (CSV מלא של כל האירועים עם IP, מדינה, דפדפן, גרסה, מכשיר, כינוי ומייל של נסיינים שהסכימו).

</div>
