# Unfinished work

This directory contains only work still requiring implementation or acceptance.
It is not an index of supported features. Current behavior belongs in the
[architecture](../architecture/README.md) and component/operator guides.

| Work | Remaining scope |
|---|---|
| [Execution security](zero-trust-tool-execution.md) | Revocation qualification, audit verification, stronger identity and effect authorization |
| [Workflows](workflows.md) | Complete provisioning/readiness, recovery and distributed end-to-end qualification |
| [Triggers](triggers.md) | Server/PUSH/provider execution, scheduling and complete safety/release acceptance |
| [Device notification](device-notifications.md) | Persistent authenticated sockets, reconnect and revocation qualification |
| [TypeScript SDK](sdk-typescript.md) | Proposed authoring API; implementation and conformance |
| [.NET SDK](sdk-dotnet.md) | Proposed authoring API; implementation and conformance |

Remove completed tasks instead of appending delivery diaries or retaining
superseded proposals. Each plan states remaining acceptance criteria; an available
API shape, local demo or successful build does not complete runtime qualification.
Track discussion and ownership in the linked GitHub issues. Record actual test
results in PRs/CI artifacts and generated evidence under ignored build output.
