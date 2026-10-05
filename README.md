# Telecom-Bridge: a REST-to-Diameter gateway

Telecom-Bridge is a non-blocking microservice that turns `POST /api/v1/charge` JSON calls into Diameter
**Credit-Control-Requests** (Ro/Gy, RFC 4006). It sends them over one persistent TCP connection to an
Online Charging System and returns the **Credit-Control-Answer** to the REST caller as JSON.

The repository contains the following parts. All of the Diameter code (codec, client and simulator) is
written from scratch on Netty. It does not use jDiameter.

| Module | What it is |
|---|---|
| `diameter-core` | Diameter codec: message header, AVPs with 32-bit padding, grouped AVPs, the CER/DWR/DPR/CCR/CCA builders, and the Netty frame decoder and encoder. Unit-tested down to the byte. |
| `gateway-service` | Spring Boot 3.5 WebFlux service with an async Diameter client. The client manages the persistent connection, CER/CEA, DWR/DWA watchdog, reconnect back-off and Hop-by-Hop correlation. |
| `diameter-simulator` | Standalone Netty Diameter server on port 3868. It answers CER, DWR, DPR and CCR, and replies to each CCR after a 50-100 ms simulated processing delay. |
| `load-tester` | Constant-rate load generator (open model, corrected for coordinated omission) that writes a Markdown and CSV report. |
| `deploy/aws` | CloudFormation for ECS Fargate (Graviton), an ALB and Cloud Map, plus a one-command deploy script. |
| `Dockerfile`, `docker-compose.yml` | Multi-stage image build with `gateway`, `simulator` and `load-tester` targets. |

---

## 1. Architecture

```
                 HTTP/1.1 JSON                              Diameter over TCP (one persistent connection)
┌────────┐   POST /api/v1/charge   ┌──────────────────────────────────────────┐   CCR (272, app 4, R|P)   ┌───────────────┐
│ client │ ──────────────────────▶ │ gateway-service                          │ ────────────────────────▶ │  OCS / sim    │
│        │ ◀────────────────────── │  Reactor Netty ─▶ ChargeController       │ ◀──────────────────────── │  :3868        │
└────────┘   200/402/404/503/504   │      Mono.fromFuture(...)                │   CCA (Result-Code)       └───────────────┘
                                   │  ChargingService (JSON ⇄ CCR/CCA)        │
                                   │  DiameterPeerClient (Netty, kqueue/epoll)│   CER/CEA on connect
                                   │   ├─ PendingRequests                     │   DWR/DWA when idle (Tw)
                                   │   │   ConcurrentHashMap<HbH, Future>     │   DPR/DPA on shutdown
                                   │   │   + HashedWheelTimer timeouts        │
                                   │   └─ state CLOSED→CONNECTING→WAIT_CEA→OPEN
                                   └──────────────────────────────────────────┘
```

### One request, step by step

1. A Reactor Netty I/O thread decodes the JSON. `ChargeController` returns a `Mono` right away, so no
   thread waits for the Diameter answer.
2. `ChargingService` builds a `CreditControlRequest`. It sets the Session-Id, `CC-Request-Type`,
   `Subscription-Id{END_USER_E164, msisdn}` and `Requested-Service-Unit`.
3. `DiameterPeerClient.send()` does four things:
   - draws the next Hop-by-Hop Identifier;
   - registers a `CompletableFuture` under that ID in a `ConcurrentHashMap`;
   - schedules a timeout on a `HashedWheelTimer`;
   - calls `channel.writeAndFlush(ccr)`.

   Then it returns.
4. The Diameter event loop decodes the CCA, takes the future out of the map with `remove(hopByHop)`
   (atomic, first one wins) and completes it.
5. The `Mono` emits. The CCA is mapped to JSON and to an HTTP status code.

Each map entry leaves exactly once: when the answer arrives, the timeout fires, the HTTP caller
cancels, or the connection drops. No future can be completed twice and no map entry can leak.

---

## 2. Running it

**Requirements:** JDK 25 (Gradle toolchain; the wrapper is included) and `curl`. Docker is needed only
for containers, and the AWS CLI only for AWS.

