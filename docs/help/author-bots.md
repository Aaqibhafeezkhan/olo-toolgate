<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Author an OLO bot

Copy a folder under `bot/configurations/`. Its sources are:

| File | Purpose |
|---|---|
| `bot.json` | Graph, agent prompts, ordered model groups/deployments and packages |
| `referenced-config.json` | Agents/groups, grants, tools, bindings, extractors and policies |
| `trigger-connections.json` | Logical mappings; destination UIDs resolve during import |
| `package-build.json` | Exact tool/trigger version and source recipe |
| `README.md` | Setup, exact input and output; kept outside `.olo` |

Give unrelated bots unique workflow, agent, trigger and referenced resource IDs.
Share a bot ID only for intentional revision replacement. Never put secrets,
Admin accounts, real device membership, model weights, mounts or monitoring in
sources. Every graph tool and trigger needs its exact recipe and declaration.
Factories are installed trusted code; JSON is data, never an import/eval escape.
`olo.json-graph` supports value/agent/model/tool nodes, branches, static parallel
joins and bounded loops.

Expressions include `$path`, `$literal`, comparison/boolean/add operations and
`$json` (canonical JSON text for a confirmed result). A value node can construct
a messages list, whose `$path` supplies a model node. Model nodes return
decisions as data; agent nodes may execute permitted tool proposals through
OLO ToolGate. Prefer explicit file nodes for reviewed file flows. JSON-only
state and output schemas still apply. Obsolete formats are rejected.

```powershell
.\bot\build.bat --configuration text-summary
```

```sh
./bot/build.sh --configuration text-summary
```

The builder records source/product/build versions and embedded package hashes
in `bot/bin/olo-profiles-<version>.json` and `SHA256SUMS`. Tools use `.olotool`;
bots use `.olo`. Unused standby models survive packaging but confer no authority.
Test a fresh destination with real effects, denied access, trigger delivery and
revision switching. A schema-valid archive is not execution acceptance.

Continue with [devices](device.md), [models](models.md), [running](run-bots.md)
and the [SDK guide](../../TOOL-SDK-GUIDE.md).

Workflow version IDs include the configuration name and release version, so A/B
variants of one workflow do not alias an immutable version in the same build.
Choose a new `--version` after editing a previously imported configuration;
an existing immutable workflow version cannot be overwritten with different
content. Use the matching `--version` when running the local acceptance helper.
Keep shared ToolGate tool IDs in the same primary tool group; independently
owned bot model groups/deployments need distinct IDs. Agent `outputSchema` is
used for finish-result decoding and validation before downstream file nodes.
