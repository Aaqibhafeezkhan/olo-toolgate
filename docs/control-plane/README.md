# Control Plane

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited. Quickstart separately registers the fixed executor, HotFolder and REST forwarding.

See [Device registry and tool-call controls](device-registry.md).

Module 02 implements tenant-scoped users, teams, agents, tools, policies and device
records, versioned CRUD, append-only mutation audit and bounded JSON/YAML configuration
import/export. See [configuration](configuration.md), [API](../api/control-plane-api.md),
[deployment/upgrades](upgrades.md) and [ADR 003](../adr/003-control-plane-transactions.md).
The console [Configuration menu](configuration-menu.md) and the client check-in
settings in [configuration](configuration.md#client-check-in-specification-design-gate-d1)
are frozen D1 specifications, implemented in milestone M1.
The v2 [invocation state](invocation-state-v2.md) (tasks, execution ledger, business keys)
and the [emergency kill switch](kill-switch.md), with their
[migration specifications](migrations-v2/README.md), are D2 specifications.
The remaining ownership list below describes the wider architecture; approvals,
fleet deployment, vault bindings and Marketplace workflows are later modules.

The Control Plane is the organization administration service and serves the Admin UI.

## Owns

- users/teams/roles.
- tools/packages.
- policies.
- approvals.
- clients/device groups.
- desired state.
- vault bindings.
- Marketplace imports/publications.
- configuration import/export.
- audit search.

## Important

It must **not execute arbitrary custom tool code inside the Java process**. Testing runs on a designated enrolled test client or isolated sandbox.

Module 09 signed fleet lifecycle: [production usage, configuration and debugging](../client/package-deployment.md).
