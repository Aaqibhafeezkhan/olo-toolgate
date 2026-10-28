// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.incidents;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A mocked incident response desk for a server crash, end to end: see the open incidents, inspect the crashed host,
 * read the crash analysis, fetch the runbook for the cause, run a remediation, verify recovery, and update the
 * incident. All hosts, logs and outcomes are fictional and fixed, so an on-call agent can be built and rehearsed
 * without touching production. The remediation tool is where a real integration would call your orchestration
 * (Ansible, Rundeck, Kubernetes, a cloud API); keep its business key so a retried call never restarts twice.
 *
 * <p>Scenario: payments-api on app-02 was killed by the out-of-memory killer after release 2.14.0 (INC-5001, P1);
 * db-01 is filling its data disk (INC-5002, P2); web-03 is healthy.
 */
@ToolPackage(id = "examples.incidents", name = "Server crash recovery (mocked)", publisher = "ToolGate Examples",
        description = "Mocked incident response: incidents, host status, crash analysis, runbooks, remediation and verification.")
public class ServerRecovery {
    // ---- data ----

    public record Incident(
            @Param(description = "Incident id") String incidentId,
            @Param(description = "P1 (outage) to P4") String priority,
            @Param(description = "Summary") String title,
            @Param(description = "Host affected") String host,
            @Param(description = "Service affected") String service,
            @Param(description = "OPEN, INVESTIGATING, MITIGATED or RESOLVED") String status,
            @Param(description = "Opened at, ISO 8601") String openedAt) {}

    public record ServiceState(
            @Param(description = "Service") String name,
            @Param(description = "RUNNING, STOPPED, CRASHED or DEGRADED") String state,
            @Param(description = "Version deployed") String version) {}

    public record HostStatus(
            @Param(description = "Host") String host,
            @Param(description = "HEALTHY, DEGRADED or DOWN") String state,
            @Param(description = "Last heartbeat, ISO 8601") String lastHeartbeat,
            @Param(description = "CPU use, percent") int cpuPercent,
            @Param(description = "Memory use, percent") int memoryPercent,
            @Param(description = "Fullest disk use, percent") int diskPercent,
            @Param(description = "Services on the host") List<ServiceState> services) {}

    public record CrashReport(
            @Param(description = "Host") String host,
            @Param(description = "Service that crashed") String service,
            @Param(description = "When, ISO 8601") String crashedAt,
            @Param(description = "Probable cause: OOM, DISK_FULL, DEADLOCK or KERNEL_PANIC") String cause,
            @Param(description = "Explanation") String summary,
            @Param(description = "Log lines that support it") List<String> evidence,
            @Param(description = "Recent change that may be related, or empty") String relatedChange,
            @Param(description = "HIGH, MEDIUM or LOW") String confidence) {}

    static final class Data {
        static final List<Incident> INCIDENTS = List.of(
                new Incident("INC-5001", "P1", "payments-api down on app-02: checkout failing", "app-02", "payments-api",
                        "OPEN", "2026-10-12T14:07:31Z"),
                new Incident("INC-5002", "P2", "db-01 data disk at 94%", "db-01", "postgres", "INVESTIGATING",
                        "2026-10-12T13:40:02Z"));
        static final Map<String, HostStatus> HOSTS = Map.of(
                "app-02", new HostStatus("app-02", "DEGRADED", "2026-10-12T14:12:00Z", 6, 31, 58, List.of(
                        new ServiceState("payments-api", "CRASHED", "2.14.0"),
                        new ServiceState("node-exporter", "RUNNING", "1.8.2"))),
                "db-01", new HostStatus("db-01", "DEGRADED", "2026-10-12T14:12:05Z", 44, 71, 94, List.of(
                        new ServiceState("postgres", "RUNNING", "16.4"),
                        new ServiceState("wal-archiver", "DEGRADED", "3.1.0"))),
                "web-03", new HostStatus("web-03", "HEALTHY", "2026-10-12T14:12:03Z", 22, 48, 37, List.of(
                        new ServiceState("nginx", "RUNNING", "1.27.1"))));
        static final Map<String, CrashReport> CRASHES = Map.of(
                "app-02", new CrashReport("app-02", "payments-api", "2026-10-12T14:06:58Z", "OOM",
                        "The JVM heap grew to its 2 GiB limit within 40 minutes of release 2.14.0 and the kernel"
                                + " out-of-memory killer ended the process. The service has restarted and crashed 3 times since.",
                        List.of("14:06:51 WARN  GC overhead: 97% of time in GC over last 60 s",
                                "14:06:57 ERROR java.lang.OutOfMemoryError: Java heap space at c.p.ReceiptCache.put",
                                "14:06:58 kernel: Out of memory: Killed process 4121 (java) total-vm:3.4GiB",
                                "14:07:30 systemd: payments-api.service: Start request repeated too quickly"),
                        "Release 2.14.0 deployed 13:25 (added ReceiptCache with no size limit)", "HIGH"),
                "db-01", new CrashReport("db-01", "wal-archiver", "2026-10-12T13:38:44Z", "DISK_FULL",
                        "WAL archiving to object storage has failed since 11:02 (expired credentials), so WAL files are"
                                + " piling up on the data disk. Postgres will stop accepting writes at 100%.",
                        List.of("11:02:13 ERROR archive command failed: 403 Forbidden",
                                "13:38:44 WARN  pg_wal is 61 GiB (normal: under 4 GiB)"),
                        "Storage credentials rotated 11:00", "MEDIUM"));

