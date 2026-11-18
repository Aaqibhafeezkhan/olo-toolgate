<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Hot-swap demo B

This workflow reads a text file using the device file agent, calls the separate
summarization agent through an externally supplied OpenAI-compatible connection,
writes the summary, then moves the processed input. JSON files in this directory
are build inputs; the portable `.olo` archive embeds its exact tool and
`hotfolder-watch` packages. No accounts, device memberships, model service,
monitoring settings or container configuration are imported.

Import into a blank profiled installation using the Admin workflow APIs. The
operator must first approve and enable the real device, allocate
`ReadAndWriteDeviceGroup`, `langchain-linux-devices` and `default-devices`, and
provide the `demo-provider-key` vault secret and trusted external model endpoint.
`tools/admin/provision_devices.py` configures approval and group allocation.
Folder permissions and container mounts remain operator responsibilities.

Place `source.txt` in `inbox-b` below the device's approved file root.
Expected results are `summaries/summary-b.txt` and
`processed/source-b.txt`. The local container root is
`/var/lib/olo-toolgate/files`. Check the workflow run and invocation business
outcome in the Admin console as well as those files.

The A and B configurations share a profile ID and differ in trigger folder,
model ID and result paths. Build them with distinct versions:

```powershell
python bot/build.py --configuration hotswap-demo-b --version 1.0.0-demo.b
```

Authoring files: `bot.json` contains graph/agents/model configuration; `package-build.json` selects embedded tool/trigger packages; `referenced-config.json` contains referenced directory policy; `trigger-connections.json` wires assignments/subscriptions. Switching does not create, move, delete or seed device files. Create folders and drop input explicitly using `debug/profiled/README.md`.