```bash
# build everything and run all 39 tests
./gradlew build

# terminal 1: Diameter simulator on :3868
./gradlew :diameter-simulator:installDist
diameter-simulator/build/install/diameter-simulator/bin/diameter-simulator

# terminal 2: gateway on :8080 (connects to localhost:3868 and performs CER/CEA)
./gradlew :gateway-service:bootJar
java -jar gateway-service/build/libs/gateway-service.jar

# terminal 3
curl -s -X POST localhost:8080/api/v1/charge -H 'Content-Type: application/json' \
  -d '{"msisdn":"919876543210","requestType":"INITIAL","requestedUnits":{"time":60,"totalOctets":1048576}}' | jq
```

```json
{
  "sessionId": "gw.telecom-bridge.local;1791211550;0",
  "resultCode": 2001,
  "resultCodeName": "DIAMETER_SUCCESS",
  "success": true,
  "requestType": "INITIAL",
  "requestNumber": 0,
  "grantedUnits": { "time": 60, "totalOctets": 1048576 },
  "validityTime": 3600,
  "originHost": "ocs.telecom-bridge.local",
  "originRealm": "telecom-bridge.local",
  "hopByHopId": "0xd9e54756",
  "endToEndId": "0x81e36983",
  "diameterLatencyMs": 60.46
}
```

### Docker

```bash
docker compose up --build                     # simulator + gateway
docker compose --profile load run --rm load-tester \
  --url http://gateway:8080/api/v1/charge --tps 100 --total 30000
```

### AWS (ECS Fargate)

```bash
aws configure            # or aws sso login
deploy/aws/deploy.sh     # creates the ECR repos, pushes arm64 images, deploys the stack, runs a smoke test
deploy/aws/destroy.sh --purge-images
```

`deploy/aws/telecom-bridge.yaml` creates:

- a VPC with two public subnets (no NAT gateway, to keep costs down);
- security groups that chain ALB → gateway:8080 → simulator:3868, so nothing else can reach the
  Diameter port;
- an ECS cluster with Container Insights;
- **two gateway tasks** behind an internet-facing ALB. Each task derives a unique Diameter Origin-Host
  from its hostname;
- **one simulator task**, registered in Cloud Map as `ocs.telecom-bridge.internal`;
- CloudWatch Logs with 14-day retention;
- a deployment circuit breaker with automatic rollback.

The ALB accepts traffic only from `ALLOWED_CIDR`, which defaults to your current public IP as a /32.
The target-group health check uses **liveness, not readiness**. If the OCS goes down, the gateways
keep running and answer 503, instead of being recycled by ECS in a loop.

To load-test the cloud deployment:

```bash
load-tester/build/install/load-tester/bin/load-tester \
  --url http://<alb-dns>/api/v1/charge --metrics-url http://<alb-dns>/actuator --tps 100 --total 30000
```

---

## 3. REST API

`POST /api/v1/charge` takes and returns `application/json`. The challenge PDF left the input payload
blank, so this contract is defined here. Only `msisdn` is required.

| Field | Type | Default | Diameter mapping |
|---|---|---|---|
| `msisdn` | string, 6-15 digits | required | `Subscription-Id { Subscription-Id-Type = END_USER_E164 (0), Subscription-Id-Data }` |
| `requestType` | `INITIAL` \| `UPDATE` \| `TERMINATION` \| `EVENT` | `INITIAL` | `CC-Request-Type` (1-4) |
| `sessionId` | string | generated for INITIAL/EVENT | `Session-Id` (required for UPDATE/TERMINATION) |
| `requestNumber` | uint32 | 0 | `CC-Request-Number` |
| `serviceContextId` | string | `32251@3gpp.org` | `Service-Context-Id` |
| `serviceIdentifier` | uint32 | none | `Service-Identifier` |
| `requestedUnits.time` / `.totalOctets` / `.serviceSpecificUnits` | integers | none | `Requested-Service-Unit { CC-Time, CC-Total-Octets, CC-Service-Specific-Units }` |

### HTTP status mapping

| Situation | HTTP | Body |
|---|---|---|
| CCA with Result-Code 2xxx | **200** | CCA as JSON |
| CCA 4012 `DIAMETER_CREDIT_LIMIT_REACHED` | 402 | CCA as JSON |
| CCA 4010 `END_USER_SERVICE_DENIED` | 403 | CCA as JSON |
| CCA 5030 `DIAMETER_USER_UNKNOWN` | 404 | CCA as JSON |
| CCA 5004/5005 (invalid or missing AVP) | 422 | CCA as JSON |
| CCA 3004 `DIAMETER_TOO_BUSY` | 503 | CCA as JSON |
| Any other CCA failure | 502 | CCA as JSON |
| **Diameter server down**, or connection not OPEN yet | **503** + `Retry-After: 1` | RFC 9457 problem |
| Gateway over its in-flight limit, or socket buffer full | 503 + `Retry-After: 1` | problem |
| **No CCA within `diameter.request-timeout` (1 s)** | **504** | problem |
| Invalid JSON or validation failure | 400 | problem |

