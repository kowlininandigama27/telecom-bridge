#!/usr/bin/env bash
# Starts simulator + gateway locally and runs the constant-rate load test.
# Usage: scripts/run-load-test.sh [--tps 100] [--total 500000] [--capture]
#   --capture  also records the whole run with tcpdump (sudo) into load_test.pcap*
#              (rotated every 500 MB; 500k transactions is roughly 1-2 GB).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TPS=100
TOTAL=500000
CAPTURE=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tps) TPS="$2"; shift 2 ;;
    --total) TOTAL="$2"; shift 2 ;;
    --capture) CAPTURE=true; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
OUT_DIR="$ROOT/docs/load-test"
mkdir -p "$OUT_DIR"
case "$(uname -s)" in Darwin) IFACE=lo0 ;; *) IFACE=lo ;; esac

"$ROOT/gradlew" -p "$ROOT" -q :gateway-service:bootJar :diameter-simulator:installDist :load-tester:installDist

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  if $CAPTURE; then sudo pkill -INT -f "tcpdump -i $IFACE -s 0 -w $ROOT/load_test.pcap" 2>/dev/null || true; fi
}
trap cleanup EXIT

if $CAPTURE; then
  sudo -v
  sudo tcpdump -i "$IFACE" -s 0 -w "$ROOT/load_test.pcap" -C 500 "tcp port 8080 or tcp port 3868" >/dev/null 2>&1 &
  sleep 2
fi

"$ROOT/diameter-simulator/build/install/diameter-simulator/bin/diameter-simulator" >"$OUT_DIR/simulator.log" 2>&1 &
PIDS+=($!)
sleep 2
java -Xms256m -Xmx512m -XX:+UseG1GC -jar "$ROOT/gateway-service/build/libs/gateway-service.jar" >"$OUT_DIR/gateway.log" 2>&1 &
PIDS+=($!)
for _ in $(seq 1 60); do
  curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1 && break
  sleep 0.5
done

# caffeinate keeps a Mac awake for long runs; harmless no-op elsewhere.
KEEP_AWAKE=""; command -v caffeinate >/dev/null && KEEP_AWAKE="caffeinate -i"
$KEEP_AWAKE "$ROOT/load-tester/build/install/load-tester/bin/load-tester" \
  --tps "$TPS" --total "$TOTAL" --report "$OUT_DIR/load-test-report.md" | tee "$OUT_DIR/load-test-console.log"
