<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 052: Managed containment crash qualification

**Status:** Accepted and delivered for the declared local Linux x86_64 OCI deployment profile; five actual owned deployment observations and the current-source OCI/interface gates pass.

## Decision

Runtime isolation does not prove supervisor-crash cleanup. Qualify the actual
systemd service boundary before advertising the OCI pool: the exact configured
ControlGroup, delegated controllers, KillMode=control-group, KillSignal=SIGKILL
and SendSIGKILL=yes. Supervisor-owned systemctl IPC supplies those properties;
worker stdout and self-reported container metadata cannot substitute for it.
A weak or unreadable deployment refuses readiness with no fallback.

Use immediate group kill for service termination, as the execution plan's forced
teardown model requires. A supervisor crash may leave durable admission/evidence
uncertain; startup reconciles its journals and never relaunches consumed work.
Systemd's cgroup kill must also stop an uncooperative worker that ignores TERM.

An engine command timeout or indeterminate command-I/O failure fences the pool.
Attempt both owned container removals, but retain the lifecycle journal and the
identity slot after uncertainty even if a later exists query reports absence.
A late engine completion cannot justify reusing an identity. Recovery requires
restarting the managed service, which kills its old descendant commands first.

## Evidence required

Small property/parser and fault-path tests, actual rootless command uncertainty,
and owned systemd deployment drills: both process memberships, supervisor crash,
uncooperative descendant termination, absence confirmation and journal recovery
without worker replay. Record monotonic observed startup/kill/recovery times and
the tested service properties, source/image/binary pins and platform.

Measured local test ceilings are not production connectivity-class revocation
SLOs, generalized signed kill delivery or a compromised-host guarantee. Full
original phase/release acceptance remains outstanding. S14 earns no weight until
its complete declared deployment qualification passes.
