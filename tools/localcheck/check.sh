#!/bin/sh
# FlightInfo - local compile check of the service layer against Android/coroutines stubs (no Android SDK needed).
# Usage: tools/localcheck/check.sh <kotlinc-bin-dir>   -> prints error count (0 = clean)
KC="$1"; ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; S="$ROOT/app/src/main/java/org/skytrack"
OUT="$(mktemp -d)"
"$KC/kotlinc" -nowarn -d "$OUT" $(find "$ROOT/tools/localcheck/stubs" -name "*.kt") \
  "$S/Parameters.kt" "$S"/route/*.kt "$S"/fusion/*.kt "$S/scan/Bcbp.kt" "$S/data/Airlines.kt" "$S/data/Stores.kt" "$S/data/AirportRepository.kt" \
  "$S/data/GeoData.kt" "$S/data/ResourceMonitor.kt" "$S"/sensors/*.kt "$S/net/Downloader.kt" "$S/net/LiveFlightSource.kt" "$S/net/Feedback.kt" \
  "$S/net/Telemetry.kt" "$S/map/AerialPack.kt" "$S/service/FlightLogger.kt" "$S/service/FlightLogReader.kt" "$S/service/FlightLogRepair.kt" \
  "$S/service/ReplayEngine.kt" "$S/service/FlightEngine.kt" 2>&1 | grep -v "^warning" | grep -A2 "error:"
echo "--- check done ---"