        record Runbook(String title, List<String> steps, String risk, int minutes) {}

        static final Map<String, Runbook> RUNBOOKS = Map.of(
                "OOM", new Runbook("Service killed by out-of-memory", List.of(
                        "Confirm the OOM in the crash report and kernel log.",
                        "If a release in the last 24 h correlates, roll it back (ROLLBACK_DEPLOY); otherwise RESTART_SERVICE.",
                        "Verify: service RUNNING, health endpoint 200, error rate under 1% for 10 minutes.",
                        "Open a problem ticket with a heap dump for the owning team."),
                        "A restart alone will crash again within the hour if the leak is in the new release.", 15),
                "DISK_FULL", new Runbook("Disk filling up", List.of(
                        "Find what is growing (WAL, logs, temp files).",
                        "Fix the cause first (here: renew the archive credentials) so space stops growing.",
                        "Run CLEAR_TEMP to remove rotated logs and temp files; never delete WAL by hand.",
                        "Verify disk under 80% and archiving succeeding."),
                        "Deleting the wrong files on a database host can lose data.", 30),
                "DEADLOCK", new Runbook("Service hung (deadlock)", List.of(
                        "Take a thread dump.", "RESTART_SERVICE.", "Verify and attach the dump to a problem ticket."),
                        "Low.", 10),
                "KERNEL_PANIC", new Runbook("Host kernel panic", List.of(
                        "FAILOVER traffic to the standby host.", "Collect the crash dump from the console.",
                        "Rebuild or reboot the host, then fail back after verification."),
                        "Failover briefly drops in-flight requests.", 45));
    }

    static final String HOSTS = "Mocked hosts are app-02, db-01 and web-03.";

    // ---- tools ----

    public record Incidents(@Param(description = "Open incidents, highest priority first") List<Incident> incidents) {}

    @ServerTool(name = "incidents", title = "Open incidents",
            description = "Lists open incidents with priority, host, service and status.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"priority\":\"P1\"}", expectedOutput = "{\"incidents\":[{\"incidentId\":\"INC-5001\",\"priority\":\"P1\","
            + "\"title\":\"payments-api down on app-02: checkout failing\",\"host\":\"app-02\",\"service\":\"payments-api\","
            + "\"status\":\"OPEN\",\"openedAt\":\"2026-10-12T14:07:31Z\"}]}")
    public Incidents incidents(
            @Param(description = "Only this priority", required = false, enumValues = {"P1", "P2", "P3", "P4"}) String priority,
            ToolContext context) {
        return new Incidents(Data.INCIDENTS.stream().filter(i -> priority == null || i.priority().equals(priority)).toList());
    }

