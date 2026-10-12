// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.health;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One call that answers "is this machine healthy?": CPU, memory, swap and disk against thresholds the administrator
 * sets, with an overall OK, WARN or CRITICAL. Pure Java, so the same jar works on Linux, Windows and macOS.
 */
@ToolPackage(id = "examples.health", name = "System health", publisher = "ToolGate Examples",
        description = "Reports CPU, memory, swap and disk health of the machine the tool runs on.")
@Global(name = "CpuWarnPercent", type = ValueType.INT, defaultValue = "85", min = "1", max = "100",
        description = "System CPU load at or above which the CPU check warns.")
@Global(name = "MemoryWarnPercent", type = ValueType.INT, defaultValue = "90", min = "1", max = "100",
        description = "Memory use at or above which the memory check warns.")
@Global(name = "DiskWarnPercent", type = ValueType.INT, defaultValue = "85", min = "1", max = "100",
        description = "Disk use at or above which a disk check warns.")
@Global(name = "CriticalPercent", type = ValueType.INT, defaultValue = "95", min = "1", max = "100",
        description = "Use at or above which any check is critical.")
@Global(name = "DiskPaths", type = ValueType.STRING, defaultValue = "*",
        description = "Comma-separated paths whose volumes are checked, or * for every volume.")
public class SystemHealth {
    public record Check(
            @Param(description = "What was checked, such as cpu, memory or disk:/var") String name,
            @Param(description = "OK, WARN or CRITICAL") String status,
            @Param(description = "Percentage in use") double percentUsed,
            @Param(description = "Human-readable detail") String detail) {}

    public record Health(
            @Param(description = "Worst status of all checks: OK, WARN or CRITICAL") String status,
            @Param(description = "Host name") String host,
            @Param(description = "Operating system name, version and architecture") String os,
            @Param(description = "Logical processors") int processors,
            @Param(description = "1-minute load average; -1 where the OS has none (Windows)") double loadAverage,
            @Param(description = "Seconds since the machine booted; -1 when unknown") long uptimeSeconds,
            @Param(description = "When the snapshot was taken, ISO 8601") String checkedAt,
            @Param(description = "Each check, worst first") List<Check> checks) {}

    @ServerTool(name = "system", title = "System health",
            description = "Takes a health snapshot of this machine: CPU load, memory, swap and disk use, each rated OK,"
                    + " WARN or CRITICAL against the configured thresholds, with an overall status.",
            useWhen = "Use it first when asked whether a server is healthy, slow or running out of resources.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{}")
    public Health system(ToolContext context) {
        var values = context.global();
        int cpuWarn = values.getIntValue("CpuWarnPercent");
        int memoryWarn = values.getIntValue("MemoryWarnPercent");
        int diskWarn = values.getIntValue("DiskWarnPercent");
        int critical = values.getIntValue("CriticalPercent");
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        List<Check> checks = new ArrayList<>();
        double cpu = cpuLoad(os) * 100;
        checks.add(new Check("cpu", rate(cpu, cpuWarn, critical), round(cpu),
                cpu < 0 ? "CPU load is not available" : round(cpu) + "% across " + os.getAvailableProcessors() + " processors"));

        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        double memory = total == 0 ? 0 : 100.0 * (total - free) / total;
        checks.add(new Check("memory", rate(memory, memoryWarn, critical), round(memory),
                gib(total - free) + " of " + gib(total) + " used"));

        long swapTotal = os.getTotalSwapSpaceSize();
        if (swapTotal > 0) {
            double swap = 100.0 * (swapTotal - os.getFreeSwapSpaceSize()) / swapTotal;
            checks.add(new Check("swap", rate(swap, memoryWarn, critical), round(swap),
                    gib(swapTotal - os.getFreeSwapSpaceSize()) + " of " + gib(swapTotal) + " used"));
        }

        for (FileStore store : stores(values.getStringValue("DiskPaths"))) {
            try {
                long size = store.getTotalSpace();
                if (size == 0) continue;
                double used = 100.0 * (size - store.getUsableSpace()) / size;
                checks.add(new Check("disk:" + mount(store), rate(used, diskWarn, critical), round(used),
                        gib(store.getUsableSpace()) + " free of " + gib(size)));
            } catch (IOException e) {
                // A volume that cannot be read, such as an empty card reader, is skipped.
            }
        }
        checks.sort((a, b) -> Integer.compare(severity(b.status()), severity(a.status())));
        String overall = checks.isEmpty() ? "OK" : checks.get(0).status();
        return new Health(overall, host(), os.getName() + " " + os.getVersion() + " (" + os.getArch() + ")",
                os.getAvailableProcessors(), round(Math.max(-1, os.getSystemLoadAverage())), uptimeSeconds(),
                Instant.now().toString(), checks);
    }

    /** The first reading of a fresh JVM is often 0 or negative, so sample twice. */
    static double cpuLoad(com.sun.management.OperatingSystemMXBean os) {
        os.getCpuLoad();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return os.getCpuLoad();
    }

    static List<FileStore> stores(String paths) {
        if (paths.trim().equals("*")) {
            List<FileStore> all = new ArrayList<>();
            for (FileStore s : java.nio.file.FileSystems.getDefault().getFileStores()) {
                if (!s.isReadOnly() && !pseudo(s.type())) all.add(s);
            }
            return all;
        }
        List<FileStore> listed = new ArrayList<>();
        for (String p : Arrays.stream(paths.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            try {
                FileStore s = Files.getFileStore(Path.of(p));
                if (!listed.contains(s)) listed.add(s);
            } catch (IOException e) {
                // A configured path that does not exist on this machine is skipped.
            }
        }
        return listed;
    }

    /** Linux exposes many in-memory file systems; they are not disks. */
    static boolean pseudo(String type) {
        return List.of("tmpfs", "devtmpfs", "proc", "sysfs", "overlay", "squashfs", "cgroup", "cgroup2", "devpts", "mqueue",
                "debugfs", "tracefs", "securityfs", "pstore", "bpf", "autofs", "hugetlbfs", "configfs", "fusectl", "nsfs",
                "ramfs", "binfmt_misc", "efivarfs").contains(type);
    }

    static String mount(FileStore store) {
        // FileStore.toString() is "<mount point> (<device>)" on Linux and macOS and "<label> (C:)" on Windows.
        String s = store.toString();
        int paren = s.lastIndexOf(" (");
        return paren > 0 && !s.endsWith(":)") ? s.substring(0, paren) : s;
    }

    static long uptimeSeconds() {
        try {
            Path proc = Path.of("/proc/uptime");
            if (Files.isReadable(proc)) return (long) Double.parseDouble(Files.readString(proc).trim().split("\\s+")[0]);
        } catch (IOException | RuntimeException e) {
            // Fall through to unknown.
        }
        return -1;
    }

    static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    static String rate(double percent, int warn, int critical) {
        if (percent >= critical) return "CRITICAL";
        return percent >= warn ? "WARN" : "OK";
    }

    static int severity(String status) {
        return switch (status) {
            case "CRITICAL" -> 2;
            case "WARN" -> 1;
            default -> 0;
        };
    }

    static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    static String gib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f GiB", bytes / 1073741824.0);
    }
}
