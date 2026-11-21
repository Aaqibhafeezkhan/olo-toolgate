<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bot: native-hotfolder

**New to OLO?** Start with the [beginner walkthrough](#beginner-walkthrough-no-programming-experience-needed) below.

This device-triggered flow explicitly reads the original file, asks the
OLO QLoRA file agent for a proposal, uses the other agent with lightweight
Ollama for the result, then writes the confirmed output. File agents try
`olo-qlora-hotfolder:e376fb40` first and `qwen3:0.6b` on inference failure;
other agents use `qwen3:0.6b`. OpenRouter remains unattached standby.
A model proposal alone cannot cause a file operation.

Start with the centralized [device setup/update guide](../../../docs/help/device.md)
and [model setup/update guide](../../../docs/help/models.md). Also read
[import/publish/run](../../../docs/help/run-bots.md),
[observability](../../../docs/help/observability.md) and
[authoring](../../../docs/help/author-bots.md). Installation settings, credentials,
model weights and folder permissions are outside this bot.

Build from the repository root:

```powershell
. .\debug\profiled\python.ps1
$debugPython = Find-DebugPython
& $debugPython bot/build.py --configuration native-hotfolder --version 1.0.0-demo.local
```

The `.olo` and version/hash inventory are in `bot/bin/`. Import in **Bots**,
resolve every persistent red readiness bullet, review, provision packages,
prepare graphs and publish. The first Super Admin can perform these operations
and run the published bot. Runtime grants still govern every effect.

Workflow/bot ID: `native-hotfolder`. Trigger: `examples.hotfolder-watch/new-file`;
assignment `native-hotfolder-watch`; target group `native-hotfolder-ReadAndWriteDeviceGroup`.
Add your real device to that group and every execution-binding group in
`referenced-config.json`. Then provision and qualify the reviewed native file
tools as explained in the device guide. Set `ollama-local-key` using
`& $debugPython debug/profiled/ollama.py vault`.

## Explicit folder setup

Import/switch never creates or changes device files. Run this separate operator
action to create only this case's directories and retain the debug device owner's access. Inputs must be owned by the device account with mode 0600. For host mounts, use the host permission commands in the device guide.

```powershell
if (-not $compose) { $compose = @('compose','-p','toolgate-profiled','-f','debug/profiled/state/compose.json') }
docker @compose exec -T device python3 -c "from pathlib import Path; import os; root=Path('/var/lib/olo-toolgate/files'); [(p.mkdir(parents=True,exist_ok=True),p.chmod(0o700)) for p in [root/x for x in ['native-hotfolder/inbox', 'native-hotfolder/processed', 'native-hotfolder/summaries']]]"
```

The fresh stack permits `.txt` and `.json` under its configured file root.
For another installation, have its operator check the device file configuration
in the device guide. Wait until the trigger reports Running/Ready before
creating a new input file.

## Trigger with this exact content

```powershell
@'
ISSUE-123 HIGH: The database connection pool was exhausted. Restarting the pool restored service.
'@ | docker @compose exec -T device python3 -c "import sys,os; from pathlib import Path; os.umask(0o077); p=Path('/var/lib/olo-toolgate/files/native-hotfolder/inbox/source.txt'); p.open('w',encoding='utf-8').write(sys.stdin.read())"
```

Input is `native-hotfolder/inbox/source.txt` under `/var/lib/olo-toolgate/files`.
The result is `native-hotfolder/summaries/result.json`. The original moves to `native-hotfolder/processed/source.txt` unchanged.
Use **Bots** run observations/results for progress and the final JSON.
Generated wording can vary; the configured output schema still applies.

```powershell
docker @compose exec -T device python3 -c "from pathlib import Path; print(Path('/var/lib/olo-toolgate/files/native-hotfolder/summaries/result.json').read_text(encoding='utf-8'))"
```

For manual Run, first stop this trigger assignment to avoid submitting the same
file through both entry points. Then use **Run**: enter `native-hotfolder/inbox/source.txt` after creating that file.
Output/archive paths are fixed demo settings. Before repeating, explicitly
choose new paths or inspect/remove prior artifacts yourself; imports do not
clean them. Do not resend an unknown write outcome.

Change folder/trigger settings in the package declaration and matching
`trigger-connections.json`, and graph paths in `bot.json`. Keep references
consistent, rebuild/import or edit the Admin JSON pages, then publish a new
revision. Use the centralized device/model guides for a different destination.

Model-group/deployment identifiers in this bot are prefixed with `native-hotfolder-`
to keep its independently published configuration separate from other bots.
The provider model IDs and ordered routing are unchanged.

## Beginner walkthrough: no programming experience needed

### What is this bot for?

For someone evaluating automatic handling of text files on an approved device. Put a new
sample in its watched inbox; the bot reads it, generates a summary, writes a result and
moves the original to its processed folder. This is a device-file demonstration similar
to text-summary, with separate paths and configuration.

### 1. Install the basics, start OLO and sign in

You need [Docker Desktop](https://www.docker.com/products/docker-desktop/) to run
OLO and [Git](https://git-scm.com/downloads) to download it. You may also install
[Git Extensions](https://gitextensions.github.io/) for a visual Git interface.
Follow [Beginner setup: install, deploy and sign in](../../README.md#beginner-setup-install-deploy-and-sign-in)
from step 1 through step 4, then come back here. That guide explains opening
PowerShell, downloading this repository, starting the development stack, finding
the private username/password files and clicking **Connect to workspace**.
Complete its GPU/model prerequisites before continuing. Keep the browser open
and PowerShell at the repository root (`olo-toolgate`).

### 2. Download this bot from the selected development release

Open [OLO releases](https://github.com/olo-labs/olo-toolgate/releases), open the
same development release you selected for the containers, and expand **Assets**.
Download its `olo-profiles-<version>.json` inventory and find the entry whose
`configuration` is **`native-hotfolder`** and `workflowId` is **`native-hotfolder`**.
Download the exact `.olo` named by that entry's `filename` into **Downloads**.
The usual name is:

```text
native-hotfolder-native-hotfolder-<version>.olo
```

Here `<version>` means the inventory's artifact version, not the literal text
`<version>`; long version labels may be shortened, so the inventory is authoritative.
Verify it against the same release's `SHA256SUMS` using the
[download steps](../../README.md#choose-one-development-release-and-download-your-bot).
Do not unzip the bundle. It already contains its required tool/trigger packages;
**you do not need to build tools or bots** to explore this example. Released
`.olotool` archives are also available separately when you need a standalone tool.
If the bundle is missing, select a complete release; do not substitute a different
bot or version. Source-build commands above are optional authoring instructions.

### 3. Import this file and connect the AI model

1. In the left menu, open **OLO Orchestration > Bots > Bots**.
2. Scroll to **Bot import / export (.olo)**. Under **Bot .olo file**, choose
   the released file downloaded in step 2 from **Downloads** (or your chosen folder).
3. Leave **Import destination** at **Add, or replace the matching bot ID**,
   then click **Import bot .olo**. Wait for its result. A pending setup message
   is a request to finish the next steps, not a reason to keep importing.
4. After import has created the reference groups, run this in PowerShell:

   ```powershell
   & $debugPython deploy/docker/quickstart/dev/manage.py ollama-vault
   ```

   This saves the local AI credential. It is separate from your console password.
   For the complete button sequence and readiness help, see
   [beginner import and activation](../../README.md#beginner-import-and-activation-buttons-in-order).

### 4. Approve the local device and choose its groups

Follow [Beginner device setup](../../README.md#beginner-device-setup-for-file-and-log-bots)
steps 1–4: compare the device code/fingerprint, click **Approve device**, switch
its directory status to **Enabled**, and open **Groups for …**.
In **Choose device groups**, select these exact group IDs as well as retaining
existing intended membership:

- `native-hotfolder-ReadAndWriteDeviceGroup`
- `native-hotfolder-langchain-linux-devices`

Click **Apply selection**, confirm you reviewed the membership change and click
**Save group membership**. Complete any required independent configuration
review, then click **Publish access changes**. Use group IDs to distinguish
similarly named records. Approval/group membership alone does not authorize tools.

### 5. Create this demonstration's folders before publication

Copy the following complete box into the root PowerShell window. It selects the
published development stack and creates private folders inside its device.
You do not need to find or create these Linux paths in Windows File Explorer.

```powershell
$compose = @('compose','-p','olo-quickstart-dev','-f','deploy/docker/quickstart/dev/state/compose.json')
docker @compose exec -T device python3 -c "from pathlib import Path; import os; root=Path('/var/lib/olo-toolgate/files'); [(p.mkdir(parents=True,exist_ok=True),p.chmod(0o700)) for p in [root/x for x in ['native-hotfolder/inbox', 'native-hotfolder/processed', 'native-hotfolder/summaries']]]"
```

Do not create the sample input until the watcher has started in step 7.

The fresh stack already permits the sample extensions and file root described
in the shared device setup. Return to **Bots**, select this revision, resume
preparation if offered, review it if still `DRAFT` and click **Provision reviewed
packages**. Wait for installation. Then set the values below in PowerShell:

```powershell
$nativeDeviceGroup = 'native-hotfolder-langchain-linux-devices'
$nativeTools = @('examples.hotfolder/read_text', 'examples.hotfolder/move', 'examples.hotfolder/write_text')
```

Read and complete [Explicit native-file qualification](../../README.md#explicit-native-file-qualification),
including its PowerShell box. It records your explicit one-day acceptance for
this device's installed native file tools; ask your operator if you cannot
assess the stated requirements. Do not skip a refused or missing qualification.

### 6. Prepare and publish this bot

Return to **Bots** and open the `native-hotfolder` revision from **Available bot revisions**.
Follow [activation steps 6–8](../../README.md#beginner-import-and-activation-buttons-in-order):
resume preparation if offered, **Refresh lifecycle status**, **Review exact revision**
if still `DRAFT`, **Provision reviewed packages**, **Prepare reviewed graphs**,
then **Refresh lifecycle status** and **Publish reviewed revision** when ready.
Wait between actions; do not proceed while red blockers remain. Expect `PUBLISHED`.

### 7. Send one sample and read the result

1. In **OLO ToolGate > Devices > Triggers**, find the imported assignment
   `native-hotfolder-watch`. Wait until its status has running instances and no failed
   instances. Do not click **Create trigger**: the bot import supplied it.
2. Return to PowerShell and paste this whole box once:

```powershell
@'
ISSUE-123 HIGH: The database connection pool was exhausted. Restarting the pool restored service.
'@ | docker @compose exec -T device python3 -c "import sys,os; from pathlib import Path; os.umask(0o077); p=Path('/var/lib/olo-toolgate/files/native-hotfolder/inbox/source.txt'); p.open('w',encoding='utf-8').write(sys.stdin.read())"
```

3. The watcher submits the bot automatically. Return to **Bots** to inspect run
   observations/results. Do not also click **Run** for this same file. To display
   the saved output in PowerShell, run the following after the run completes:

```powershell
docker @compose exec -T device python3 -c "from pathlib import Path; print(Path('/var/lib/olo-toolgate/files/native-hotfolder/summaries/result.json').read_text(encoding='utf-8'))"
```

Input is `native-hotfolder/inbox/source.txt` under `/var/lib/olo-toolgate/files`.
The result is `native-hotfolder/summaries/result.json`. The original moves to `native-hotfolder/processed/source.txt` unchanged.
The generated wording can vary. If the output is missing, inspect the run's
failure/readiness and watcher status before sending another event. The output
and archive paths are fixed in this demonstration. Inspect existing files and
choose new paths deliberately before repeating; do not blindly overwrite or
replay a run whose write outcome is unknown. Manual-run input and editing details
are in the technical instructions above.

For help stopping/restarting OLO or resolving an error, use
[Beginner troubleshooting and next use](../../README.md#beginner-troubleshooting-and-next-use).
Normal restarts keep your login and imported bot.

### Adapt this bot to your use case

Follow the central [configuration and monitoring tuning guide](../../../docs/help/tune-bots.md)
to define domain-specific success criteria, connect Langfuse/LangSmith/Phoenix,
change prompts or model configuration, publish a new revision and compare it
against the same examples. Monitoring shows execution structure and timing;
review answer quality in OLO. Model-weight training is a separate process.
