#!/bin/sh
# FlightInfo - run the JVM core tests locally (tests needing org.json / network classes are excluded; CI runs them).
KC="$1"; ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; S="$ROOT/app/src/main/java/org/skytrack"; T="$ROOT/tools/localcheck"
OUT="$(mktemp -d)"
python3 - "$ROOT" "$OUT" <<'PY'
import re,sys
root,out=sys.argv[1],sys.argv[2]
s=open(root+'/app/src/test/java/org/skytrack/CoreTests.kt').read()
for name in ["adsbResponseParsesToFix","versionComparisonHandlesBetas"]:
    s=re.sub(r"    @Test\n    fun %s\(\) \{.*?\n    \}\n\n"%name, "", s, flags=re.S)
s=s.replace("import org.skytrack.net.LiveFlightSource\n","").replace("import org.skytrack.net.UpdateInfo\n","")
open(out+'/CoreTestsLocal.kt','w').write(s)
PY
"$KC/kotlinc" -nowarn -d "$OUT/classes" $(find "$T/stubs" -name "*.kt") "$T"/teststubs/org/junit/Junit.kt "$T/teststubs/Runner.kt" \
  "$S/Parameters.kt" "$S"/route/*.kt "$S"/fusion/*.kt "$S/scan/Bcbp.kt" "$S/data/Airlines.kt" "$S/data/AirportRepository.kt" "$S"/sensors/*.kt "$OUT/CoreTestsLocal.kt" 2>&1 | grep -v "^warning" | grep -A2 "error:"
"$KC/kotlin" -cp "$OUT/classes" RunnerKt
