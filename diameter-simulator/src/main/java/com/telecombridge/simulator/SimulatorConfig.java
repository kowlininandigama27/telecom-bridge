package com.telecombridge.simulator;

import java.time.Duration;

/**
 * Simulator settings. Every value can be overridden by an environment variable (shown in brackets)
 * or a {@code -D} system property with the same name in lower-case dotted form, e.g.
 * {@code -Dsim.port=3868}.
 *
 * @param port              listen port [SIM_PORT], default 3868
 * @param originHost        Origin-Host [SIM_ORIGIN_HOST]
 * @param originRealm       Origin-Realm [SIM_ORIGIN_REALM]
 * @param minDelay          lower bound of simulated CCR processing delay [SIM_MIN_DELAY_MS], default 50
 * @param maxDelay          upper bound of simulated CCR processing delay [SIM_MAX_DELAY_MS], default 100
 * @param watchdogInterval  idle time after which the simulator itself sends DWR, 0 disables [SIM_WATCHDOG_SECONDS]
 * @param ioThreads         Netty worker threads, 0 = Netty default [SIM_IO_THREADS]
 * @param preciseTimer      release CCAs from a spin-assisted timer thread so OS timer slack does not
 *                          stretch the delay beyond its configured bounds [SIM_PRECISE_TIMER], default true
 */
public record SimulatorConfig(int port, String originHost, String originRealm, Duration minDelay, Duration maxDelay,
                              Duration watchdogInterval, int ioThreads, boolean preciseTimer) {

    public SimulatorConfig {
        if (minDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException("minDelay must be <= maxDelay");
        }
    }

    public static SimulatorConfig defaults() {
        return new SimulatorConfig(3868, "ocs.telecom-bridge.local", "telecom-bridge.local",
                Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(30), 0, true);
    }

    public static SimulatorConfig fromEnvironment() {
        SimulatorConfig d = defaults();
        return new SimulatorConfig(
                Integer.parseInt(setting("SIM_PORT", String.valueOf(d.port()))),
                setting("SIM_ORIGIN_HOST", d.originHost()),
                setting("SIM_ORIGIN_REALM", d.originRealm()),
                Duration.ofMillis(Long.parseLong(setting("SIM_MIN_DELAY_MS", "50"))),
                Duration.ofMillis(Long.parseLong(setting("SIM_MAX_DELAY_MS", "100"))),
                Duration.ofSeconds(Long.parseLong(setting("SIM_WATCHDOG_SECONDS", "30"))),
                Integer.parseInt(setting("SIM_IO_THREADS", "0")),
                Boolean.parseBoolean(setting("SIM_PRECISE_TIMER", "true")));
    }

    public SimulatorConfig withPort(int newPort) {
        return new SimulatorConfig(newPort, originHost, originRealm, minDelay, maxDelay, watchdogInterval, ioThreads, preciseTimer);
    }

    public SimulatorConfig withDelay(Duration min, Duration max) {
        return new SimulatorConfig(port, originHost, originRealm, min, max, watchdogInterval, ioThreads, preciseTimer);
    }

    private static String setting(String envName, String fallback) {
        String property = System.getProperty(envName.toLowerCase().replace('_', '.'));
        if (property != null) {
            return property;
        }
        String env = System.getenv(envName);
        return env != null ? env : fallback;
    }
}
