#!/usr/bin/env bash
# Produces transaction_flow.pcap: CER/CEA on startup, then N REST calls each mapped to CCR/CCA,
# an idle DWR/DWA exchange, and DPR/DPA on gateway shutdown.
#
# Only tcpdump runs with sudo (BPF devices are root-only on macOS).
# Usage: scripts/capture-pcap.sh [number-of-transactions, default 10]
# Env:   OUT=path.pcap  HTTP_PORT=8080  DIAMETER_PORT=3868
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COUNT="${1:-10}"
OUT="${OUT:-$ROOT/transaction_flow.pcap}"
HTTP_PORT="${HTTP_PORT:-8080}"
DIAMETER_PORT="${DIAMETER_PORT:-3868}"
LOGS="$(mktemp -d)"
case "$(uname -s)" in Darwin) IFACE=lo0 ;; *) IFACE=lo ;; esac

for port in "$HTTP_PORT" "$DIAMETER_PORT"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "Port $port is already in use; stop whatever is listening there first." >&2
    exit 1
  fi
done

echo "==> Building"
"$ROOT/gradlew" -p "$ROOT" -q :gateway-service:bootJar :diameter-simulator:installDist

echo "==> sudo is needed for tcpdump only"
sudo -v

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  sudo pkill -INT -f "tcpdump -i $IFACE -s 0 -U -w $OUT" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> Capturing on $IFACE (tcp port $HTTP_PORT or $DIAMETER_PORT) -> $OUT"
sudo tcpdump -i "$IFACE" -s 0 -U -w "$OUT" "tcp port $HTTP_PORT or tcp port $DIAMETER_PORT" >"$LOGS/tcpdump.log" 2>&1 &
sleep 2

echo "==> Starting Diameter simulator on :$DIAMETER_PORT (logs: $LOGS)"
SIM_PORT="$DIAMETER_PORT" SIM_WATCHDOG_SECONDS=30 \
  "$ROOT/diameter-simulator/build/install/diameter-simulator/bin/diameter-simulator" >"$LOGS/simulator.log" 2>&1 &
PIDS+=($!)
sleep 2

echo "==> Starting gateway on :$HTTP_PORT (CER/CEA happens now)"
SERVER_PORT="$HTTP_PORT" DIAMETER_PEER_HOST=127.0.0.1 DIAMETER_PEER_PORT="$DIAMETER_PORT" \
  DIAMETER_WATCHDOG_INTERVAL=5s GATEWAY_LOG_LEVEL=DEBUG \
  java -jar "$ROOT/gateway-service/build/libs/gateway-service.jar" >"$LOGS/gateway.log" 2>&1 &
GATEWAY_PID=$!
PIDS+=("$GATEWAY_PID")
for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:$HTTP_PORT/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 0.5
done

echo "==> Sending $COUNT charge requests"
for i in $(seq 1 "$COUNT"); do
  msisdn=$(printf "9198765%05d" "$i")
  curl -sS -o /dev/null -w "  POST /api/v1/charge msisdn=$msisdn -> HTTP %{http_code} in %{time_total}s\n" \
    -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/charge" -H 'Content-Type: application/json' \
    -d "{\"msisdn\":\"$msisdn\",\"requestType\":\"INITIAL\",\"serviceIdentifier\":1,\"requestedUnits\":{\"time\":60,\"totalOctets\":1048576}}"
  sleep 0.2
done

echo "==> Idling 7 s so the watchdog (Tw=5s) produces a DWR/DWA exchange"
sleep 7

echo "==> Stopping gateway gracefully (DPR/DPA)"
kill -TERM "$GATEWAY_PID"
wait "$GATEWAY_PID" 2>/dev/null || true
sleep 1
cleanup
trap - EXIT
sleep 1
sudo chown "$(id -u):$(id -g)" "$OUT" 2>/dev/null || true

echo "==> Wrote $OUT ($(wc -c <"$OUT") bytes)"
if command -v tshark >/dev/null; then
  tshark -r "$OUT" -Y 'diameter || http' -T fields -e frame.number -e _ws.col.Protocol -e _ws.col.Info | head -60
else
  echo "Open it in Wireshark with display filter: diameter || http"
fi
echo "Gateway/simulator logs kept in $LOGS"
