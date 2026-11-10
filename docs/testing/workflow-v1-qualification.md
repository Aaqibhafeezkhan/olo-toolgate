<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Workflow v1 qualification

The qualification command executes the [published scenarios](../architecture/workflow-v1-acceptance.json)
through an explicitly supplied, trusted live-install driver. It records evidence;
it does not grant phase acceptance or prove that an operator's assertions are true.
The [live status](../architecture/workflow-v1-status.md) remains authoritative.

**The current implementation cannot complete this suite against ToolGate.**
Profile publication/readiness, run admission/leases/model transport and durable
trigger dispatch are unfinished. There is deliberately no built-in production
driver, substitute profile format or simulated success fallback. One-upload
example profiles and fresh-install orchestration must use the actual canonical
profile application and server APIs when those are implemented. Existing
DeploymentProfile v1 does not support this use case.

## Run outside containers

From a clean checkout of the integration revision, using Python 3.11 or later:

```sh
python3 tools/workflows/check.py
python3 tools/workflows/qualify.py --output /private/new-workflow-evidence
```

The second command currently exits **2**, prints `BLOCKED`, and writes a
`report.json` containing all 16 blocked scenarios. It never creates a server
identity, grant, profile, effect, admission or local substitute ledger. The output
directory must not already exist, preventing accidental mixing of evidence.
On POSIX it is created with owner-only directory permissions. Operators must
also restrict access to parent directories and apply equivalent ACLs elsewhere.

When real server APIs exist, supply a reviewed driver:

```sh
python3 tools/workflows/qualify.py \
  --driver /private/live_install_driver.py \
  --output /private/new-workflow-evidence \
  --scenario-timeout 300
```

Drivers are executable trusted operator code, **never uploaded profile code**.
The driver must export `run_scenario(scenario, evidence_directory)`. Each scenario
runs in a separate Python process, with no inherited stdin and suppressed output
to prevent accidental credential disclosure in the controller log. Bootstrap
credentials/endpoints may come from the operator environment. Never pass literal
tokens in command arguments. There is no automatic effect retry.

The function performs the scenario against a disposable installation and returns:

```python
{
    "checks": {assertion_name: observed_boolean for assertion_name in scenario["then"]},
    "artifacts": ["redacted-server-evidence.json"],
}
```

Every assertion must have an actual boolean observation; missing, extra or
nonboolean assertions fail. Evidence paths are relative to that scenario's
directory, must exist, cannot escape it or traverse symlinks, and cannot repeat.
The controller rehashes artifacts after the driver exits. Do not return `True`
for an unavailable prerequisite, skipped operation, mock response or unobserved
effect. A false assertion is `FAILED`; an exception, invalid evidence or deadline
is `ERROR`. A worker timeout does not cancel already submitted remote work or
terminate descendants it launched. The driver must bound network operations,
clean up child processes and reconcile uncertain effects. Never blindly rerun a
timed-out mutation or rerun the suite against an installation with uncertain
effects. Each scenario must arrange isolated fixtures and verified cleanup;
process isolation alone does not isolate server data.

## Evidence and review

`report.json` pins the scenario specification, every Python runtime source file,
runtime package metadata, dependency lock and supplied driver using SHA-256.
Source changes during the suite invalidate the report. Artifacts are individually
hashed. This records local source consistency, not deployed-service attestation,
dependency-install integrity, artifact authenticity or cross-platform support.
Capture those separately using trusted deployment evidence. Drivers' imported
modules and environment are not automatically captured by the harness.

The live driver and reviewer must retain redacted, attributable evidence of:

- Fresh supported database engine/schema, exact Control/Gateway/tool package
  revisions, infrastructure readiness and installed dependency versions.
- One upload/review/publish per use case, immutable profile digests, closed
  references and READY, including denied missing/foreign dependency cases.
- Two unrelated profiles using identical host runtime source and lock hashes,
  with text and trigger paths, configured supervisor and two agent models,
  distinct per-agent tools, governed context and actual final outcomes.
- Authoritative run/admission/effect references; matching durable receipts;
  current-grant denials; subscription disable/recreation and forgery denials;
  PREPARE/ASK nonmutation; redelivery and uncertain-effect reconciliation.
- Applicable component/security gate commands and results at the tested source
  revisions, including unresolved failures. A local runner unit test is not
  evidence of server enforcement or model/tool integration.

Never store bearer tokens, provider keys, vault contents, private prompts,
unredacted event payloads or personal data in artifacts. The harness does not
redact arbitrary driver files or scan them for secrets. Keep evidence private
and review redaction before sharing; publish hashes and approved summaries.

Exit **0** means every driver's assertion was true and its artifacts were
recorded: `EVIDENCE_RECORDED_REVIEW_REQUIRED`. It is **not** V1.4 acceptance.
Exit **2** means blocked, failed, invalid, changed-source or setup error. No
report changes the progress percentage automatically. Review server evidence,
actual effect outcomes and all phase exit criteria before updating acceptance.
The umbrella PR stays draft until full V1.4 acceptance.

## Harness verification

`python3 tools/workflows/check.py` runs the real LangGraph/listener tests and the
qualification-controller tests. Controller tests intentionally use temporary
simulated drivers to test evidence handling, failure visibility and deadlines;
their reports are not integration qualification artifacts. Host execution does
not provide arbitrary egress confinement; controlled container qualification is
later scope.
