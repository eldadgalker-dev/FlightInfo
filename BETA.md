# FlightInfo 4.5-beta8 — Tester notes

<div dir="rtl">

## למי זה
גרסת בטא לנסיינים בלבד. טיסה אמיתית אחת (MUC→TLV, 10.9.2026) נותחה ושימשה לכיול; המנוע הנוכחי (4.x) הורץ על יומן הטיסה בהצלחה, אך **עדיין לא נבדק בטיסה בזמן אמת**. אל תסתמכו עליה לקבלת החלטות; היא כלי מידע לנוסע.

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
- עם GPS המטוס במיקום הנמדד; ללא GPS הוא מתקדם על הקו הישר ליעד מהנקודה האחרונה שנמדדה, והאזהרה מבקשת להצמיד לחלון.
- שעה מקומית במיקום הנוכחי מוצגת כ־UTC.
- מיקום ADS‑B (כשיש רשת) מגיע ממקור קהילתי (adsb.lol) ללא התחייבות לזמינות.
- גובה מ־GPS בלבד. שמות ערים ומדינות באנגלית.
- הפרמטרים כוילו מטיסה אחת בלבד; כל יומן נוסף משפר אותם.

</div>

---

**English.** Beta for testers only; validated on one real flight (MUC-TLV). Install from the page above. Before departure: enable flight logging, enter the flight (or scan the boarding pass), start it at the gate, keep the phone near the window. After landing: Settings > Share latest log, and open an Issue with a screenshot, phone model, Android version and flight number. Known limits: great-circle route without GPS, current-position time in UTC, GPS-only altitude, community ADS-B when online, uncalibrated profile parameters.
