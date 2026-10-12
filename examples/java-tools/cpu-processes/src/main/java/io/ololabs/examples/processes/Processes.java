// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.processes;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CPU usage and the processes behind it. Read-only, and it never returns command-line arguments, which often carry
 * passwords and tokens: a process is shown by its executable name, owner and resource use only.
 */
@ToolPackage(id = "examples.processes", name = "CPU and processes", publisher = "ToolGate Examples",
        description = "Measures CPU usage, lists the busiest processes and checks whether a process is running.")
public class Processes {
    public record CpuUsage(
            @Param(description = "Whole-machine CPU use over the sample, percent") double systemPercent,
            @Param(description = "Logical processors") int processors,
            @Param(description = "1-minute load average; -1 where the OS has none (Windows)") double loadAverage,
            @Param(description = "Sample length in milliseconds") long sampleMs) {}

    @ServerTool(name = "cpu", title = "CPU usage",
            description = "Measures this machine's CPU use over a short sample, with processor count and load average.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"sampleMs\":200}")
    public CpuUsage cpu(
            @Param(description = "How long to sample, in milliseconds", defaultValue = "1000", min = "100", max = "10000")
            int sampleMs,
            ToolContext context) {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        os.getCpuLoad();
        sleep(sampleMs);
        double load = os.getCpuLoad();
        return new CpuUsage(round(Math.max(0, load) * 100), os.getAvailableProcessors(),
                round(Math.max(-1, os.getSystemLoadAverage())), sampleMs);
    }

    public record ProcessInfo(
            @Param(description = "Process id") long pid,
            @Param(description = "Executable name, without its arguments") String name,
            @Param(description = "Owner, or empty when the OS does not say") String user,
            @Param(description = "CPU use over the sample, percent of one processor (so up to 100 x processors)") double cpuPercent,
            @Param(description = "CPU time used since the process started, seconds") double cpuSecondsTotal,
            @Param(description = "When it started, ISO 8601, or empty") String startedAt) {}

    public record TopProcesses(
            @Param(description = "Processes, busiest first") List<ProcessInfo> processes,
            @Param(description = "Processes seen") int processCount,
            @Param(description = "Sample length in milliseconds") long sampleMs) {}

    @ServerTool(name = "top", title = "Top processes",
            description = "Lists the processes using the most CPU over a short sample, like top or Task Manager.",
            useWhen = "Use it when a machine is slow or its CPU is high, to find the process responsible.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"limit\":3,\"sampleMs\":200}")
    public TopProcesses top(
            @Param(description = "How many processes to return", defaultValue = "10", min = "1", max = "100") int limit,
            @Param(description = "How long to sample, in milliseconds", defaultValue = "1000", min = "100", max = "10000")
            int sampleMs,
            ToolContext context) {
        Map<Long, Duration> before = new HashMap<>();
        ProcessHandle.allProcesses().forEach(p -> p.info().totalCpuDuration().ifPresent(d -> before.put(p.pid(), d)));
        long started = System.nanoTime();
        sleep(sampleMs);
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        List<ProcessInfo> all = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(p -> {
            ProcessHandle.Info info = p.info();
            Duration now = info.totalCpuDuration().orElse(null);
            Duration then = before.get(p.pid());
            double percent = now == null || then == null ? 0 : 100.0 * (now.toNanos() - then.toNanos()) / 1_000_000.0 / elapsedMs;
            all.add(describe(p, Math.max(0, percent)));
        });
        all.sort(Comparator.comparingDouble(ProcessInfo::cpuPercent).thenComparingDouble(ProcessInfo::cpuSecondsTotal).reversed());
        return new TopProcesses(List.copyOf(all.subList(0, Math.min(limit, all.size()))), all.size(), sampleMs);
    }

    public record Found(
            @Param(description = "True when at least one process matched") boolean running,
            @Param(description = "Matching processes") int count,
            @Param(description = "The matches, oldest first, at most 50") List<ProcessInfo> processes) {}

    @ServerTool(name = "find", title = "Find a process",
            description = "Checks whether a process is running, by executable name (case-insensitive; .exe optional),"
                    + " and returns each match's pid, owner, start time and CPU time.",
            useWhen = "Use it to answer 'is the service or agent X running on this machine?'.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"name\":\"no-such-process-name\"}", expectedOutput = "{\"running\":false,\"count\":0,\"processes\":[]}")
    public Found find(
            @Param(description = "Executable name, such as nginx, java or sqlservr.exe", minLength = 1, maxLength = 128)
            String name,
            @Param(description = "Match names containing the text instead of equal to it", defaultValue = "false")
            boolean partial,
            ToolContext context) {
        String wanted = normalise(name);
        List<ProcessInfo> matches = ProcessHandle.allProcesses()
                .filter(p -> {
                    String n = normalise(executable(p));
                    return !n.isEmpty() && (partial ? n.contains(wanted) : n.equals(wanted));
                })
                .map(p -> describe(p, 0))
                .sorted(Comparator.comparing(ProcessInfo::startedAt))
                .toList();
        return new Found(!matches.isEmpty(), matches.size(), List.copyOf(matches.subList(0, Math.min(50, matches.size()))));
    }

    static ProcessInfo describe(ProcessHandle p, double cpuPercent) {
        ProcessHandle.Info info = p.info();
        return new ProcessInfo(p.pid(), executable(p), info.user().orElse(""), round(cpuPercent),
                round(info.totalCpuDuration().map(d -> d.toMillis() / 1000.0).orElse(0.0)),
                info.startInstant().map(Instant::toString).orElse(""));
    }

    static String executable(ProcessHandle p) {
        // Split by hand: a command path from another OS user may not parse as a Path.
        return p.info().command().map(c -> c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('\\')) + 1)).orElse("");
    }

    static String normalise(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
