# FlightInfo 3.0-beta1 — Tester notes

<div dir="rtl">

## למי זה
גרסת בטא ראשונה לנסיינים בלבד. האפליקציה יציבה בשימוש יומיומי בבית; **בטיסה אמיתית עדיין לא נבדקה**. אל תסתמכו עליה לקבלת החלטות; היא כלי מידע לנוסע.

## התקנה
https://eldadgalker-dev.github.io/FlightInfo/ — או ישירות: https://github.com/eldadgalker-dev/FlightInfo/releases/latest/download/FlightInfo.apk
Chrome עלול לחסום ("Dangerous download") — הורדות ← הקובץ ← "Download dangerous file". Play Protect עלול להזהיר — "פרטים נוספים" ← "התקן בכל מקרה".

## מה לבדוק בטיסה
1. הפעילו **רישום יומני טיסה** (הגדרות; מופעל כברירת מחדל) לפני היציאה.
2. הזינו את הטיסה (או סרקו את כרטיס העלייה) בשער, ולחצו "התחל טיסה" לפני ההמראה. הטלפון ליד החלון.
3. שימו לב ל: זמן ההמראה שנקלט, המעבר "על הקרקע ← נסיקה ← שיוט", מרחק נותר לעומת מסך המטוס (אם יש), ומיקום המטוס לעומת מה שרואים מהחלון.
4. במושב אמצע: האם מופיע "חיזוי בלבד", ואיך התיקון החזותי (עין) עבד.
5. אחרי הנחיתה: הגדרות ← "שתף יומן אחרון" ושלחו ל־eldad@galker.com או צרפו ל־Issue.

## מה לדווח
צילום מסך, דגם הטלפון, גרסת Android, מספר הטיסה, ותיאור קצר. GitHub Issues: https://github.com/eldadgalker-dev/FlightInfo/issues

## מגבלות ידועות בבטא זו
- ללא GPS המטוס עוקב אחרי המעגל הגדול; סטייה מוכחת ומתוקנת רק כשה־GPS חוזר (~3 דקות).
- שעה מקומית במיקום הנוכחי מוצגת כ־UTC.
- מיקום ADS‑B (כשיש רשת) מגיע ממקור קהילתי (adsb.lol) ללא התחייבות לזמינות.
- גובה מ־GPS בלבד. שמות ערים ומדינות באנגלית.
- כלי הפרמטרים (מהירויות, זמן נסיעה על הקרקע) לא כוילו עדיין מטיסה אמיתית — לכן היומנים.

</div>

---

**English.** First beta for testers only; not yet validated on a real flight. Install from the page above. Before departure: enable flight logging, enter the flight (or scan the boarding pass), start it at the gate, keep the phone near the window. After landing: Settings > Share latest log, and open an Issue with a screenshot, phone model, Android version and flight number. Known limits: great-circle route without GPS, current-position time in UTC, GPS-only altitude, community ADS-B when online, uncalibrated profile parameters.
