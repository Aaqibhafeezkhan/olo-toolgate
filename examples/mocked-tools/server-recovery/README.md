<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Server crash recovery (mocked)

Tools: `incidents.incidents`, `incidents.host-status`, `incidents.crash-report`, `incidents.runbook`, `incidents.remediate`, `incidents.verify`, `incidents.update-incident`. Part of the [mocked tool examples](../README.md).

An on-call assistant for a crashed server, end to end. `incidents.incidents` lists open incidents. `incidents.host-status` shows a host's services, CPU, memory, disk and recent deploys. `incidents.crash-report` gives the crash analysis: the log excerpt, the probable cause and the evidence for it. `incidents.runbook` returns the steps for that cause. `incidents.remediate` runs RESTART_SERVICE, ROLLBACK_DEPLOY, FAILOVER or CLEAR_TEMP (once per `reference`, so a retry never restarts twice). `incidents.verify` checks the service is healthy again, and `incidents.update-incident` records the outcome.

Scenario: payments-api on app-02 was killed by the out-of-memory killer after release 2.14.0 (INC-5001, P1); db-01's data disk is filling (INC-5002, P2); web-03 is healthy.

`remediate` is where a real integration would call your orchestration (Ansible, Rundeck, Kubernetes, a cloud API). Under ToolGate, require approval for it and allow it only for the on-call group.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/server-recovery-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "What's on fire right now?"
- "Why did payments-api crash on app-02, and what does the runbook say?"
- "Roll back app-02 to the last good release, check it's healthy, and resolve INC-5001."