When the server is down, the request fails immediately with 503 and never waits for a timeout. A
connection drop also fails every in-flight request with 503 at once. 504 is reserved for a peer that
is connected but does not answer in time.

The simulator decides results from the MSISDN, so each outcome is easy to demo. An MSISDN ending in
`0000` gets 4012 (HTTP 402). One ending in `9999` gets 5030 (HTTP 404). Anything else gets 2001.

### Operations endpoints

`/actuator/health` includes a `diameterPeer` component with the peer state, the peer's Origin-Host and
the pending-request count. The readiness group includes the Diameter peer; liveness does not.

`/actuator/prometheus` exposes:

| Metric | Meaning |
|---|---|
| `diameter_client_ccr_seconds` | CCR→CCA round-trip time (p50, p95, p99) |
| `diameter_client_pending` | requests waiting for an answer |
| `diameter_client_peer_open` | 1 while the peer connection is OPEN |
| `diameter_client_reconnects_total` | reconnect attempts |
| `http_server_requests_*` | standard HTTP server metrics |

---

## 4. Diameter implementation

### Header (RFC 6733 §3)

The header is 20 bytes: Version=1, a 24-bit Message Length (which counts the header), flags `R P E T`,
a 24-bit Command-Code, Application-Id, Hop-by-Hop and End-to-End identifiers.

| Message | Command-Code | Application-Id | Flags |
|---|---|---|---|
| CER / CEA | 257 | 0 | `R` / – |
| DWR / DWA | 280 | 0 | `R` / – |
| DPR / DPA | 282 | 0 | `R` / – |
| **CCR / CCA** | **272** | **4** | **`R P` / `P`** (answers copy the P bit; 3xxx protocol errors set `E`) |

- **Hop-by-Hop Identifier:** starts at a random value and increments. It is the correlation key.
- **End-to-End Identifier:** the high 12 bits come from the clock and the low 20 bits from a random
  counter, as RFC 6733 recommends.
- **Session-Id:** `<Origin-Host>;<boot-seconds>;<counter>`, the RFC 6733 §8.8 format.

### AVPs (RFC 6733 §4)

Each AVP has a code, flags `V M P`, a 24-bit length and an optional Vendor-Id. The **length excludes
padding**, and every AVP is **zero-padded to a 4-byte boundary** on the wire. Grouped AVPs carry their
children already padded. The unit tests in `AvpTest` check the exact bytes, for example `"abcde"`
encodes as length 13 followed by 3 padding bytes. Supported data types are UTF8String,
DiameterIdentity, Unsigned32/64, Integer32/Enumerated, Address (2-byte family + IP),
Time (NTP epoch 1900) and Grouped.

### Messages

```
CER  = Origin-Host, Origin-Realm, Host-IP-Address, Vendor-Id=0, Product-Name (no M bit), Origin-State-Id,
       Auth-Application-Id=4, Firmware-Revision (no M bit)
CEA  = Result-Code=2001, Origin-Host, Origin-Realm, Host-IP-Address, Vendor-Id, Product-Name, Origin-State-Id, Auth-Application-Id=4
CCR  = <Session-Id> first, Origin-Host, Origin-Realm, Destination-Realm, Auth-Application-Id=4, Service-Context-Id,
       CC-Request-Type, CC-Request-Number, [Destination-Host], Origin-State-Id, Event-Timestamp,
       Subscription-Id{Subscription-Id-Type=0 (E.164/MSISDN), Subscription-Id-Data}, [Service-Identifier],
       [Requested-Service-Unit{CC-Time, CC-Total-Octets}]
CCA  = Session-Id, Result-Code, Origin-Host, Origin-Realm, Auth-Application-Id=4, CC-Request-Type, CC-Request-Number,
       [Granted-Service-Unit], [Validity-Time]
```

### Connection management

- **Startup:** TCP connect, then CER. Application traffic is allowed only after CEA with 2001 (state
  `OPEN`). Any other CEA result closes the connection and triggers a back-off.
