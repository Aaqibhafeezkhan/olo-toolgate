<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 074: Reconcile local launcher credentials against the running installation

Status: accepted for implementation.

The local operator already controls Docker and the stack's private custody.
On every start/restart, a read-only helper inside the owned Quickstart container
verifies the saved local administrator password against the current private
verifier. It returns that password only if valid, or the still-valid initial
password when the local copy is missing/stale. The launcher captures the result
privately and writes protected local files atomically. It never writes a new
server password, resets an account or exposes a credential through an HTTP API,
command argument or console output.

A new, still-initialized installation can refresh stale local initial-password
files and CA after explicit storage recreation. Without a valid initial password,
changed CA trust is refused. If a console password change made the saved local
copy invalid and the initial password was removed, the operator must supply the
current password in the private file; a verifier cannot recover a forgotten
password. Previous changed local files are retained in a protected history folder.

This is local installation tooling, not a new runtime grant, remote enrollment
authority, store adoption, schema migration or compatibility format. The helper
uses only the current Quickstart verifier. No profile can control it.
