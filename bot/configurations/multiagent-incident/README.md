<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO bot: multiagent-incident

**New to OLO?** Start with the [beginner walkthrough](#beginner-walkthrough-no-programming-experience-needed) below.

Two separate agents execute sequentially: `incident-triage-agent` classifies the
incident, then `incident-summary-agent` combines the original input and triage
into an operations handover. Both use local Qwen3 through the configured general
model group. Premium OpenRouter remains unattached standby. This text-only bot
uses no device files, tools or trigger packages.

Follow [model setup](../../../docs/help/models.md) and
[import/publish/run](../../../docs/help/run-bots.md). Configure the installation's
local Ollama vault key and approved HTTPS endpoint, build/import this `.olo`,
resolve readiness, review/prepare/publish, then click **Run**. Device membership
and filesystem permissions are unnecessary for this model-only example; see
[device setup](../../../docs/help/device.md) when adding tools.

```powershell
python bot/build.py --configuration multiagent-incident --no-sdk-build --version 1.0.0-demo.multiagent
```

Paste into Bot input:

```text
INCIDENT-204: The production API returned HTTP 503 because the database connection pool was exhausted. Restarting the pool restored service. No customer data was lost. Summarize the incident for an operations handover.
```

The result contains `triage.severity`, `triage.assessment` and `summary`.
The Run panel records both agents and their model calls. Follow the same run in
Langfuse, LangSmith and Phoenix using its `toolgate.run.id`; see
[observability setup](../../../docs/help/observability.md). Exported spans contain
structural metadata, not the incident text or generated prose. Model output
requires human review; no remediation or independent factual verification occurs.

In `bot.json`, the triage agent has `entryEligible: true`. The handover agent
has `entryEligible: false` and `delegationAllowed: true`: the sequential graph
invokes it after the first agent. Keep that eligibility when editing the graph.
[Recorded successful run and three-cloud views](../../../docs/media/olo-2026-11/README.md).

## Beginner walkthrough: no programming experience needed

### What is this bot for?

For operations teams preparing an incident handover. Paste an incident description: one
AI assistant suggests a severity and assessment, and a second writes a handover using
that assessment and the original text. It produces suggestions for human review and does
not repair services.

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
`configuration` is **`multiagent-incident`** and `workflowId` is **`multiagent-incident`**.
Download the exact `.olo` named by that entry's `filename` into **Downloads**.
The usual name is:

```text
multiagent-incident-multiagent-incident-<version>.olo
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
qualification and triggers. Return to **Bots**, select `multiagent-incident` using its
**Open …** button and follow [activation steps 6–8](../../README.md#beginner-import-and-activation-buttons-in-order).
Click **Refresh lifecycle status**; if still `DRAFT`, click **Review exact revision**.
Then **Provision reviewed packages**, **Prepare reviewed graphs**, and
**Refresh lifecycle status**, waiting for each action. Provisioning has no tool
packages for this bot. Once ready, click **Publish reviewed revision** and confirm
`PUBLISHED`. Import may already have completed some preparation actions.
Resolve red blockers before running.

### 5. Paste the sample and run

1. On **Bots**, click **▶ Run** for the published `multiagent-incident` revision.
2. In the dialog's **Bot input** box, paste:

   ```text
   INCIDENT-204: The production API returned HTTP 503 because the database connection pool was exhausted. Restarting the pool restored service. No customer data was lost. Summarize the incident for an operations handover.
   ```

3. Click **Run bot** once. Leave the panel open while **Execution progress**
   shows the run moving through its steps. Wait for **Run succeeded**.
4. Read **Result**. It is structured text (JSON) with `triage.severity`, `triage.assessment` and `summary`; the progress panel records both agents.
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
