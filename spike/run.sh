#!/usr/bin/env bash
# Spike runner (design §10.1). Baseline JDK 25; also compiles clean on JDK 21.
#   spike/run.sh           -> compile + run the cooperative-runtime spike (checks 1-5)
#   spike/run.sh stress [N] -> run N times (default 30), assert determinism (no flaky/deadlock)
#   spike/run.sh sts        -> step 0: probe whether StructuredTaskScope is still preview
set -euo pipefail
cd "$(dirname "$0")"

# Resolve a JDK 25 (override with JAVA25_HOME). Falls back to whatever `javac` is on PATH.
JDK="${JAVA25_HOME:-/opt/homebrew/opt/openjdk@25}"
JAVAC="$JDK/bin/javac"; JAVA="$JDK/bin/java"
[ -x "$JAVAC" ] || { JAVAC=javac; JAVA=java; }
echo "using: $("$JAVAC" -version 2>&1)"

compile() {
  rm -rf out && mkdir -p out
  "$JAVAC" -d out Coop.java Fiber.java FiberState.java Interp.java Log.java Node.java Result.java Scheduler.java SpikeMain.java
}

if [ "${1:-}" = "stress" ]; then
  N="${2:-30}"
  echo "== compile + stress $N runs (determinism / deadlock check) =="
  compile
  pass=0; fail=0; orders=""; se=""
  for i in $(seq 1 "$N"); do
    if out=$("$JAVA" -cp out SpikeMain 2>&1); then pass=$((pass+1)); else fail=$((fail+1)); echo "RUN $i FAILED:"; echo "$out" | tail -3; fi
    orders="$orders $(echo "$out" | grep -o 'interleaving = [A-C]*' || true)"
    se="$se $(echo "$out" | grep -o 'side-effect executions = [0-9]' || true)"
  done
  echo "pass=$pass fail=$fail / $N"
  echo "distinct round-robin interleavings:"; echo "$orders" | tr ' ' '\n' | grep ABC | sort | uniq -c | sed 's/^/  /'
  echo "distinct side-effect counts:";        echo "$se"     | tr ' ' '\n' | grep -o '[0-9]$' | sort | uniq -c | sed 's/^/  /'
  [ "$fail" -eq 0 ] || exit 1
  exit 0
fi

if [ "${1:-}" = "sts" ]; then
  echo "== step 0: compile StsProbe WITHOUT --enable-preview (failure => STS still preview) =="
  rm -rf out_probe && mkdir -p out_probe
  if "$JAVAC" -d out_probe StsProbe.java 2>probe.err; then
    echo "RESULT: StructuredTaskScope is FINAL on this JDK (compiled with no preview flag)."
  else
    echo "RESULT: StructuredTaskScope is still PREVIEW (compile failed):"
    sed 's/^/    /' probe.err
  fi
  rm -f probe.err
  exit 0
fi

echo "== compile cooperative-runtime spike (no preview flags) =="
compile
echo "== run =="
"$JAVA" -cp out SpikeMain
