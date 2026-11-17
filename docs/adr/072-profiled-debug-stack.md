<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 072: One persistent profiled debug stack

Status: accepted for implementation; local work is in progress under the
[hot-swap plan](../plans/workflow-profile-hot-swap.md).

Provisioning is a fresh current-contract flow. No legacy provisioning path,
compatibility readers/aliases, schema migrations, conversion or old-flow fallback
will be retained. Unsupported existing formats/stores are rejected and preserved;
current-schema retries, recovery and reconnect remain required.

The proposed `debug/profiled` stack replaces the standard and hot-folder launchers. A
stable Compose project owns one installation, device identity and credential
directory. Local images are inspected and reused; missing source images are
built in dependency order, and missing third-party images are pulled. A source
configuration seeds a fresh installation once. Subsequent profile changes go
through the existing Control console, review and publication APIs.

One persistent device receives tool/trigger packages and configuration through
the administrator console, Control and Gateway. Check-ins carry bounded lists
of identified trigger-instance statuses and pending event payloads, using current
leases, generations and durable acknowledgements. The debug listener follows
active subscriptions without recreating core/device containers.
The optional file-delivery adapter follows published workflow deployment data,
retains a journal per configuration and drains previous deliveries after a
switch. Runtime claims continue resolving immutable Control run snapshots;
events cannot choose their own workflow or credential. Shared trigger contracts
are revised together where needed; no separate management channel, legacy reader
or additional execution authority is introduced.

The existing privileged local fixture identity issuer remains an evaluation
facility with Docker access. Its custody and all generated credentials live in
an ignored private subfolder; tool containers never receive that folder.
Monitoring is configured manually in installation-owned files and private
exporter settings, outside workflow profiles.
Model hosting is outside core. Users/cloud operations supply provider services,
container settings, storage, mounts, permissions and network access. Workflows
declare connection types, external endpoints/secret references, per-agent model
policies and agent execution order. A protected runtime API builds candidate
graphs from reviewed JSON without publishing them or executing workflow effects.
Required state resides in external stores; disposable graph caches are invalidated
after authoritative database changes. Durable device outboxes remain required.
README commands describe operator prerequisites and their scope before execution.
Supported profile/tool/trigger updates do not restart core/device containers;
new executable adapters still require trusted operator installation.