- **Watchdog (RFC 3539):** an `IdleStateHandler` fires after Tw (30 s by default) with no traffic in
  either direction, and the client sends a DWR. If no DWA arrives within another Tw, the connection
  is declared failed and re-established. DWRs from the peer are answered with DWA.
- **Reconnect:** exponential back-off from 500 ms to 30 s, with jitter.
- **Shutdown:** Spring stops the HTTP server first (graceful), then the client sends **DPR**, waits up
  to 1 s for DPA, closes the socket, stops the timer and shuts down the event loop.
- **Back-pressure:** the client caps in-flight requests (`max-pending-requests`, 10,000) and uses Netty
  write-buffer watermarks. Beyond either limit it sheds load with 503 instead of queueing without
  bound.
- **Transport:** native epoll on Linux and kqueue on macOS, with NIO as the fallback.
  `FlushConsolidationHandler` batches syscalls under load. TCP_NODELAY and keepalive are on.

---

## 5. How the evaluation criteria are met

| Criterion | Where |
|---|---|
| Concurrency management | `PendingRequests` holds a `ConcurrentHashMap<Integer, Pending>` keyed by Hop-by-Hop ID. Atomic `remove` resolves the answer / timeout / cancel / disconnect race. Timeouts run on a `HashedWheelTimer` (O(1), one thread). WebFlux plus `Mono.fromFuture` means no thread is ever parked waiting. The test `concurrentRequestsAreCorrelatedByHopByHopId` sends 200 requests in parallel and checks that each gets its own answer. |
| Protocol accuracy | `DiameterMessage`, `Avp`, `BaseMessages`, `CreditControlRequest`/`Answer`. Byte-level tests cover header flags, command codes, application IDs, padding, grouped AVPs, Address, Time, Unsigned64, vendor-specific headers, and segmented or coalesced TCP frames. |
| Resource management | Every inbound `ByteBuf` is released inside the frame decoder, and tests run with Netty's **paranoid** leak detector. Event loops are sized explicitly (`diameter.io-threads`). Sockets are closed on DPR, on watchdog failure and on shutdown. Caller cancellation frees the map slot. Memory stayed flat across the 500k-transaction run (section 6). |
| Error handling | Peer down → 503 + Retry-After. Connection lost mid-flight → 503 for all pending requests. Slow peer → 504. Overload → 503. Bad input → 400. Covered by `PeerFailureTest` (down, reconnect, down again) and `AnswerTimeoutTest`. |
| Lead-developer maturity | SLF4J logs for connection lifecycle and errors, with per-transaction logs at DEBUG. Health indicator, Prometheus metrics, validated configuration properties, Dockerfile, docker-compose, CloudFormation, GitHub Actions CI. 39 tests. |

---

## 6. Load test (100 TPS × 500,000 transactions)

```bash
scripts/run-load-test.sh                 # 500,000 transactions at 100 TPS (~83 min)
scripts/run-load-test.sh --capture       # same, plus tcpdump of the whole run into load_test.pcap* (sudo)
```

The load tester runs an **open model**: request *i* is sent at `t0 + i × 10 ms`, whatever happened to
earlier requests. Latency is measured from that **scheduled** time, so any stall shows up in the
percentiles and can't quietly lower the offered load. The tester polls the gateway's actuator every
10 s for heap and G1 Old Gen usage. It writes `docs/load-test/load-test-report.md`, a CSV with the same
timeline, and the console log.

<!-- RESULTS -->

### About the latency budget

The challenge asks for a CCA delay of **50-100 ms** and **p95 < 100 ms** end to end. With a uniform
delay, the simulator alone already has p95 = 97.5 ms. That leaves **about 2.5 ms** for HTTP, JSON, the
gateway, two TCP hops and the load generator together. Two environment effects had to be removed for
the measurement to reflect the gateway rather than the laptop:

1. **OS timer slack.** On macOS, a niced background process wakes about 1.3 ms late from every timed
   sleep (measured: `parkNanos(5 ms)` overshoots 1.27 ms at p50 and 1.33 ms at p99). The simulator's
   "75 ms" therefore became 76.3 ms. The simulator now releases CCAs from a dedicated timer thread
   (`PreciseDelayScheduler`) that parks until 2 ms before the deadline and spin-waits the rest. The
   deadline is measured from CCR receipt. The delay distribution is still exactly uniform 50-100 ms.
   Set `SIM_PRECISE_TIMER=false` to go back to plain event-loop timers. On Linux the default timer
   slack is 50 µs, so the difference is negligible there.
