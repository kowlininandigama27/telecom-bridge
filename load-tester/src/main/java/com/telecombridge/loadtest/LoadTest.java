package com.telecombridge.loadtest;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Open-model load generator: requests are started on a fixed schedule (1/TPS apart) regardless of
 * how fast responses come back, and latency is measured from the <em>scheduled</em> start time.
 * This avoids coordinated omission: if the gateway stalls, the queueing delay shows up in the
 * percentiles instead of silently lowering the offered load.
 *
 * <pre>
 * Usage: LoadTest [--url URL] [--tps 100] [--total 500000] [--report docs/load-test-report.md]
 *                 [--metrics-url http://localhost:8080/actuator] [--max-in-flight 2000]
 * </pre>
 */
public final class LoadTest {

    private static final Pattern METRIC_VALUE = Pattern.compile("\"statistic\"\\s*:\\s*\"VALUE\"\\s*,\\s*\"value\"\\s*:\\s*([0-9.Ee+-]+)");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final long REPORT_INTERVAL_SECONDS = 10;
    private static final long SPIN_NANOS = 2_000_000;

    private final URI target;
    private final String metricsBase;
    private final int tps;
    private final long total;
    private final Path report;
    private final Semaphore inFlight;
    private final int maxInFlight;

    /** JDK client for warm-up and metric polling only (not on the measured path). */
    private final HttpClient http;
    /** Reactor Netty client for the measured traffic: non-blocking, pooled keep-alive connections. */
    private final reactor.netty.http.client.HttpClient loadClient;
    private final LatencyHistogram overall = new LatencyHistogram();
    private final LatencyHistogram interval = new LatencyHistogram();
    /** Response time measured from the moment the request was actually handed to the HTTP client. */
    private final LatencyHistogram serviceTime = new LatencyHistogram();
    /** How late the generator started each request relative to its schedule. */
    private final LatencyHistogram sendLag = new LatencyHistogram();
    private final Map<Integer, LongAdder> statusCounts = new ConcurrentHashMap<>();
    private final LongAdder transportErrors = new LongAdder();
    private final LongAdder rejectedLocally = new LongAdder();
    private final AtomicLong sent = new AtomicLong();
    private final LongAdder completed = new LongAdder();
    private final List<IntervalStats> timeline = new ArrayList<>();

    private LoadTest(Map<String, String> args) {
        this.target = URI.create(args.getOrDefault("url", "http://localhost:8080/api/v1/charge"));
        this.metricsBase = args.getOrDefault("metrics-url", target.resolve("/actuator").toString());
        this.tps = Integer.parseInt(args.getOrDefault("tps", "100"));
        this.total = Long.parseLong(args.getOrDefault("total", "500000"));
        this.report = Path.of(args.getOrDefault("report", "docs/load-test-report.md"));
        this.maxInFlight = Integer.parseInt(args.getOrDefault("max-in-flight", "2000"));
        this.inFlight = new Semaphore(maxInFlight);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "http-client");
                    t.setDaemon(true);
                    return t;
                }))
                .build();
        this.loadClient = reactor.netty.http.client.HttpClient
                .create(ConnectionProvider.builder("load").maxConnections(maxInFlight).pendingAcquireMaxCount(-1).build())
                .headers(h -> h.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON))
                .responseTimeout(Duration.ofSeconds(5));
    }

    public static void main(String[] argv) throws Exception {
        new LoadTest(parse(argv)).run();
    }

    private void run() throws Exception {
        log("Target %s, %d TPS, %,d transactions (~%s), metrics from %s",
                target, tps, total, Duration.ofSeconds(total / tps), metricsBase);
        warmUp();

        Instant startedAt = Instant.now();
        long periodNanos = 1_000_000_000L / tps;
        long t0 = System.nanoTime();
        Reporter reporter = new Reporter(t0);
        ScheduledExecutorService reporting = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reporter");
            t.setDaemon(true);
            return t;
        });
        reporting.scheduleAtFixedRate(reporter, REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        for (long i = 0; i < total; i++) {
            long intended = t0 + i * periodNanos;
            pace(intended);
            fire(intended, i);
        }
        log("All %,d requests started, waiting for in-flight responses", total);
        inFlight.acquire(maxInFlight);
        reporting.shutdown();
        reporting.awaitTermination(REPORT_INTERVAL_SECONDS + 1, TimeUnit.SECONDS);
        reporter.run();

        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);
        writeReport(startedAt, elapsed);
    }

    /**
     * Sleeps until {@code intendedNanos}. OS timers (especially macOS timer coalescing) can overshoot
     * by a millisecond or more, which would be charged to the system under test, so the last
     * {@link #SPIN_NANOS} are busy-waited.
     */
    private static void pace(long intendedNanos) {
        long wait;
        while ((wait = intendedNanos - System.nanoTime()) > SPIN_NANOS) {
            LockSupport.parkNanos(wait - SPIN_NANOS);
        }
        while (System.nanoTime() < intendedNanos) {
            Thread.onSpinWait();
        }
    }

    private void fire(long intendedNanos, long sequence) {
        if (!inFlight.tryAcquire()) {
            // The system under test is hopelessly behind; count it as a failure rather than
            // letting the generator queue unboundedly.
            rejectedLocally.increment();
            completed.increment();
            return;
        }
        sent.incrementAndGet();
        long sentNanos = System.nanoTime();
        sendLag.record(sentNanos - intendedNanos);
        loadClient.post()
                .uri(target)
                .send(ByteBufFlux.fromString(Mono.just(body(sequence))))
                .responseSingle((response, content) -> content.then(Mono.just(response.status().code())))
                .subscribe(status -> done(intendedNanos, sentNanos, status), error -> done(intendedNanos, sentNanos, -1));
    }

    private void done(long intendedNanos, long sentNanos, int status) {
        long now = System.nanoTime();
        long latency = now - intendedNanos;
        serviceTime.record(now - sentNanos);
        overall.record(latency);
        interval.record(latency);
        if (status < 0) {
            transportErrors.increment();
        } else {
            statusCounts.computeIfAbsent(status, k -> new LongAdder()).increment();
        }
        completed.increment();
        inFlight.release();
    }

    /** Random subscriber from a pool of 10^7 MSISDNs, avoiding the simulator's 0000/9999 rejection suffixes. */
    private static String body(long sequence) {
        long subscriber = ThreadLocalRandom.current().nextLong(10_000_000L);
        long suffix = subscriber % 10_000;
        if (suffix == 0) {
            subscriber++;
        } else if (suffix == 9_999) {
            subscriber--;
        }
        return "{\"msisdn\":\"9198" + String.format("%08d", subscriber % 100_000_000L)
                + "\",\"requestType\":\"INITIAL\",\"serviceIdentifier\":1,"
                + "\"requestedUnits\":{\"time\":60,\"totalOctets\":1048576}}";
    }

    private void warmUp() {
        log("Warm-up: 500 requests, 50 concurrent (excluded from results)");
        Integer bad = reactor.core.publisher.Flux.range(0, 500)
                .flatMap(i -> loadClient.post().uri(target)
                        .send(ByteBufFlux.fromString(Mono.just(body(i))))
                        .responseSingle((response, content) -> content.then(Mono.just(response.status().code()))), 50)
                .filter(status -> status != 200)
                .blockFirst(Duration.ofSeconds(60));
        if (bad != null) {
            throw new IllegalStateException("Warm-up request failed with HTTP " + bad + "; is the gateway connected to the simulator?");
        }
    }

    private double gatewayMetric(String name, String tag) {
        try {
            String url = metricsBase + "/metrics/" + name + (tag == null ? "" : "?tag=" + tag);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            Matcher m = METRIC_VALUE.matcher(r.body());
            return r.statusCode() == 200 && m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return Double.NaN;
        }
    }

    private long ok() {
        LongAdder c = statusCounts.get(200);
        return c == null ? 0 : c.sum();
    }

    private long failures() {
        long nonOk = statusCounts.entrySet().stream().filter(e -> e.getKey() != 200).mapToLong(e -> e.getValue().sum()).sum();
        return nonOk + transportErrors.sum() + rejectedLocally.sum();
    }

    private record IntervalStats(long elapsedSeconds, double tps, long count, double p50, double p95, double p99, double max,
                                 long errors, int inFlight, double heapUsedMb, double oldGenMb, double gatewayPending) {
    }

    private final class Reporter implements Runnable {
        private final long t0;
        private long lastCompleted;
        private long lastFailures;
        private long lastNanos;

        Reporter(long t0) {
            this.t0 = t0;
            this.lastNanos = t0;
        }

        @Override
        public synchronized void run() {
            long now = System.nanoTime();
            long done = completed.sum();
            long fails = failures();
            double seconds = (now - lastNanos) / 1e9;
            IntervalStats s = new IntervalStats(
                    Math.round((now - t0) / 1e9),
                    (done - lastCompleted) / seconds,
                    interval.count(),
                    interval.percentileMillis(50), interval.percentileMillis(95), interval.percentileMillis(99), interval.maxMillis(),
                    fails - lastFailures,
                    maxInFlight - inFlight.availablePermits(),
                    gatewayMetric("jvm.memory.used", "area:heap") / 1_048_576,
                    gatewayMetric("jvm.memory.used", "id:G1%20Old%20Gen") / 1_048_576,
                    gatewayMetric("diameter.client.pending", null));
            interval.reset();
            lastCompleted = done;
            lastFailures = fails;
            lastNanos = now;
            if (s.count() == 0 && s.inFlight() == 0) {
                return;
            }
            synchronized (timeline) {
                timeline.add(s);
            }
            log("t=%5ds sent=%,9d done=%,9d tps=%6.1f p50=%6.2fms p95=%6.2fms p99=%6.2fms max=%7.2fms errors=%d inflight=%d heap=%.0fMB oldgen=%.0fMB",
                    s.elapsedSeconds(), sent.get(), done, s.tps(), s.p50(), s.p95(), s.p99(), s.max(), s.errors(), s.inFlight(),
                    s.heapUsedMb(), s.oldGenMb());
        }
    }

    private void writeReport(Instant startedAt, Duration elapsed) throws IOException {
        List<IntervalStats> rows;
        synchronized (timeline) {
            rows = new ArrayList<>(timeline);
        }
        double p95 = overall.percentileMillis(95);
        long failures = failures();
        double errorRate = 100.0 * failures / Math.max(1, total);
        double achievedTps = overall.count() / (elapsed.toMillis() / 1000.0);
        double[] oldGen = rows.stream().mapToDouble(IntervalStats::oldGenMb).filter(v -> !Double.isNaN(v) && v > 0).toArray();
        String oldGenTrend = oldGen.length < 4 ? "n/a" : String.format("%.1f MB (first quarter avg) → %.1f MB (last quarter avg)",
                avg(oldGen, 0, oldGen.length / 4), avg(oldGen, oldGen.length - oldGen.length / 4, oldGen.length));
        double heapMax = rows.stream().mapToDouble(IntervalStats::heapUsedMb).filter(v -> !Double.isNaN(v)).max().orElse(Double.NaN);
        boolean pass = p95 < 100 && errorRate < 0.1;

        Map<Integer, Long> statuses = new TreeMap<>();
        statusCounts.forEach((k, v) -> statuses.put(k, v.sum()));

        Files.createDirectories(report.toAbsolutePath().getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(report))) {
            out.printf("# Telecom-Bridge load test report%n%n");
            out.printf("| | |%n|---|---|%n");
            out.printf("| Result | **%s** (p95 < 100 ms and error rate < 0.1%%) |%n", pass ? "PASS" : "FAIL");
            out.printf("| Started | %s |%n", TS.format(startedAt));
            out.printf("| Duration | %s |%n", elapsed.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            out.printf("| Target | `POST %s` |%n", target);
            out.printf("| Offered load | %d TPS, open model, %,d transactions |%n", tps, total);
            out.printf("| Achieved throughput | %.2f TPS |%n", achievedTps);
            out.printf("| Completed | %,d |%n", overall.count() + rejectedLocally.sum());
            out.printf("| HTTP status counts | %s |%n", statuses);
            out.printf("| Transport errors / locally rejected | %d / %d |%n", transportErrors.sum(), rejectedLocally.sum());
            out.printf("| Error rate | %.4f %% |%n", errorRate);
            out.printf("| Latency mean | %.2f ms |%n", overall.meanMillis());
            out.printf("| Latency p50 / p90 / p95 / p99 / p99.9 | %.2f / %.2f / **%.2f** / %.2f / %.2f ms |%n",
                    overall.percentileMillis(50), overall.percentileMillis(90), p95, overall.percentileMillis(99), overall.percentileMillis(99.9));
            out.printf("| Latency max | %.2f ms |%n", overall.maxMillis());
            out.printf("| Service time p50 / p95 / p99 (from actual send) | %.2f / %.2f / %.2f ms |%n",
                    serviceTime.percentileMillis(50), serviceTime.percentileMillis(95), serviceTime.percentileMillis(99));
            out.printf("| Generator send lag p99 / max | %.3f / %.3f ms |%n", sendLag.percentileMillis(99), sendLag.maxMillis());
            out.printf("| Gateway heap used (peak sample) | %.1f MB |%n", heapMax);
            out.printf("| Gateway G1 Old Gen | %s |%n", oldGenTrend);
            out.printf("%nLatency is measured from each request's scheduled send time (coordinated-omission corrected) and "
                    + "includes the simulator's deliberate 50-100 ms CCA delay.%n");
            out.printf("%n## Timeline (%d s intervals)%n%n", REPORT_INTERVAL_SECONDS);
            out.printf("| t (s) | TPS | p50 ms | p95 ms | p99 ms | max ms | errors | heap MB | old gen MB |%n");
            out.printf("|---:|---:|---:|---:|---:|---:|---:|---:|---:|%n");
            for (IntervalStats r : rows) {
                out.printf("| %d | %.1f | %.2f | %.2f | %.2f | %.2f | %d | %.0f | %.1f |%n", r.elapsedSeconds(), r.tps(),
                        r.p50(), r.p95(), r.p99(), r.max(), r.errors(), r.heapUsedMb(), r.oldGenMb());
            }
        }
        Path csv = report.resolveSibling(report.getFileName().toString().replaceFirst("\\.md$", "") + ".csv");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(csv))) {
            out.println("elapsed_s,tps,p50_ms,p95_ms,p99_ms,max_ms,errors,in_flight,heap_used_mb,old_gen_mb,gateway_pending");
            for (IntervalStats r : rows) {
                out.printf("%d,%.2f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.1f,%.1f,%.0f%n", r.elapsedSeconds(), r.tps(), r.p50(), r.p95(),
                        r.p99(), r.max(), r.errors(), r.inFlight(), r.heapUsedMb(), r.oldGenMb(), r.gatewayPending());
            }
        }
        log("RESULT %s: %,d tx in %s, %.2f TPS, p50=%.2fms p95=%.2fms p99=%.2fms, errors=%d (%.4f%%). Report: %s",
                pass ? "PASS" : "FAIL", overall.count(), elapsed, achievedTps, overall.percentileMillis(50), p95,
                overall.percentileMillis(99), failures, errorRate, report.toAbsolutePath());
        if (!pass) {
            System.exit(1);
        }
    }

    private static double avg(double[] v, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += v[i];
        }
        return sum / Math.max(1, to - from);
    }

    private static Map<String, String> parse(String[] argv) {
        Map<String, String> args = new TreeMap<>();
        for (int i = 0; i + 1 < argv.length; i += 2) {
            if (!argv[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --option value, got " + argv[i]);
            }
            args.put(argv[i].substring(2), argv[i + 1]);
        }
        return args;
    }

    private static void log(String format, Object... args) {
        System.out.printf("%s %s%n", TS.format(Instant.now()), String.format(format, args));
    }
}
