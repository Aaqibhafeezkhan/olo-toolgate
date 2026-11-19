<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Import, publish, run and switch OLO bots

Start the [blank Quickstart](../../debug/profiled/README.md), then prepare
[models](models.md) and [a device](device.md). Build an example with `bot/build.bat`
or `bot/build.sh`; outputs go to `bot/bin`. Each `.olo` contains one bot workflow,
its referenced configuration and exact `.olotool` tool/trigger archives. No
README, account/password, private key, model weight or device identity is bundled.

In **OLO Orchestration > Bots**, import `.olo` and select that bot. Resolve every
item in its persistent red bullet list, which refreshes while selected. Use
lifecycle actions in order: stage the immutable revision, **Review exact
revision**, **Provision reviewed packages**, **Prepare reviewed graphs**,
**Publish reviewed revision**. Refresh lifecycle status after external setup.
A disabled Publish button means the reviewed revision is not ready; blockers
explain missing secrets, packages, device state or graph readiness.

Use a published bot's run symbol, enter its README's input and submit. The
dialog shows queued/running/completed state, recorded agent/model/tool/node
progress and the result or failure. Super Admin may initiate runs; current
runtime authority still governs every tool/model call. File triggers also run
when their watcher sees a new matching file. Check run observations/results and
the configured output files. Never resend an unknown write outcome blindly.

Distinct bot IDs can coexist. `hotswap-demo-a/b` intentionally share
`hotswap-demo`: publishing B replaces that bot's revision. It does not recreate
containers, change users or move A's files. Admitted runs remain pinned to A;
subsequent admissions use B. The other cases use distinct IDs/references.

Export a workflow to transfer its referenced data and exact packages. In a
fresh destination, set secrets, approve/assign its own device and prepare its
folders. Model/network/monitoring settings belong to that installation.
Factory reset clears backend bot/data, leaving the initial Admin ready to
import; it does not promise to erase operator-owned folders, external weights
or cloud telemetry.
