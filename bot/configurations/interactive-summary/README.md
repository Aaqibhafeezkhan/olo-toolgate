<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bot: interactive-summary

**New to OLO?** Start with the [beginner walkthrough](#beginner-walkthrough-no-programming-experience-needed) below.

A text-only bot uses `qwen3:0.6b` through the configured agent. It needs
no device/tool/trigger. QLoRA is declared but not selected for this general agent;
OpenRouter is unattached standby with no automatic paid fallback.

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
& $debugPython bot/build.py --configuration interactive-summary --version 1.0.0-demo.local
```

The `.olo` and version/hash inventory are in `bot/bin/`. Import in **Bots**,
resolve every persistent red readiness bullet, review, provision packages,
prepare graphs and publish. The first Super Admin can perform these operations
and run the published bot. Runtime grants still govern every effect.

Start optional Ollama with `debug\profiled\start.bat -WithOllama` and, after
referenced groups exist, run `& $debugPython debug/profiled/ollama.py vault`.
Click this published bot's run symbol and enter:

```text
The database connection pool was exhausted. Restarting the pool restored service.
```

The dialog shows progress and a JSON object with a generated `summary`. No
folder/output file is used. Wording is model-generated, not an exact fixture.
Edit the agent prompt/group and deployments in `bot.json` or the corresponding
Admin editors, then publish a new revision. Export includes referenced data but
never the model secret or weights; use the two central guides on a new system.

Model-group/deployment identifiers in this bot are prefixed with `interactive-summary-`
to keep its independently published configuration separate from other bots.
The provider model IDs and ordered routing are unchanged.

## Beginner walkthrough: no programming experience needed

### What is this bot for?

For anyone who wants to shorten a paragraph without managing files. Paste text into the
console and receive a short summary on screen. This is a good first bot to try; it does
not watch folders or change documents.

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
`configuration` is **`interactive-summary`** and `workflowId` is **`interactive-summary`**.
Download the exact `.olo` named by that entry's `filename` into **Downloads**.
The usual name is:

```text
interactive-summary-interactive-summary-<version>.olo
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

### 4. Prepare and publish this bot

This is a text-only bot: skip devices, group allocation, folders, tool
qualification and triggers. Return to **Bots**, select `interactive-summary` using its
**Open …** button and follow [activation steps 6–8](../../README.md#beginner-import-and-activation-buttons-in-order).
Click **Refresh lifecycle status**; if still `DRAFT`, click **Review exact revision**.
Then **Provision reviewed packages**, **Prepare reviewed graphs**, and
**Refresh lifecycle status**, waiting for each action. Provisioning has no tool
packages for this bot. Once ready, click **Publish reviewed revision** and confirm
`PUBLISHED`. Import may already have completed some preparation actions.
Resolve red blockers before running.

### 5. Paste the sample and run

1. On **Bots**, click **▶ Run** for the published `interactive-summary` revision.
2. In the dialog's **Bot input** box, paste:

   ```text
   The database connection pool was exhausted. Restarting the pool restored service.
   ```

3. Click **Run bot** once. Leave the panel open while **Execution progress**
   shows the run moving through its steps. Wait for **Run succeeded**.
4. Read **Result**. It is structured text (JSON) with a `summary` field containing the shortened text.
   This bot creates no output file. AI wording varies and needs human review.
5. For another paragraph, close the run panel, click **Run** again and enter
   the new text. If progress disconnects, use **Resume progress**; if submission
   is unknown, use **Check same submission** instead of starting a duplicate.

For help stopping/restarting OLO or resolving an error, use
[Beginner troubleshooting and next use](../../README.md#beginner-troubleshooting-and-next-use).
Normal restarts keep your login and imported bot.

### Adapt this bot to your use case

Follow the central [configuration and monitoring tuning guide](../../../docs/help/tune-bots.md)
to define domain-specific success criteria, connect Langfuse/LangSmith/Phoenix,
change prompts or model configuration, publish a new revision and compare it
against the same examples. Monitoring shows execution structure and timing;
review answer quality in OLO. Model-weight training is a separate process.
