# 008: Capability-scoped built-ins and anonymous client distribution

Status: superseded by [ADR 027](027-one-tool-protocol-device-files.md), 2026-10-17. The client built-ins, their
IPC catalog and the Quickstart executor described below were removed; HotFolder is the `examples.hotfolder`
package on the generic device files area. The client-distribution part (last paragraph) still applies.

Originally accepted 2026-10-03. Scope: Module 07.

The existing native OS service owns a configurable protected HotFolder and its
keys, independently of login sessions. Filesystem access uses directory
capabilities, no-follow component traversal, restricted portable relative names,
regular-file and hardlink checks, bounded contents and serialized operations.
The protected root and ancestry cannot be writable by ordinary callers. Root or
Administrator remains outside the threat model. No shell or plugin execution is
introduced; delete is absent.

A distinct version-2 local IPC protocol exposes a bounded catalog and fixed tool
calls. Kernel UID/SID authentication remains mandatory. Every execution requires
current enrolled readiness plus a fresh online Gateway decision. ASK permits
must be consumed at the Gateway; BLOCK, dependency failure or malformed evidence
never executes. Move/copy check both resource endpoints. No cached ALLOW exists.
Gateway origin, TLS roots and credential path are administrator-owned settings;
local callers cannot select them. Filesystem execution happens after validation
and authorization, with handles opened relative to the fixed root.

Built-ins use fixed parsers and explicit size/depth/result limits. Event delivery
is bounded and sanitized; it does not expose arbitrary host paths or file content.
Web search is absent from the enabled catalog unless an administrator configures
a fixed supported provider and protected credential. Search cannot select an
arbitrary URL. Network credentials never cross IPC.

Control's anonymous landing page offers Windows, macOS and Linux artifacts.
An external immutable artifact directory/CI-produced image layer supplies a
validated manifest, SHA-256 checksums and SBOMs. Only allowlisted manifest file
names are downloadable; a caller-controlled path never selects a server file.
Missing artifacts produce explicit unavailable states, never fabricated binaries.
Downloads do not authenticate a device or bypass normal browser enrollment.
Release signing remains protected CI with externally referenced keys. No new
runtime infrastructure, Quickstart service or general execution module is added.
