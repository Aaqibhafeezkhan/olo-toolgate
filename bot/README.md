<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bots

`configurations/<name>/` contains the editable source needed to build each
profile: workflow/agent/model JSON, referenced directory definitions, logical
trigger connections and exact tool-package source recipes. Each folder has its
own README and, for a file use case, an input example. Secrets are never sources.

The supplied configurations now declare execution order in `workflow.graph.config`
using the installed `olo.json-graph` factory. Edit its nodes, edges and output
expressions to change the topology; agent definitions supply the governed model
and tool capabilities. The compiler supports value, agent, model, tool, branching,
static joins and bounded loops. Compilation does not provision devices or make a
bot runnable. Start with [community help](../docs/help/README.md), especially
[device updates](../docs/help/device.md) and [model updates](../docs/help/models.md).
Actual execution acceptance and remaining work are recorded in
the [live workflow status](../docs/architecture/workflow-v1-status.md).

From any directory on Windows:

```bat
D:\git\olo-toolgate\bot\build.bat
D:\git\olo-toolgate\bot\build.bat --configuration text-summary
```

On Linux/macOS:

```sh
./bot/build.sh
./bot/build.sh --configuration native-log
```

Python 3 and JDK 21 plus the pinned Rust toolchain build the package CLI and SDK.
If native Rust is absent, Docker builds and runs the supplied toolchain image.
Docker Desktop must be running for that path. The builder receives workspace
sources and a private container-local Gradle home. Each invocation downloads
its Gradle dependencies independently and uses single-use Gradle daemons.
The builder image and CLI build layers remain cached. SDK publication selects
the root SDK task with the application-plugin isolation guard.
Builds need network access for pinned tooling/dependencies.
Output is always in the ignored `bot/bin/` directory
unless `--output` is supplied. `--packages-dir` selects an existing collection
of exact verified `.olotool` tool archives instead of compiling recipes.

Package declarations use source `BUNDLE` and the exact digest of their embedded
`.olotool` bytes. The target verifies/stores these archives before provisioning;
they do not need to exist in its core image lock or Marketplace. Missing content
stays pending with no substitution. Package verification, release signing,
destination runtime support and independently administered permissions remain
required. Configuration sources remain unchanged by export/build packaging.

Each output `<configuration>-<workflow>-<version>.olo` is a ZIP containing:

- `manifest.json`: current format version, workflow ID, configuration hashes,
  exact package inventory, product version, source commit and build identifier;
- `workflow.json` and `referenced-config.json`;
- `packages/<package-digest>.olotool`: the exact required tool/trigger archives.

Tool archives use **`.olotool`**; workflow/profile archives use **`.olo`**.
The old tool extension is unsupported. No profile README is added. Embedded
package bytes, including any package-owned documentation/signatures, remain
unchanged. SHA-256 establishes integrity, not publisher trust or authorization.

`olo-profiles-<version>.json` lists output sizes, hashes, source commit and each
package's own version/digest. `SHA256SUMS` covers that inventory and its bundles.
Version defaults to the repository `VERSION`; `--version` accepts an exact SemVer.
Product/profile versions and tool package versions are independently recorded.
Long or platform-sensitive labels are shortened in filenames; the inventory
retains the complete original identifiers and version.

Import a bundle on **Bots → Import workflow .olo**. Importing its embedded
packages is part of that action: all exact archives must verify through Control
before the new workflow is staged. Apply referenced configuration under the existing
configuration policy, restore trigger mappings, review and publish. Upload does
not deploy packages or grant execution. Approved devices, native Python graph
factories, model services and vault secrets still need destination provisioning.
Imports preserve human credentials and do not restart containers.
Prepare reviewed graphs before publication; package installation alone is not
graph readiness. Direct JSON graph tool nodes are included in the exported
package/reference closure as well as configured agent context tools.

Foundation CI builds and retains these outputs. Development, tagged, manual
development and immutable product publication collect the tested bytes and
version inventory as release assets. Publication rechecks source/version,
configuration hashes and the full embedded package inventory rather than
rebuilding profiles during promotion.

The seven file examples use the real OLO QLoRA model first for file agents,
then lightweight Ollama on inference failure. Other agents use the lightweight
model; OpenRouter remains unattached standby. The three text-only examples use
lightweight Ollama. Each source README contains exact input and output commands.

For a multi-agent demonstration, build/import
[`multiagent-incident`](configurations/multiagent-incident/README.md). One agent
classifies an incident; a second receives that triage and the original incident
to produce a handover. It requires no device files or tool packages.
[Recorded local run and three-cloud traces](../docs/media/olo-2026-11/README.md).