    @ServerTool(name = "host-status", title = "Host status",
            description = "Returns a host's state, last heartbeat, CPU, memory and disk use, and the state of each service on it.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "HOST_NOT_FOUND", description = "No host has that name."))
    @Example(arguments = "{\"host\":\"mainframe-9\"}", expectedError = "HOST_NOT_FOUND")
    public HostStatus hostStatus(@Param(description = "Host name, such as app-02", maxLength = 64) String host, ToolContext context) {
        return host(Data.HOSTS, host);
    }

    @ServerTool(name = "crash-report", title = "Crash analysis",
            description = "Returns the analysis of the latest crash on a host: probable cause, evidence from the logs,"
                    + " related recent change and confidence.",
            useWhen = "Use it after host-status shows a CRASHED or DEGRADED service, before choosing a remediation.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "HOST_NOT_FOUND", description = "No host has that name, or it has no crash."))
    public CrashReport crashReport(@Param(description = "Host name", maxLength = 64) String host, ToolContext context) {
        return host(Data.CRASHES, host);
    }

    public record Runbook(
            @Param(description = "Runbook title") String title,
            @Param(description = "Steps in order") List<String> steps,
            @Param(description = "What can go wrong") String risk,
            @Param(description = "Typical time to recover, minutes") int estimatedMinutes) {}

    @ServerTool(name = "runbook", title = "Get a runbook",
            description = "Returns the recovery runbook for a crash cause.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"cause\":\"DEADLOCK\"}", expectedOutput = "{\"title\":\"Service hung (deadlock)\",\"steps\":["
            + "\"Take a thread dump.\",\"RESTART_SERVICE.\",\"Verify and attach the dump to a problem ticket.\"],\"risk\":\"Low.\","
            + "\"estimatedMinutes\":10}")
    public Runbook runbook(
            @Param(description = "Cause from crash-report", enumValues = {"OOM", "DISK_FULL", "DEADLOCK", "KERNEL_PANIC"}) String cause,
            ToolContext context) {
        Data.Runbook r = Data.RUNBOOKS.get(cause);
        return new Runbook(r.title(), r.steps(), r.risk(), r.minutes());
    }

    public record Remediation(
            @Param(description = "Id to pass to incidents.verify") String remediationId,
            @Param(description = "Action taken") String action,
            @Param(description = "Host") String host,
            @Param(description = "Service") String service,
            @Param(description = "What was done, step by step (simulated)") List<String> log,
            @Param(description = "SUCCEEDED or FAILED") String outcome) {}

    @ServerTool(name = "remediate", title = "Run a remediation",
            description = "Runs a recovery action on a host (mocked: simulated, nothing changes): restart a service,"
                    + " roll back the last deploy, fail over to the standby, or clear temp files and rotated logs. A"
                    + " repeat with the same reference returns the first result instead of acting twice.",
            useWhen = "Use it only after reading the crash report and runbook, and after the user approves the action.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = @ErrorDecl(code = "HOST_NOT_FOUND", description = "No host or service has that name."), reconciliation = Reconciliation.MANUAL)
    @Example(arguments = "{\"host\":\"app-02\",\"service\":\"payments-api\",\"action\":\"ROLLBACK_DEPLOY\",\"reference\":\"INC-5001-a1\"}")
    public Remediation remediate(
            @Param(description = "Host", maxLength = 64) String host,
            @Param(description = "Service on the host", maxLength = 64) String service,
            @Param(description = "What to do", enumValues = {"RESTART_SERVICE", "ROLLBACK_DEPLOY", "FAILOVER", "CLEAR_TEMP"}) String action,
            @Param(description = "Your change or incident step id; a repeat with the same reference acts once", minLength = 1,
                    maxLength = 64) String reference,
            ToolContext context) {
        HostStatus h = host(Data.HOSTS, host);
        ServiceState s = h.services().stream().filter(x -> x.name().equalsIgnoreCase(service.trim())).findFirst()
                .orElseThrow(() -> new ToolBusinessError("HOST_NOT_FOUND", "No service " + service + " on " + h.host() + "."));
        List<String> log = switch (action) {
            case "RESTART_SERVICE" -> List.of("Drained " + h.host() + " from the load balancer.",
                    "systemctl restart " + s.name() + ": started.", "Health endpoint answered 200 after 18 s.",
                    "Returned " + h.host() + " to the load balancer.");
            case "ROLLBACK_DEPLOY" -> List.of("Drained " + h.host() + " from the load balancer.",
                    "Rolled " + s.name() + " back from " + s.version() + " to the previous release 2.13.4.",
                    "Started " + s.name() + "; heap steady at 41% after 2 minutes.", "Returned " + h.host() + " to the load balancer.");
            case "FAILOVER" -> List.of("Promoted the standby for " + s.name() + ".", "Moved the virtual IP to the standby.",
                    "Traffic now served by the standby; " + h.host() + " is out of rotation.");
            default -> List.of("Removed 11.2 GiB of rotated logs and temp files under /var/log and /tmp.",
                    "Fullest disk now " + Math.max(0, h.diskPercent() - 21) + "%.");
        };
        return new Remediation("REM-" + shortHash(reference), action, h.host(), s.name(), log, "SUCCEEDED");
    }

    public record Verification(
            @Param(description = "True when every check passed") boolean recovered,
            @Param(description = "Each check and its result") List<String> checks,
            @Param(description = "Suggested next step") String next) {}

    @ServerTool(name = "verify", title = "Verify recovery",
            description = "Runs post-recovery checks for a remediation: service state, health endpoint, error rate and"
                    + " resource use (mocked).", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"remediationId\":\"REM-1234abcd\"}")
    public Verification verify(@Param(description = "Id from incidents.remediate", pattern = "^REM-[0-9a-f]{8}$") String remediationId,
            ToolContext context) {
        return new Verification(true, List.of("Service state: RUNNING", "Health endpoint: 200 in 84 ms",
                "Error rate (10 min): 0.2%, under the 1% threshold", "Memory: 41%, steady"),
                "Mark the incident MITIGATED and open a problem ticket for the root cause.");
    }

    public record IncidentUpdate(
            @Param(description = "Incident id") String incidentId,
            @Param(description = "New status") String status,
            @Param(description = "Timeline entry added") String timelineEntry,
            @Param(description = "Who was notified") List<String> notified) {}

    @ServerTool(name = "update-incident", title = "Update an incident",
            description = "Sets an incident's status and adds a note to its timeline (mocked: nothing is stored). Stakeholders"
                    + " are notified on MITIGATED and RESOLVED.",
            effect = EffectSafety.NON_IDEMPOTENT, businessKeyArgument = "reference",
            errors = @ErrorDecl(code = "INCIDENT_NOT_FOUND", description = "No open incident has that id."), reconciliation = Reconciliation.MANUAL)
    @Example(arguments = "{\"incidentId\":\"INC-5001\",\"status\":\"MITIGATED\",\"note\":\"Rolled back to 2.13.4\",\"reference\":\"u1\"}",
            expectedOutput = "{\"incidentId\":\"INC-5001\",\"status\":\"MITIGATED\",\"timelineEntry\":\"MITIGATED: Rolled back to 2.13.4\","
                    + "\"notified\":[\"#inc-5001\",\"payments-oncall\",\"status page\"]}")
    public IncidentUpdate updateIncident(
            @Param(description = "Incident id, such as INC-5001", maxLength = 20) String incidentId,
            @Param(description = "New status", enumValues = {"INVESTIGATING", "MITIGATED", "RESOLVED"}) String status,
            @Param(description = "What happened", minLength = 1, maxLength = 1000) String note,
            @Param(description = "Your update id; a repeat with the same reference is added once", minLength = 1, maxLength = 64)
            String reference,
            ToolContext context) {
        Incident i = Data.INCIDENTS.stream().filter(x -> x.incidentId().equalsIgnoreCase(incidentId.trim())).findFirst()
                .orElseThrow(() -> new ToolBusinessError("INCIDENT_NOT_FOUND", "No open incident has that id."));
        String team = i.service().equals("postgres") ? "dba-oncall" : i.service().replace("-api", "") + "-oncall";
        List<String> notified = status.equals("INVESTIGATING") ? List.of("#" + i.incidentId().toLowerCase(Locale.ROOT))
                : List.of("#" + i.incidentId().toLowerCase(Locale.ROOT), team, "status page");
        return new IncidentUpdate(i.incidentId(), status, status + ": " + note, notified);
    }

    static <T> T host(Map<String, T> data, String host) {
        T value = data.get(host.trim().toLowerCase(Locale.ROOT));
        if (value == null) throw new ToolBusinessError("HOST_NOT_FOUND", "No crash or host data for " + host + ". " + HOSTS);
        return value;
    }

    static String shortHash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
