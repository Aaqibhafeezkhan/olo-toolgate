# Workflow execution architecture

OLO stores bot definitions in Control and executes them in the external
[LangGraph runtime](../../apps/langgraph-runtime/README.md). The console, manual
Run action and configured trigger bindings use Control's governed run path.

Manual application admissions capture the enabled human user's explicit workflow,
agent, model and full declared resource ceiling, or Control-derived live tenant
Super Admin entitlement. Control rechecks current human
authority at claim and protected steps alongside independent service/effect
authority. Session revocation invalidates the captured execution identity.
Ordinary owner-scoped observation uses current grants; live tenant Super Admins
can inspect any tenant run. Observation cannot resume revoked work.
Durable tenant-scoped structural events commit with run/trigger transitions.
SSE and leased HTTPS callbacks recheck source authority and never replay workflow
effects. See [external applications](../api/workflow-applications.md) and the
[all-bot permission matrix](../../bot/PERMISSIONS.md) for setup and transport limits.

## Configuration and ownership

A `.olo` bundle transfers one workflow and its referenced agents, models,
directory definitions and exact `.olotool` packages. It excludes human
credentials, private keys, model weights and installation state. See
[bot authoring](../help/author-bots.md) and [profile configuration](../deployment/profiles.md).

The operator owns images, ports, mounts, device permissions, model endpoints and
collector settings. Importing a workflow does not deploy infrastructure, grant
device access, clean folders or rotate credentials. First startup of the
[community installation](../../deploy/docker/quickstart/dev/README.md) is blank.

The optional startup bot selector is a separate operator installation action.
Its default is `0 None`; explicit selections authorize the listed local demo
device allocation, private folder creation and bounded native-file qualification.
The selector uses authenticated Control APIs for reference import, exact local
device enrollment review, membership, vault writes and readiness-gated publication.
It does not change tenant-wide device approval policy. Each selected bot delegates
to the same standalone quickstart configurator. Blank credential answers retain
installation custody; portable bundles and the selection catalog contain no secrets.
LangGraph service credentials remain issued by the existing runtime provisioning
flow, independently from administrator and optional cloud monitoring credentials.
The current tenant Super Admin can run all selected demos through its live
Control-derived human entitlement. Setup does not create copied human grants or
change management roles. Ordinary users continue to need their own reviewed
runtime grants; ordinary bundle import allocates no human runtime access.

Control validates, stages, reviews and publishes immutable workflow revisions.
Each admitted run pins its revision, graph selection and configured dependency
ceiling. Updating publication changes new admissions, not an existing run.
Model/provider secrets are resolved from installation custody; they do not enter
the portable bundle.

## Execution

1. An authenticated manual request or configured trigger binding selects the
   workflow. Untrusted event text cannot choose authority or another workflow.
2. Control performs current admission checks and stores the durable run identity.
   Worker claims and leases are scoped to that run.
3. The runtime loads an operator-installed immutable graph package or the
   configured JSON graph. Required native capabilities must be present before
   activation. Uploaded workflow content cannot install arbitrary Python code.
4. Governed agent/model/tool adapters use current run and step authority.
   Tool calls pass through Gateway and executor permit checks. Model output
   cannot grant permissions or replace a verified tool outcome.
5. Control records progress and terminal results. The console and external
   collectors display projections of that evidence; they do not authorize effects.

Agent stages can run in configured order. Context tools retain their own tool
authorization. Model fallback advances only for eligible provider failures;
authority/configuration failures stop the run. An inference retry is not a retry
of an uncertain tool effect.

## Availability and credentials

The runtime separates liveness from readiness, reconnects after unavailable
dependencies and rereads externally replaced service credentials. Control owns
issuance; a worker cannot mint its own replacement authority. The launcher
provisions installation credentials independently from human Admin sessions.
See [runtime operation](../../apps/langgraph-runtime/README.md) for file formats,
health endpoints and replacement procedures.

Reconnection does not establish general checkpoint/replay semantics or permission
to repeat an uncertain effect. Keep source execution observations separate from
Control-derived business finality and target-fencing evidence.

## Boundaries and limits

Native graphs are operator-installed trusted code. Current execution does not
establish universal untrusted-code isolation, exactly-once effects, durable native
checkpoint/interrupt resume, or full cross-host/HA qualification. Filesystem
effects require the specific [device file boundary](../client/hotfolder.md);
a native process is not an attested or contained OCI worker.

Use [run bots](../help/run-bots.md), [device setup](../help/device.md),
[model setup](../help/models.md) and [troubleshooting](../help/troubleshooting.md)
for operation. Remaining implementation and qualification work is in the
[workflow plan](../plans/workflows.md), with executable scenario guidance in
[workflow qualification](../testing/workflow-v1-qualification.md).
