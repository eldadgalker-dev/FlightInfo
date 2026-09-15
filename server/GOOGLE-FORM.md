# FlightInfo — Telemetry without a server: Google Form (5 minutes)

**English** · (עברית למטה)

Simplest channel. Every event (page view, download click, tester register / install / update) becomes one row in a Google Sheet you own. No server, no account other than Google, nothing to maintain. The public IP is fetched by the client (api.ipify.org) and sent as a field, so IP and browser are recorded too.

## Create the form (once)
1. https://forms.google.com → **Blank form**. Title: `FlightInfo telemetry`.
2. Add **9 short-answer questions**, in this order, with exactly these titles:
   `event`, `testerId`, `nickname`, `email`, `version`, `device`, `android`, `ip`, `ua`
   (add also `page`, `lang`, `screen`, `tz`, `platform`, `referer` if you want the page details — optional).
3. Settings → **Responses**: turn off "Collect email addresses", turn off "Limit to 1 response". Responses tab → **Link to Sheets** → create a spreadsheet (this is your log; column A = timestamp).
4. Get the field ids: **Send → link icon** → copy the link → open it in a new tab → **Ctrl+U** (page source) → Ctrl+F `entry.` — each question has an id like `entry.1234567890`, in question order. (Alternatively: **⋮ → Get pre-filled link**, fill anything, copy the link: the ids are in the URL.)
5. The form's submit URL is the link from step 4 with `/viewform` replaced by `/formResponse`.

## Wire it in (once)
- `app/src/main/java/org/skytrack/Parameters.kt`: `TELEMETRY_URL = "https://docs.google.com/forms/d/e/<ID>/formResponse"` and fill `TELEMETRY_FORM_FIELDS` with the `entry.N` ids for `event, testerId, nickname, email, version, device, android, ip`.
- `docs/index.html` and `docs/index.he.html`: on `<body>` set `data-telemetry="<same URL>"` and `data-form-fields='{"event":"entry.N","ip":"entry.N","ua":"entry.N","page":"entry.N",...}'` (only the fields you created).
- Publish. Test: open the installation page → the sheet gets a `page_view` row within seconds.

## What you see in the sheet
| Column | From |
|---|---|
| Timestamp | Google (server time) |
| event | page_view / download_click / register / install / update / unregister |
| ip, ua | client-fetched public IP and browser string |
| version, device, android | app events |
| testerId, nickname, email | tester programme (consent required) |
| page, lang, screen, tz, platform, referer | page events (if created) |

Unique downloaders: pivot on `ip`+`ua` per day, or on `testerId`. Google Forms accepts about 1 submission per second per form — far more than needed.

Or send me the `formResponse` URL and the `entry.N` list and I will wire them into the next build.

---

<div dir="rtl">

## בעברית — טופס Google (5 דקות, ללא שרת)
1. forms.google.com → טופס חדש בשם `FlightInfo telemetry`.
2. 9 שאלות "תשובה קצרה" בסדר הזה ובשמות האלה: `event, testerId, nickname, email, version, device, android, ip, ua` (רשות: `page, lang, screen, tz, platform, referer`).
3. הגדרות → תגובות: בטל איסוף כתובת מייל ובטל הגבלה לתגובה אחת. תגובות → קישור ל־Sheets (זה היומן; עמודה A = חותמת זמן).
4. מזהי השדות: שלח → קישור → פתח בלשונית → Ctrl+U → חפש `entry.` — מזהה לכל שאלה לפי הסדר. (או: ⋮ → קבל קישור ממולא מראש — המזהים בכתובת.)
5. כתובת השליחה: הקישור עם `/formResponse` במקום `/viewform`.
6. הכנס את הכתובת ל־`Parameters.TELEMETRY_URL` ואת המזהים ל־`TELEMETRY_FORM_FIELDS`; בשני עמודי ההתקנה: `data-telemetry` ו־`data-form-fields` על `<body>`. פרסם ובדוק — פתיחת העמוד יוצרת שורת `page_view` בגיליון.

או שלח לי את כתובת ה־`formResponse` ואת רשימת ה־`entry.N` ואני אשלב אותם בבנייה הבאה.

</div>
