// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - create_form.gs  (Google Apps Script)
// Version 1.1
// Purpose : Builds the telemetry Google Form with the exact questions the app
//           and the installation page send, links it to a new Google Sheet,
//           and prints everything needed for wiring: the formResponse URL,
//           the Kotlin TELEMETRY_FORM_FIELDS map and the data-form-fields
//           JSON for the pages.
// Usage   : script.google.com -> New project -> paste this file -> Run
//           "createFlightInfoForm" -> allow permissions -> read the log
//           (View -> Executions, or Ctrl+Enter shows the log at the bottom).
// =============================================================

// ---------------- Parameters ----------------
var FORM_TITLE  = "FlightInfo telemetry";
var SHEET_TITLE = "FlightInfo telemetry (responses)";
// Field order = column order in the sheet after the timestamp.
var FIELDS = ["event", "testerId", "nickname", "email", "version", "device", "android", "ip", "ua",
              "page", "lang", "screen", "tz", "platform", "referer"];
var APP_FIELDS = ["event", "testerId", "nickname", "email", "version", "device", "android", "ip"];
var PAGE_FIELDS = ["event", "ip", "ua", "page", "lang", "screen", "tz", "platform", "referer"];

function createFlightInfoForm() {
  var form = FormApp.create(FORM_TITLE);
  form.setDescription("Machine-filled by the FlightInfo app and installation page. Do not fill manually.");
  // Settings that exist only on Google Workspace accounts throw "not supported" on a personal account;
  // a personal account has them off already, so failures are ignored.
  [function () { form.setCollectEmail(false); },
   function () { form.setLimitOneResponsePerUser(false); },
   function () { form.setRequireLogin(false); },
   function () { form.setShowLinkToRespondAgain(false); },
   function () { form.setConfirmationMessage("ok"); }].forEach(function (f) { try { f(); } catch (e) { Logger.log("skipped: " + e.message); } });

  var ids = {};
  FIELDS.forEach(function (name) {
    var item = form.addTextItem();
    item.setTitle(name);
    ids[name] = "entry." + item.getId();
  });

  // Responses -> a new spreadsheet (this is the log).
  var ss = SpreadsheetApp.create(SHEET_TITLE);
  form.setDestination(FormApp.DestinationType.SPREADSHEET, ss.getId());

  var responseUrl = form.getPublishedUrl().replace("/viewform", "/formResponse");

  // ---- Output for wiring ----
  var kotlin = "    val TELEMETRY_FORM_FIELDS = mapOf(\n" + APP_FIELDS.map(function (f) {
    return '        "' + f + '" to "' + ids[f] + '"';
  }).join(",\n") + "\n    )";
  var pageJson = {};
  PAGE_FIELDS.forEach(function (f) { pageJson[f] = ids[f]; });

  Logger.log("\n==== FlightInfo telemetry form created ====");
  Logger.log("Form (edit):        " + form.getEditUrl());
  Logger.log("Sheet (the log):    " + ss.getUrl());
  Logger.log("TELEMETRY_URL:      " + responseUrl);
  Logger.log("\nParameters.kt:\n    const val TELEMETRY_URL = \"" + responseUrl + "\"\n" + kotlin);
  Logger.log("\ndocs/index.html and docs/index.he.html, on <body>:\n  data-telemetry=\"" + responseUrl + "\"\n  data-form-fields='" + JSON.stringify(pageJson) + "'");
  Logger.log("\nAll entry ids: " + JSON.stringify(ids));
  return responseUrl;
}
