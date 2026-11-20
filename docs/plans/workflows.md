# Remaining workflow work

Status: unfinished work for [issue #295](https://github.com/olo-labs/olo-toolgate/issues/295).
Current [workflow architecture](../architecture/workflows.md) and
[runtime operation](../../apps/langgraph-runtime/README.md) describe shipped behavior.

## Complete provisioning and readiness

Close directory/grant/subscription provisioning, scoped management/user filtering
and whole-profile readiness. Import and publication must preserve shared references,
human accounts, installation secrets and immutable existing revisions. Qualify
candidate readiness independently from the active revision and make missing
dependencies actionable without silently granting access.

Finish durable server deployment reconciliation, reference-aware retirement of
old assignments and recovery/cleanup. Retire watchers only after their pinned
runs drain. Never destroy installation data as a side effect of bot import/switch.

## Advanced graph and conversation execution

Implement [external application workflow execution](workflow-application-integration.md):
ordinary users with explicit bot/resource grants, authorized result access,
resumable live events and durable signed webhook subscriptions. Current manual
run endpoints are Super Admin only; these integrations are not delivered.

Qualify durable native checkpoints and restart/replica resume, interrupt/approval
races, concurrent protected steps and replay behavior before enabling them.
Unknown effects must remain unknown without implicit resubmission. Untrusted
tenant Python publishing requires an independently reviewed isolation boundary.

External history, RAG and conversation/chat behavior require ownership, credential,
cross-tenant, duplicate-message and crash/ACK tests. Reloading a chat must not
duplicate messages or external effects. Planned schemas or node names do not
establish these capabilities.

## Distributed and security qualification

Execute successful message and trigger paths through the same governed run path,
with current disable/revocation/forgery denials and independent Control/target
evidence. Cover unrelated profiles and multiple agents/models/tools without
per-workflow runtime builds. Qualify cross-host operation and real provider
failures separately from deterministic fixtures and bounded local examples.

Run the [workflow qualification scenarios](../testing/workflow-v1-qualification.md),
applicable SQLite/PostgreSQL/component gates and full fresh-deployment tests.
Record the exact source and image identities with actual results. A compiler,
bundle build, structural import or collector trace is not execution finality.

## Release delivery

Keep the integration PR into main draft until end-to-end acceptance is complete.
Publish matching runtime, Quickstart and device artifacts through the reviewed
release pipeline. Registry metadata and cached local images do not replace
capability probes or a tested installation. Use current contracts/fresh stores;
do not introduce old-format conversion or migration support.