2. **Load-generator overhead.** The JDK `HttpClient` added about 2 ms per request. The tester now uses
   Reactor Netty with native transport and spin-waits the last 2 ms before each scheduled send.

After both changes, the gateway's own overhead measures about **0.5 ms** per transaction:

| Measured at | Mean |
|---|---|
| Simulator delay (by construction) | 75 ms |
| Diameter round trip at the client (`diameter_client_ccr`) | 75.3 ms |
| HTTP server (`http_server_requests`) | 75.7 ms |

---

## 7. PCAP trace

```bash
scripts/capture-pcap.sh        # needs sudo for tcpdump only; writes ./transaction_flow.pcap
```

The script captures loopback traffic on `tcp port 8080 or tcp port 3868`. It starts the simulator and
the gateway, sends 10 REST charge calls, idles long enough for a watchdog exchange, then stops the
gateway gracefully. In Wireshark, use the display filter `diameter || http`. The trace shows:

1. TCP handshake, then **CER** (Vendor-Id 0, Auth-Application-Id 4) and **CEA** (Result-Code 2001).
2. Ten times: **HTTP POST /api/v1/charge**, then **CCR** (INITIAL_REQUEST, Subscription-Id E.164 =
   MSISDN), then **CCA** (2001) 50-100 ms later, then the **HTTP 200** response.
3. **DWR / DWA** after 5 s idle (Tw is lowered to 5 s for the capture).
4. **DPR / DPA** on gateway shutdown, then the TCP FIN.

CER/CEA comes before the first REST call because the requirements ask for a persistent connection set
up at startup. Every REST call is then carried on that same connection.

---

## 8. Configuration

Gateway settings live in `application.yml` under the `diameter.*` prefix, and the common ones can be
overridden with environment variables.

| Property | Env | Default |
|---|---|---|
| `diameter.peer-host` / `peer-port` | `DIAMETER_PEER_HOST` / `DIAMETER_PEER_PORT` | `localhost` / `3868` |
| `diameter.origin-host` / `origin-realm` | `DIAMETER_ORIGIN_HOST` / `DIAMETER_ORIGIN_REALM` | `gw.telecom-bridge.local` / `telecom-bridge.local` |
| `diameter.destination-realm` / `destination-host` | `DIAMETER_DESTINATION_REALM` / `DIAMETER_DESTINATION_HOST` | `telecom-bridge.local` / none |
| `diameter.request-timeout` | `DIAMETER_REQUEST_TIMEOUT` | `1s` (then 504) |
| `diameter.watchdog-interval` | `DIAMETER_WATCHDOG_INTERVAL` | `30s` (Tw) |
| `diameter.reconnect-min-delay` / `reconnect-max-delay` | none | `500ms` / `30s` |
| `diameter.max-pending-requests` | none | `10000` |
| `diameter.io-threads` | none | `1` |
| `server.port` | `SERVER_PORT` | `8080` |

The simulator reads environment variables, or the matching `-Dsim.*` system properties:

| Variable | Default |
|---|---|
| `SIM_PORT` | `3868` |
| `SIM_ORIGIN_HOST` | `ocs.telecom-bridge.local` |
| `SIM_ORIGIN_REALM` | `telecom-bridge.local` |
| `SIM_MIN_DELAY_MS` / `SIM_MAX_DELAY_MS` | `50` / `100` |
| `SIM_WATCHDOG_SECONDS` | `30` (the simulator also sends DWR when idle) |
| `SIM_IO_THREADS` | `0` (Netty default) |
| `SIM_PRECISE_TIMER` | `true` |

---

## 9. Known limitations and next steps

- **Single peer, single connection.** 100 TPS uses well under 1% of one connection's capacity. For HA,
  add a peer table with primary/secondary failover (RFC 6733 §5.1, RFC 3539), connection pooling by
  Origin-Host, and DWR-driven suspect/down states.
- **TCP only.** SCTP (`com.sun.nio.sctp`) and TLS/DTLS (`Inband-Security-Id`) are not implemented.
- **No retransmission.** After a failover, the client would need to resend pending requests with the
  T flag set. Today they fail fast with 503.
- **Toy OCS.** The simulator keeps no balances or session state, and uses MSISDN suffixes to simulate
  rejections.
