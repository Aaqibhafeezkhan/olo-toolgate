<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bots

## Quick demo setup

From the repository root, run `bot\start.bat` on Windows or `sh bot/start.sh` on
Linux/macOS. This starts the published community installation and offers all ten
bots from [AutoConfigure.yaml](AutoConfigure.yaml). The menu defaults to **0 None**.
Enter **2,3** to install and configure both Ollama summary and multi-agent incident;
when setup reports that all selected bots are ready, sign in, open **Bots** and
use **Run**. There is no separate import, model-key, device-group or publish step
for a successfully configured demo. File/log bots wait for their watchers too;
their guides below explain how to supply sample input.

Setup prepares local models automatically for selected standard demos and asks
for optional OpenRouter and cloud tracing credentials. Empty answers keep saved
values. The local LangGraph runtime receives its service token from Control;
it needs no cloud LangGraph API key. See the
[quick-start prerequisites](../README.md#try-the-standard-demo-bots) and
[selection/credential details](../deploy/docker/quickstart/dev/README.md#choose-demo-bots).

Each bot also has `quickstart/configure.bat` and `quickstart/configure.sh`.
For example, from the repository root:

```powershell
.\bot\configurations\log-triage\quickstart\configure.bat
```

```sh
sh bot/configurations/log-triage/quickstart/configure.sh
```

These entrypoints start/reuse the managed stack, configure that bot and stop on
unresolved readiness. An external operator may pass `--origin`, `--token-file`
and `--ca-file`; the token is read from its private file, never passed as a
command-line secret. `--bundles-dir` selects an already downloaded inventory and
its verified bundles. Without it, setup finds the release matching all three
running application images. The catalog uses JSON flow syntax (valid YAML 1.2)
so startup needs no host package installation. It lists bot IDs, labels and
local folder prerequisites only; never put credentials there.

The detailed manual walkthroughs below remain available for custom imports,
provider changes and learning each configuration.

## First-time users: choose a bot

No programming knowledge is needed to follow the Windows walkthroughs below.
A bot is a saved set of instructions for an AI assistant. Download its released
`.olo` file; import adds it to OLO; publish makes the reviewed version active.
You do not need to compile OLO, tools or bots to explore these examples.
Use sample data first and review AI-generated answers before relying on them.

| Bot | Who it helps and what it does |
|---|---|
| [interactive-summary](configurations/interactive-summary/README.md#beginner-walkthrough-no-programming-experience-needed) | Anyone wanting to paste a paragraph and get a short summary on screen. Start here for a first experiment. |
| [ollama-summary](configurations/ollama-summary/README.md#beginner-walkthrough-no-programming-experience-needed) | A similar paste-and-summarize example using the local Ollama model service; no files or watchers. |
| [multiagent-incident](configurations/multiagent-incident/README.md#beginner-walkthrough-no-programming-experience-needed) | Operations teams: one AI assistant assesses an incident and another writes a handover. It does not repair the incident. |
| [text-summary](configurations/text-summary/README.md#beginner-walkthrough-no-programming-experience-needed) | People processing text documents: watches an inbox, summarizes each new sample file, saves the result and archives the original. |
| [issue-triage](configurations/issue-triage/README.md#beginner-walkthrough-no-programming-experience-needed) | Support/operations teams: turns a new issue text file into an issue ID, summary, suggested priority and investigation action. |
| [log-triage](configurations/log-triage/README.md#beginner-walkthrough-no-programming-experience-needed) | Operations teams: watches an application log for a burst of errors and writes a triage result. It does not fix the service. |
| [native-hotfolder](configurations/native-hotfolder/README.md#beginner-walkthrough-no-programming-experience-needed) | A device-file demonstration: watches a folder, summarizes a text file, writes a result and archives the source. |
| [native-log](configurations/native-log/README.md#beginner-walkthrough-no-programming-experience-needed) | A device-log demonstration: reacts to two ERROR/FATAL lines in a minute and writes a result while leaving the log in place. |
| [hotswap-demo-a](configurations/hotswap-demo-a/README.md#beginner-walkthrough-no-programming-experience-needed) | Demonstrates version A of a file-summary bot using inbox A and output A. |
| [hotswap-demo-b](configurations/hotswap-demo-b/README.md#beginner-walkthrough-no-programming-experience-needed) | Demonstrates switching that same bot to version B with inbox B and output B. A and B replace one another. |

Each linked README has a separate beginner section with the bot's purpose,
release asset to download, device groups when needed, exact sample and expected result.
The common steps below are maintained once for all ten bots. Complete setup
once, then return to your chosen bot's beginner section.
After your first successful run, follow
[Adapt a bot to your use case with monitoring](../docs/help/tune-bots.md)
to configure tracing, adjust prompts and compare results on your own examples.

## Beginner setup: install, deploy and sign in

This walkthrough uses **Windows PowerShell** and the **released development
containers**, launched from `D:\git\olo-toolgate\deploy\docker\quickstart\dev`.
A stack is the collection of programs OLO runs together in Docker.
It runs on your computer; this is a local evaluation setup. Linux/macOS users
should use the [platform startup instructions](../deploy/docker/quickstart/dev/README.md#start-on-windows)
and the same release downloads. Do not paste PowerShell commands into Bash.

### 1. Install Docker, Git and optional Git Extensions

1. Download [Docker Desktop](https://www.docker.com/products/docker-desktop/).
   Choose the installer matching your Windows computer, run it and follow its
   setup prompts. Follow Docker's [Windows installation instructions](https://docs.docker.com/desktop/setup/install/windows-install/)
   if it asks for WSL 2, virtualization or a restart. Open Docker Desktop from
   Start and wait for its engine to finish starting. OLO needs **Linux containers**.
2. Download [Git](https://git-scm.com/downloads), choose Windows and install it.
   Git downloads this project's files. Enable command-line access in the
   installer so PowerShell can use `git`; reopen PowerShell after installation.
3. If you prefer a visual Git application, download
   [Git Extensions](https://gitextensions.github.io/) using its **Download** link
   and follow its installer. It is optional; the commands below use Git directly.
   It is separate from Docker extensions and browser extensions.
4. Allow several GB of downloads/disk and up to 8 GB of RAM for first model
   preparation. The local Ollama launcher checks Docker NVIDIA GPU access and
   uses it when available; otherwise it runs on CPU, which can be slower.
   No host model installation is needed. Starting without Ollama requires an
   already configured [different model provider](../docs/help/models.md);
   omitting the service alone does not supply AI.

### 2. Download OLO and check Docker

1. Open **Start**, type **PowerShell** and open **Windows PowerShell**.
   Commands are instructions pasted into that window. Paste one line, press
   **Enter**, and wait for it to finish before pasting the next. Copy the contents
   of code boxes only, without the surrounding backticks.
2. Paste these lines. The first two should print version information, not an error:

   ```powershell
   git --version
   docker compose version
   docker info
   ```

   `docker info` must reach a Linux Docker engine. If it cannot connect, open
   Docker Desktop and wait; resolve that error before continuing.
3. Download a working copy. The example location is `D:\git\olo-toolgate`.
   If your computer has no D: drive, substitute `C:\git` in these commands and
   in later instructions that reopen the folder:

   ```powershell
   New-Item -ItemType Directory -Force D:\git | Out-Null
   Set-Location D:\git
   git clone https://github.com/olo-labs/olo-toolgate.git
   Set-Location .\olo-toolgate
   ```

   If you already cloned it there, skip `git clone` and open the existing folder.
   This folder is the **repository root**: it contains `bot`, `deploy` and
   `README.md`. All remaining PowerShell boxes assume you are here.

### Choose one development release and download your bot

1. Open [OLO releases](https://github.com/olo-labs/olo-toolgate/releases) in your
   browser. Choose a **development prerelease** for the intended source branch.
   Open that release's own page. Do not use `/releases/latest`, which can select
   a different channel. Read its version and source branch/commit information.
2. Expand **Assets** at the bottom; click **Show all assets** if offered. Look
   for `olo-profiles-<version>.json` and your bot's `.olo`. Older releases may
   predate these bots or the required containers: choose a release that actually
   includes the inventory, desired bundle and all three required application
   images. A download page or image tag alone does not prove runtime readiness.
3. Download the `olo-profiles-<version>.json` inventory and open it with Notepad
   (right-click the downloaded file, choose **Open with > Notepad**). Find your
   bot's `configuration` name; its `filename` is the exact asset to download.
   Both hotswap configurations must be listed separately, even though their
   `workflowId` is the same. Download that exact `.olo` from **Assets** and keep
   it in **Downloads**. Do not unzip it or choose GitHub's **Source code (zip)**.
4. Download `SHA256SUMS` from that same release. To check the bundle, run the
   following in PowerShell, paste its full downloaded path when prompted, then
   compare the displayed hash to that filename's line in `SHA256SUMS` (case does
   not matter). Stop if they differ:

   ```powershell
   $botDownload = Read-Host 'Full path to the downloaded .olo file (without quotes)'
   Get-FileHash -Algorithm SHA256 -LiteralPath $botDownload
   ```

   If your browser added `(1)` to a repeated download, compare against the
   original release filename. Checksums detect changed bytes; they do not
   establish publisher trust or grant execution permission.
5. Note **two** values from the selected release: its Git tag and the artifact
   version used by its inventory/images. They can differ for development
   publication; copy the values shown rather than guessing from the title.
   In the fresh clone from step 2, select that release's launcher files:

   ```powershell
   $releaseTag = Read-Host 'Paste the Git tag shown on the selected release'
   git fetch --tags
   git checkout --detach $releaseTag
   ```

   A detached checkout is expected: you are exploring a release, not editing
   code. If Git refuses, stop and read the error; do not discard existing work.
   Use a fresh separate clone when an existing checkout contains your edits.
   Keep the release page and downloaded bundle for the following steps.

### 3. Deploy the development stack

1. Use **`deploy/docker/quickstart/dev`**, which pulls published containers;
   this is the exploration path, separate from the source-built debug stack.
   In PowerShell at the repository root, enter:

   ```powershell
   Set-Location .\deploy\docker\quickstart\dev
   ```

   You are now in `D:\git\olo-toolgate\deploy\docker\quickstart\dev` if you used
   the example clone location. Before first startup, create the image settings
   file from the template (skip the copy if `.env` already exists) and open it:

   ```powershell
   if (-not (Test-Path .\.env)) { Copy-Item .\.env.example .\.env }
   notepad .\.env
   ```

   In Notepad, change the three image lines to use the **same artifact version**
   recorded by your selected development release, then save and close Notepad.
   Replace `RELEASE_VERSION` below with that actual version, without adding `v`:

   ```text
   QUICKSTART_IMAGE=ololab/olo-toolgate-quickstart:RELEASE_VERSION
   LANGGRAPH_IMAGE=ololab/olo-toolgate-langgraph-runtime:RELEASE_VERSION
   DEVICE_IMAGE=ololab/olo-toolgate-device:RELEASE_VERSION
   ```

   Use the image registry/repository or immutable digests actually recorded in
   the release metadata if they differ. Preserve the existing port settings.
   This pins the containers to the same release as your downloaded bot; the
   template's moving `:dev` aliases may have advanced to a different publication.
2. Keep Docker Desktop open. For the NVIDIA-supported local-model path, paste:

   ```powershell
   .\start.bat -WithOllama -WithoutMonitoring
   ```

   This downloads the application images, creates private local storage and
   credentials, starts OLO, and prepares the local AI models. First startup and
   model downloads can take time; leave the window open. Monitoring is optional
   and is omitted for this first run. You do not need a host Java/Node/Rust
   installation to start published images. The launcher obtains private Python
   if a usable Python 3.11+ is unavailable.
3. Wait for successful completion. If the command stops with an error, use the
   troubleshooting table below. In particular, missing/incompatible published
   images must be resolved with a matching publication; the launcher does not
   build an application as a fallback. The
   [source-built stack](../debug/profiled/README.md) is a separate developer path.
4. Return to the repository root, select this installation for every later file
   command, initialize the launcher's private Python helper and check services:

   ```powershell
   Set-Location ..\..\..\..
   . .\debug\profiled\python.ps1
   $debugPython = Find-DebugPython
   $compose = @('compose','-p','olo-quickstart-dev','-f','deploy/docker/quickstart/dev/state/compose.json')
   docker @compose ps
   ```

   You should see the core `quickstart`, `langgraph` and `device` services.
   Container health alone does not mean your bot is ready. Do not start the
   separate `debug/profiled` stack at the same time on these default ports.

### 4. Find your credentials and log in

1. In the same PowerShell window, paste:

   ```powershell
   Get-Content .\deploy\docker\quickstart\dev\credentials\admin\username.txt
   Get-Content .\deploy\docker\quickstart\dev\credentials\admin\password.txt
   ```

   The first output is your username; the second is your generated password.
   They are also text files you can open in File Explorer. There is no shared
   default password. Keep these private; do not include them in screenshots.
2. Open a browser, put [the local OLO console](http://127.0.0.1:18090/console/)
   in the address bar and press Enter. This address opens OLO on this computer.
3. Enter the file's username in **Username** and password in **Password**.
   Click **Connect to workspace**. A new installation has no bots yet.
4. Leave this browser tab open. Return to your bot's **Beginner walkthrough**
   to select the downloaded release bot and import it. If you reopen PowerShell
   later, first run (use your actual clone location):

   ```powershell
   Set-Location D:\git\olo-toolgate
   . .\debug\profiled\python.ps1
   $debugPython = Find-DebugPython
   $compose = @('compose','-p','olo-quickstart-dev','-f','deploy/docker/quickstart/dev/state/compose.json')
   ```

## Beginner import and activation: buttons in order

Use this sequence when your chosen bot's walkthrough tells you to import.

1. In the console's left menu, expand **Bots** under **OLO Orchestration**
   and select **Bots**. Scroll to **Bot import / export (.olo)**, then
   **Import or replace a bot**.
2. Under **Bot .olo file**, click the browser's file chooser. Open **Downloads**
   (or your chosen download folder). Select the released `.olo` named in your
   bot's walkthrough/inventory and click **Open**. Upload the `.olo`, not its JSON inventory,
   `SHA256SUMS`, source JSON or a separate `.olotool` archive.
3. Leave **Import destination** on **Add, or replace the matching bot ID** for
   a first import. Click **Import bot .olo** once and wait for its message.
   It verifies/imports embedded tools and referenced settings and attempts
   preparation. A pending device/model message means setup remains to be done.
   If a bot-owner field asks for an existing user, use the intended enabled
   destination user (`admin` for this local evaluation).
4. After import has created the referenced groups, return to PowerShell and run:

   ```powershell
   & $debugPython deploy/docker/quickstart/dev/manage.py ollama-vault
   ```

   This stores this installation's local model credential using the saved Admin
   login. It does not change the login password. Do not substitute the older
   `debug/profiled/ollama.py vault` command for this installation.
5. **File/log bot:** complete your README's device and folder steps before
   publication. **Text-only bot:** skip device setup entirely.
6. Return to **Bots**. If **Resume bot preparation** is visible, click it after
   completing setup. Select your imported revision using its **Open …** button
   in **Available bot revisions**. Read its red readiness bullets; fix each
   reported problem using the table below.
7. In **Bot lifecycle**, click **Refresh lifecycle status**. Import may already
   have staged/reviewed/prepared the bot. If you edited the working bot, click
   **Stage bot revision** first. If its state is `DRAFT`, click **Review exact
   revision**. For a reviewed bot, click **Provision reviewed packages**, wait,
   then **Prepare reviewed graphs**, wait, then **Refresh lifecycle status**.
8. When dependencies are ready and state is `REVIEWED`, click **Publish reviewed
   revision**. Confirm it becomes `PUBLISHED`. Publication is how this bot version
   becomes active; there is no additional universal “Enable bot” button.
   Imported tools/groups have their own enabled status and current grants.
   If a tool/group is disabled, inspect that exact record in **Tools** or
   **Devices > Device groups** and enable only the intended record using its
   status switch. Approval or publication does not replace execution permission.
9. Return to the bot's walkthrough for its sample run and expected result.

## Beginner device setup for file and log bots

Skip this entire section for interactive-summary, ollama-summary and
multiagent-incident. A device here is the local Docker worker that accesses
the demonstration files; you do not need to enroll another laptop.

1. After import, read this stack's device identity in PowerShell:

   ```powershell
   docker @compose exec -T device cat /run/olo-toolgate/device-status.json
   ```

   Note its `deviceId`, `keyFingerprint` and, if pending, `userCode`.
2. In **OLO ToolGate > Devices > Devices**, locate that exact device. Hover
   over action icons to read their labels. Click **Approve …** if approval is
   required. Compare the displayed fingerprint/code with the output above.
   Choose the intended approval duration, select the confirmation checkbox only
   after the values match, and click **Approve device**. Expired codes need a
   fresh enrollment request; never approve an unrelated device.
3. In its **Directory status** column, click the **Disabled** switch if needed
   so it reads **Enabled**. Approval and enablement are separate steps.
4. Click the device's **Groups for …** action. Click **Choose device groups**,
   search for each of the two exact IDs in your bot's walkthrough and tick them.
   Keep existing intended groups selected. Click **Apply selection**, tick
   **I reviewed the membership changes**, then **Save group membership**.
   If the policy requests independent review, complete that review through
   **Audit > Configuration reviews** with an authorized independent reviewer;
   a pending change has not applied. Click **Publish access changes** on the
   Devices page and confirm the device's group list after saving/review.
5. Prepare folders using your bot's commands. In this fresh Docker stack, the
   device launcher already supplies root `/var/lib/olo-toolgate/files` and
   extensions `txt`, `md`, `json`, `csv`, `log`. **Administration > Device**
   manages approval/check-in, not an editable file-root form. Custom installations
   need their operator's [device file configuration](../docs/client/hotfolder.md).
   The sample files are inside Docker storage, not a Windows Downloads folder.
6. Return to **Bots**, resume preparation if offered, review the revision if it
   is still `DRAFT`, and click **Provision reviewed packages**. Wait for the
   packages to install before the following qualification step.

### Explicit native-file qualification

The supplied file examples require an installation decision for their native
file tools. Native packages are trusted code; this acknowledgement does not
provide physical fencing or exactly-once file writes. Read the
[device requirements](../docs/help/device.md) and
[execution boundaries](../docs/security/zero-trust-boundaries.md#runtime-and-target-scope).
Use disposable demonstration files. If you cannot assess the owner/confinement
requirements, have your operator complete this step before running a file bot.

After checking those requirements, paste your bot's `$nativeDeviceGroup` and
`$nativeTools` box first, then this box in the same root PowerShell window:

```powershell
$deviceStatus = docker @compose exec -T device cat /run/olo-toolgate/device-status.json | ConvertFrom-Json
$nativeDeviceId = $deviceStatus.deviceId
@'
import sys
from pathlib import Path
sys.path.insert(0, str(Path('deploy/docker/quickstart/dev').resolve()))
import manage
manage.configure(manage.settings())
import stack
from tools.admin.qualify_native_files import qualify
print(qualify(stack.operator_client(), sys.argv[1], sys.argv[2], sys.argv[3:]))
'@ | & $debugPython - $nativeDeviceId $nativeDeviceGroup @nativeTools
```

This explicitly accepts unfenced filesystem effects for the selected installed
tools/device/group for **one day**. It uses the saved local login and writes an
operator token in the ignored credential folder; do not share that folder.
It does not grant the bot permission. Stop on refusal; do not change IDs or
weaken checks to make it pass. Expiry or a different installed image requires
another reviewed qualification. Return to your bot's publication step.

## Beginner troubleshooting and next use

| What you see | What to do next |
|---|---|
| `git` or `docker` is not recognized | Finish installing Git/Docker, close PowerShell, reopen it and repeat the version checks. |
| Docker engine/GPU error | Start Docker Desktop's Linux engine. Resolve driver/GPU support before local-model startup; use an operator-configured provider if needed. |
| Script execution is blocked | Use the launcher's `.bat` entrypoint. If company policy also blocks the later PowerShell helper, ask your administrator to permit the reviewed scripts; do not disable company policy. |
| Image not found/capability validation failed | Check the exact launcher error and matching published image selection in its runbook. A tag is not proof the required runtime was published. |
| Browser cannot connect | Check successful startup and `docker @compose ps`. Use the default console address only if you kept the default ports. Stop another stack occupying them. |
| Credentials missing/login refused | Check this stack's credential folder and successful initialization. Retain the actual saved password after changing it; do not reset storage to obtain a login. |
| Missing `ollama-local-key` or model failure | Run this stack's `ollama-vault` command after import; check successful model preload and configured endpoint. An unattached premium model is not an automatic fallback. |
| No device/package/graph readiness | Check the exact device's approval, enablement, both groups, installed packages and native qualification. Resume preparation, prepare graphs and refresh lifecycle status. |
| Permission denied or pending review | Read the named grant/scope or review blocker. Use the [bot permissions matrix](PERMISSIONS.md) with your operator. Admin rights alone do not grant runtime access. |
| Publish is disabled | Check the selected revision, `REVIEWED` state and every readiness bullet. Fix the cause, then refresh status. |
| Watcher does not start | Open **Devices > Triggers** and inspect the imported assignment named in your bot README. Wait for running instances with no failed instances before adding sample data. Do not create a second assignment. |
| Run progress disconnects or an effect is unknown | Use **Resume progress** or **Check same submission** where offered. Inspect recorded results and files; do not submit another write just because the response was lost. |

To pause this evaluation, run from the repository root:

```powershell
.\deploy\docker\quickstart\dev\stop.bat -WithOllama -WithoutMonitoring
```

Start again with the same startup command. Data and credentials are preserved.
Do not use volume deletion or Factory reset for a normal restart. File demos
use fixed output/archive paths: inspect prior artifacts before repeating and
choose new paths deliberately as described in the bot's technical instructions.
For more errors, see [troubleshooting](../docs/help/troubleshooting.md).

## Download a standalone tool or trigger

Every development/production release includes standalone `.olotool` assets for
the configured core packages, Java tool/mock examples and the exact packages
embedded in its bots. Open that release's **Assets**, download
`olo-tools-<release-version>.json`, and find the desired `packageId` and package
`version`. Download the exact `filename` in that entry and verify it against
the same release's `SHA256SUMS`. The package's own version can differ from the
product version; the inventory associates the exact bytes with this release.

For a normal bot walkthrough, upload only the `.olo`: its required packages are
already embedded. For a standalone tool, open **OLO ToolGate > Tools > Server
packages**, find **Upload a package**, choose **Package file (.olotool)** and
click **Upload and verify**. Verification is not deployment or execution
permission. Follow the package's declared execution kind and the
[device/package guide](../docs/help/device.md) for the correct host/device,
review, enablement, group bindings, grants and any native-file qualification.
Do not deploy a client trigger as a server tool or upload `.olotool` in the
bot's `.olo` file chooser. See the [tool runtime guide](../docs/client/local-runtimes.md)
for device execution and [console guide](../apps/admin-ui/README.md).

## Builder and technical reference

The beginner path downloads released `.olo` bundles and uses published
development containers. The build instructions below are optional for authors
who edit source configurations or create custom tools.

`configurations/<name>/` contains the editable source needed to build each
profile: workflow/agent/model JSON, referenced directory definitions, logical
trigger connections and exact tool-package source recipes. Each folder has its
own README and, for a file use case, an input example. Secrets are never sources.

Use the [bot user, permission and resource matrix](PERMISSIONS.md) to see which
human/runtime grants, models, tools, Device Groups and paths each bot requires.

The supplied configurations now declare execution order in `workflow.graph.config`
using the installed `olo.json-graph` factory. Edit its nodes, edges and output
expressions to change the topology; agent definitions supply the governed model
and tool capabilities. The compiler supports value, agent, model, tool, branching,
static joins and bounded loops. Compilation does not provision devices or make a
bot runnable. Start with [community help](../docs/help/README.md), especially
[device updates](../docs/help/device.md) and [model updates](../docs/help/models.md).
Execution boundaries and limitations are described in the
[workflow architecture](../docs/architecture/workflows.md).

From any directory on Windows:

```bat
D:\git\olo-toolgate\bot\build.bat
D:\git\olo-toolgate\bot\build.bat --configuration text-summary
```

With no arguments, `build.bat` builds **all ten configurations locally** into
`D:\git\olo-toolgate\bot\bin` (or this checkout's `bot/bin` when cloned elsewhere).
Upload each resulting `.olo` through **Bots → Import bot .olo** on a fresh
setup supporting the current bundle/package contracts. Bundles carry their exact
required packages and configuration, without a source tenant's login, private
keys or device identity. The source commit records provenance, not a target
installation restriction. Copy the `.olo` files; the inventory and `SHA256SUMS`
help verify the transfer and are not separate bot uploads.

On the destination, configure its own user grants, model endpoints/vault secrets,
approved devices and directories before review, preparation and publication.
See [every bot's permission/resource requirements](PERMISSIONS.md). Importability
does not make these installation-owned resources available automatically. Hotswap
A/B share one workflow ID: import/publish the intended revision rather than
expecting both to appear as independent bots.

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
- `workflow.json` and `referenced-data.json`;
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

Import a bundle on **Bots → Import bot .olo**. Importing its embedded
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
