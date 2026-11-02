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
import io.ololabs.toolgate.sdk.annotations.ModelValidation;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingPrompt;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.annotations.ValuePhrasing;
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
    @Training(
            phrasings = {
                "What incidents are open?",
                "Show me the {priority} incidents",
                "Any open {priority}s?",
                "List current incidents",
                "What's on fire right now?",
                "Open {priority} incidents",
                "Show {priority} incidents",
                "List the {priority} incidents"
            },
            defaultPhrasings = {
                "List the open incidents",
                "What incidents are open?",
                "Show open incidents",
                "Any open incidents?",
                "List incidents",
                "What's currently broken?",
                "incidents?",
                "Show the incident list",
                "Which incidents are active?",
                "What's on fire?",
                "Open incidents please"
            },
            negatives = {
                "How should we define P1 versus P2 incidents?",
                "Write an incident postmortem template"
            },
            parameters = @TrainingValues(name = "priority",
                    phrasings = {
                        @ValuePhrasing(value = "P1", phrasings = {
                            "P1",
                            "priority 1"
                        }),
                        @ValuePhrasing(value = "P2", phrasings = {
                            "P2",
                            "priority 2"
                        }),
                        @ValuePhrasing(value = "P3", phrasings = "P3"),
                        @ValuePhrasing(value = "P4", phrasings = "P4")
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Any open P4s?", arguments = "{\"priority\":\"P4\"}"),
                @TrainingPrompt(prompt = "Open incidents with priority P2", arguments = "{\"priority\":\"P2\"}"),
                @TrainingPrompt(prompt = "What's on fire right now?", arguments = "{}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "anything sev1 open?", arguments = "{\"priority\":\"P1\"}"),
                @TrainingPrompt(prompt = "what incidents are still open", arguments = "{}")
            },
            negatives = {
                "What is the difference between P1 and P2 incidents?"
            })
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
    @Training(
            phrasings = {
                "What's the status of {host}?",
                "Is {host} up?",
                "Which services are running on {host}?",
                "When did {host} last send a heartbeat?",
                "CPU, memory and disk on {host}?",
                "Is {host} healthy?",
                "Last heartbeat from {host}?",
                "How is {host} doing?"
            },
            negatives = "What is a heartbeat in monitoring?",
            parameters = @TrainingValues(name = "host",
                    examples = {
                        "app-02",
                        "app-01",
                        "db-01",
                        "web-03",
                        "cache-01"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Is cache-01 healthy?", arguments = "{\"host\":\"cache-01\"}"),
                @TrainingPrompt(prompt = "CPU, memory and disk on app-02?", arguments = "{\"host\":\"app-02\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "how's web-01 looking right now", arguments = "{\"host\":\"web-01\"}"),
                @TrainingPrompt(prompt = "is db-02 alive?", arguments = "{\"host\":\"db-02\"}")
            },
            negatives = {
                "Out of curiosity, what is a heartbeat in monitoring?"
            })
    public HostStatus hostStatus(@Param(description = "Host name, such as app-02", maxLength = 64) String host, ToolContext context) {
        return host(Data.HOSTS, host);
    }

    @ServerTool(name = "crash-report", title = "Crash analysis",
            description = "Returns the analysis of the latest crash on a host: probable cause, evidence from the logs,"
                    + " related recent change and confidence.",
            useWhen = "Use it after host-status shows a CRASHED or DEGRADED service, before choosing a remediation.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "HOST_NOT_FOUND", description = "No host has that name, or it has no crash."))
    @Training(
            phrasings = {
                "Why did {host} crash?",
                "Crash report for {host}",
                "What caused the last crash on {host}?",
                "Show the crash analysis for {host}",
                "What changed before {host} went down?",
                "Crash analysis for {host}",
                "What killed {host}?"
            },
            negatives = "How do I read a Java crash dump?",
            parameters = @TrainingValues(name = "host",
                    examples = {
                        "app-02",
                        "app-01",
                        "db-01",
                        "web-03",
                        "cache-01"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Why did web-03 crash?", arguments = "{\"host\":\"web-03\"}"),
                @TrainingPrompt(prompt = "What changed before db-01 went down?", arguments = "{\"host\":\"db-01\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "app-02 died last night, what happened?", arguments = "{\"host\":\"app-02\"}"),
                @TrainingPrompt(prompt = "root cause of the cache-01 crash?", arguments = "{\"host\":\"cache-01\"}")
            },
            negatives = {
                "Why do servers crash in general?"
            })
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
    @Training(
            phrasings = {
                "Runbook for {cause}",
                "How do we recover from {cause}?",
                "What are the steps when a host hits {cause}?",
                "Give me the {cause} recovery procedure",
                "What do we do about {cause}?",
                "Recovery steps for {cause}"
            },
            negatives = "What is a runbook?",
            parameters = @TrainingValues(name = "cause",
                    phrasings = {
                        @ValuePhrasing(value = "OOM", phrasings = {
                            "OOM",
                            "out of memory"
                        }),
                        @ValuePhrasing(value = "DISK_FULL", phrasings = {
                            "a full disk",
                            "DISK_FULL"
                        }),
                        @ValuePhrasing(value = "DEADLOCK", phrasings = {
                            "a deadlock",
                            "DEADLOCK"
                        }),
                        @ValuePhrasing(value = "KERNEL_PANIC", phrasings = {
                            "a kernel panic",
                            "KERNEL_PANIC"
                        })
                    }),
            prompts = {
                @TrainingPrompt(prompt = "What do we do about a full disk?", arguments = "{\"cause\":\"DISK_FULL\"}"),
                @TrainingPrompt(prompt = "What do we do about OOM?", arguments = "{\"cause\":\"OOM\"}"),
                @TrainingPrompt(prompt = "How do we recover from a deadlock?", arguments = "{\"cause\":\"DEADLOCK\"}"),
                @TrainingPrompt(prompt = "Runbook for a kernel panic", arguments = "{\"cause\":\"KERNEL_PANIC\"}"),
                @TrainingPrompt(prompt = "What are the steps when a host runs out of memory?", arguments = "{\"cause\":\"OOM\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "box ran out of disk, what's the playbook?", arguments = "{\"cause\":\"DISK_FULL\"}"),
                @TrainingPrompt(prompt = "steps after a kernel panic?", arguments = "{\"cause\":\"KERNEL_PANIC\"}")
            },
            negatives = {
                "What causes a deadlock in databases?"
            })
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
    @Training(
            phrasings = {
                "{action} {service} on {host}, change {reference}",
                "Fix {host}: {action} {service} (ref {reference})",
                "On {host}, {action} the {service} service. Step id {reference}",
                "Please {action} {service} on {host}, reference {reference}"
            },
            negatives = "Should I restart or roll back after a bad deploy?",
            parameters = {
                @TrainingValues(name = "host",
                    examples = {
                        "app-02",
                        "app-01",
                        "db-01",
                        "web-03",
                        "cache-01"
                    }),
                @TrainingValues(name = "service",
                    examples = {
                        "checkout-api",
                        "nginx",
                        "postgres",
                        "worker",
                        "redis",
                        "payments"
                    }),
                @TrainingValues(name = "reference",
                    examples = {
                        "CHG-1201",
                        "INC-5001-step2",
                        "CHG-1207",
                        "fix-app02-01"
                    }),
                @TrainingValues(name = "action",
                    phrasings = {
                        @ValuePhrasing(value = "RESTART_SERVICE", phrasings = {
                            "restart",
                            "restart the service"
                        }),
                        @ValuePhrasing(value = "ROLLBACK_DEPLOY", phrasings = {
                            "roll back the last deploy of",
                            "rollback"
                        }),
                        @ValuePhrasing(value = "FAILOVER", phrasings = {
                            "fail over",
                            "failover"
                        }),
                        @ValuePhrasing(value = "CLEAR_TEMP", phrasings = {
                            "clear the temp files for",
                            "clean temp space for"
                        })
                    })
            },
            prompts = {
                @TrainingPrompt(prompt = "On web-03, clean temp space for the nginx service. Step id INC-5001-step2", arguments = "{\"action\":\"CLEAR_TEMP\",\"host\":\"web-03\",\"service\":\"nginx\",\"reference\":\"INC-5001-step2\"}"),
                @TrainingPrompt(prompt = "Fix app-02: clear temp files for worker (ref fix-app02-01)", arguments = "{\"action\":\"CLEAR_TEMP\",\"host\":\"app-02\",\"service\":\"worker\",\"reference\":\"fix-app02-01\"}"),
                @TrainingPrompt(prompt = "Restart checkout-api on app-02, ref fix-app02-02", arguments = "{\"action\":\"RESTART_SERVICE\",\"host\":\"app-02\",\"service\":\"checkout-api\",\"reference\":\"fix-app02-02\"}"),
                @TrainingPrompt(prompt = "Roll back the last deploy of payments on web-01, reference INC-5003-1", arguments = "{\"action\":\"ROLLBACK_DEPLOY\",\"host\":\"web-01\",\"service\":\"payments\",\"reference\":\"INC-5003-1\"}"),
                @TrainingPrompt(prompt = "Fail db-01 over to the standby for postgres, ref INC-5004-2", arguments = "{\"action\":\"FAILOVER\",\"host\":\"db-01\",\"service\":\"postgres\",\"reference\":\"INC-5004-2\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "bounce the api service on web-02, ref fix-77", arguments = "{\"action\":\"RESTART_SERVICE\",\"host\":\"web-02\",\"service\":\"api\",\"reference\":\"fix-77\"}"),
                @TrainingPrompt(prompt = "free up temp space on app-03 for the batch service (ref INC-6001-1)", arguments = "{\"action\":\"CLEAR_TEMP\",\"host\":\"app-03\",\"service\":\"batch\",\"reference\":\"INC-6001-1\"}")
            },
            negatives = {
                "What's the safest way to restart a production service?"
            })
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
    @Training(
            phrasings = {
                "Did remediation {remediationId} work?",
                "Verify {remediationId}",
                "Run the post-recovery checks for {remediationId}",
                "Is everything healthy after {remediationId}?"
            },
            negatives = "How do I verify a fix in production safely?",
            parameters = @TrainingValues(name = "remediationId",
                    examples = {
                        "REM-1a2b3c4d",
                        "REM-00ff12ab",
                        "REM-9c8d7e6f",
                        "REM-deadbeef",
                        "REM-4e5f6a7b"
                    }))
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "did remediation REM-3001 work?", arguments = "{\"remediationId\":\"REM-3001\"}")
            },
            negatives = {
                "How do I verify a backup?"
            })
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
    @Training(
            phrasings = {
                "Mark {incidentId} as {status}: {note} (update {reference})",
                "Set {incidentId} to {status} with note {note}, id {reference}",
                "Update incident {incidentId}: {status}. {note}. Ref {reference}",
                "{incidentId} is {status}. Add to the timeline: {note} ({reference})"
            },
            negatives = "How often should we update stakeholders during an incident?",
            parameters = {
                @TrainingValues(name = "incidentId",
                    examples = {
                        "INC-5001",
                        "INC-5002",
                        "INC-5010",
                        "INC-5013"
                    }),
                @TrainingValues(name = "note",
                    examples = {
                        "Restarted checkout-api, errors dropped",
                        "Rolled back release 1.4.2",
                        "Disk cleared on app-02",
                        "Still investigating the deadlock"
                    }),
                @TrainingValues(name = "reference",
                    examples = {
                        "UPD-01",
                        "upd-5001-3",
                        "UPD-17",
                        "status-0931"
                    }),
                @TrainingValues(name = "status",
                    phrasings = {
                        @ValuePhrasing(value = "INVESTIGATING", phrasings = {
                            "investigating",
                            "under investigation"
                        }),
                        @ValuePhrasing(value = "MITIGATED", phrasings = "mitigated"),
                        @ValuePhrasing(value = "RESOLVED", phrasings = {
                            "resolved",
                            "fixed"
                        })
                    })
            },
            prompts = {
                @TrainingPrompt(prompt = "Set INC-5002 to under investigation, note Rolled back release 1.4.2, ref upd-5001-3", arguments = "{\"incidentId\":\"INC-5002\",\"status\":\"INVESTIGATING\",\"note\":\"Rolled back release 1.4.2\",\"reference\":\"upd-5001-3\"}"),
                @TrainingPrompt(prompt = "Mark INC-5001 mitigated: Failover done, reference upd-5001-4", arguments = "{\"incidentId\":\"INC-5001\",\"status\":\"MITIGATED\",\"note\":\"Failover done\",\"reference\":\"upd-5001-4\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "close INC-5007 as resolved, note disk cleaned, ref upd-9", arguments = "{\"incidentId\":\"INC-5007\",\"status\":\"RESOLVED\",\"note\":\"disk cleaned\",\"reference\":\"upd-9\"}")
            },
            negatives = {
                "How do I write a good incident postmortem?"
            })
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
