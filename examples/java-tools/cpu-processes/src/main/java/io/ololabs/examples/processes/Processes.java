// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.processes;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.annotations.ValuePhrasing;
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
    @Training(
            phrasings = {
                "How busy is the CPU right now?",
                "What's the CPU usage on this machine?",
                "Check the processor load",
                "Is the CPU maxed out?",
                "Measure CPU use over {sampleMs} ms",
                "Sample the CPU for {sampleMs} milliseconds and tell me the load",
                "What's the load average on this box?",
                "How many cores does this machine have and how loaded are they?",
                "cpu %?",
                "Why is the machine slow? Check CPU utilisation"
            },
            defaultPhrasings = {
                "CPU usage?",
                "Check the CPU load please",
                "How busy is the CPU?",
                "Check CPU",
                "What's the processor load?",
                "cpu load?",
                "Is the CPU busy?",
                "Measure the CPU",
                "Show CPU use",
                "How loaded is the processor?",
                "CPU check please"
            },
            negatives = {
                "What is a CPU cache and how does it work?",
                "Which CPU should I buy for a gaming PC?",
                "Explain what load average means on Linux",
                "How do I overclock my processor?"
            },
            parameters = @TrainingValues(name = "sampleMs",
                    examples = {
                        "250",
                        "500",
                        "2000",
                        "3000",
                        "5000"
                    }))
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
    @Training(
            phrasings = {
                "Which processes are using the most CPU?",
                "Show me the top {limit} processes by CPU",
                "What's hogging the CPU?",
                "List the {limit} busiest processes",
                "Like top: what is eating the processor?",
                "Top CPU consumers over a {sampleMs} ms sample",
                "Which program is making the machine slow?",
                "Show the heaviest processes, top {limit}",
                "What's using all the CPU right now, sample {sampleMs} ms"
            },
            defaultPhrasings = {
                "What's using the CPU?",
                "Show the top processes",
                "Top processes?",
                "Which processes are busiest?",
                "Show top",
                "What's hogging the processor?",
                "List the heaviest processes",
                "Who's eating CPU?",
                "Busiest processes please",
                "top",
                "What processes use the most CPU?"
            },
            negatives = {
                "How do I use the top command on Linux?",
                "How do I kill a process in Task Manager?",
                "What's the difference between a process and a thread?"
            },
            parameters = {
                @TrainingValues(name = "limit",
                    examples = {
                        "3",
                        "5",
                        "15",
                        "20",
                        "25"
                    }),
                @TrainingValues(name = "sampleMs",
                    examples = {
                        "500",
                        "2000",
                        "3000",
                        "5000"
                    })
            })
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
    @Training(
            phrasings = {
                "Is {name} running?",
                "Check whether {name} is running on this machine",
                "Is the {name} process up?",
                "Find the {name} process",
                "Do we have {name} running right now?",
                "Is anything named {name} running? {partial}",
                "Look for processes matching {name}, {partial}",
                "Is {name} alive or did it die?",
                "Show me the PID of {name}"
            },
            defaultPhrasings = {
                "Is {name} running?",
                "Find process {name}"
            },
            negatives = {
                "How do I install nginx?",
                "What does the java process do?",
                "How do I start postgres as a service?"
            },
            parameters = {
                @TrainingValues(name = "name",
                    examples = {
                        "nginx",
                        "java",
                        "sqlservr.exe",
                        "postgres",
                        "node",
                        "redis-server",
                        "chrome",
                        "python",
                        "w3wp.exe",
                        "httpd"
                    }),
                @TrainingValues(name = "partial",
                    phrasings = @ValuePhrasing(value = "true", phrasings = {
                            "partial match",
                            "names containing it",
                            "match any name that contains it"
                        }))
            })
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
