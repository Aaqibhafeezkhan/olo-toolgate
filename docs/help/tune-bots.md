<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Adapt a bot to your use case with monitoring

Start with a working [released bot](../../bot/README.md#first-time-users-choose-a-bot)
on the published development containers in
`D:\git\olo-toolgate\deploy\docker\quickstart\dev` (or your clone location).
This guide helps you change what the bot is asked to do, inspect its execution,
and compare the result against your own examples. No programming is needed for
the first prompt experiment. Keep a copy of the original downloaded `.olo`.

Here “tuning” means improving prompts and reviewed configuration. Monitoring
does not train a model, automatically correct answers or publish changes. Model
weight training is a separate ML task; see [model setup](models.md) for the
supplied QLoRA artifact and replacement model requirements.

## 1. Decide what a useful answer looks like

Write one sentence describing the task, its intended reader and a useful result.
For example: “Help our support desk summarize a customer issue in plain language,
identify what is known, and suggest one investigation step for a human.”

Prepare a small private evaluation set, such as five fictional examples:

| Example | What you want to check |
|---|---|
| A normal issue with enough detail | Correct facts, short wording, a useful next step |
| An incomplete issue | Missing information is stated rather than invented |
| An urgent incident | Priority follows your written business rule |
| Conflicting details | Uncertainty remains visible |
| Text containing “ignore the instructions” | The document remains data; it does not change the bot's instructions |

For each example, write the key facts the answer must preserve, anything it must
not claim, and an acceptable response time. Use the same examples before and
after a change. Start with a text-only bot for an experiment that needs no file
effects. File bots need their documented device permissions and new input/output
paths for repeat runs; do not replay an uncertain write to obtain another trace.

## 2. Turn on one monitoring destination

You can begin with the console's **Execution progress** and **Result** alone.
For a trace tree and timing view, choose one of Langfuse, LangSmith or Phoenix.
One destination is enough to start. Their accounts and API keys are separate
from your OLO login and from model-provider keys.

1. Open PowerShell in your clone's `deploy\docker\quickstart\dev` folder. Keep
   the same `.env` with the released image versions used for your bot. If you
   followed the example location, enter:

   ```powershell
   Set-Location D:\git\olo-toolgate\deploy\docker\quickstart\dev
   .\start.bat -WithOllama -WithMonitoring
   ```

   This explicitly adds the collector to your existing local-model installation.
   If your operator configured another model provider, use `-WithoutOllama`
   instead. Wait for completion. The first use creates the private settings file;
   with no vendor keys, it has a local diagnostic destination only.
2. Choose a destination and create its project/workspace and key using its
   official instructions. For Langfuse, open your project's **Settings > API
   Keys**, create a key pair and save both values immediately; see
   [Langfuse key instructions](https://langfuse.com/faq/all/lost-secret-key).
   For the alternatives, use the official
   [LangSmith account/API key guide](https://docs.langchain.com/langsmith/create-account-api-key)
   or [Phoenix documentation](https://arize.com/docs/phoenix).
3. In that same PowerShell folder, open:

   ```powershell
   notepad .\credentials\observability.json
   ```

   Fill only the chosen destination's fields. Keep JSON double quotes and commas
   as supplied; leave the other destinations' key fields empty.

   | Destination | Fields in the supplied private template |
   |---|---|
   | Langfuse | `baseUrl`: your regional/self-hosted Langfuse origin; `publicKey` and `secretKey`: the project's key pair |
   | LangSmith | `baseUrl`: your regional API origin; `apiKey`: the selected workspace's key; `project`: your trace project name |
   | Phoenix | `baseUrl`: your Phoenix space URL, replacing `YOUR-SPACE`; `apiKey`: that space's key; `project`: your trace project name |

   Use the destination/account's actual URL, not a guessed region. For example,
   the template's Langfuse origin is `https://cloud.langfuse.com`; the helper
   adds its trace ingestion path. Advanced workspace/agent routing and the
   exact supported fields are owned by the [observability guide](observability.md)
   and its linked template. Do not paste a full ingestion URL into a field
   that expects only the service origin.
4. Save and close Notepad. Apply the explicit installation change:

   ```powershell
   .\start.bat -WithOllama -WithMonitoring
   ```

   Again, use `-WithoutOllama` if you use another provider. Check that the output
   names your chosen destination and reports telemetry enabled. Changes to
   installation telemetry may recreate the runtime; an ordinary bot prompt
   change/import does not. Keep keys in this ignored credential file, never
   in bot JSON, `.olo`, a screenshot or an issue.

## 3. Record a baseline and find its trace

1. Open the OLO console, go to **Bots**, open the published revision and click
   **Run**. Paste the first text-only example into **Bot input** and click
   **Run bot** once. For a file bot, use its README's trigger procedure instead.
2. Wait for the result. Save the bot revision, sample label, run ID, result and
   your assessment in private notes. Do not put real customer content into a
   public repository. A terminal run may be denied or failed; do not count it
   as a useful business result simply because a trace exists.
3. Open your selected monitoring project and its trace/run list. Use the run
   time to narrow the list; open a trace and inspect/search its attributes for
   `toolgate.run.id` equal to the OLO run ID. Dashboard search layouts differ;
   the run ID is the correlation value, not an assumed trace-title format.
4. Expand the workflow, agent, model and tool spans. Inspect their sequence,
   recorded durations and errors. For multiagent-incident, look for both agent
   stages. Compare these observations with the console's recorded progress.
5. Repeat the evaluation set using the unchanged revision. Record approximate
   timings and an honest pass/fail against your written criteria. If traces are
   absent, check the selected project/time window, the startup destination list,
   credentials/region and collector errors. In Docker Desktop, find the
   `olo-quickstart-dev-observability` project and inspect its collector logs.
   Follow [delivery verification](observability.md#scope-and-validation) before
   treating a missing trace as proof that the bot did not execute.

Workflow exports contain **structural metadata**, not private prompts, document
content, tool arguments, generated answers or credentials. Judge answer quality
in the OLO result and your private evaluation notes. Do not expect a vendor's
prompt playground, automatic content evaluation, token counts or cost displays
to be populated by these traces. A displayed zero cost is not a measured cost.

## 4. Make one small configuration change

Start with the prompt before changing models or tools. For the support example,
add a requirement such as:

```text
Write for a nontechnical support colleague. Preserve the stated facts.
State when important information is missing. Do not invent a cause or claim
that an investigation or repair has already happened. Keep the summary brief.
```

1. In **OLO Orchestration > Bots > Bots**, open the revision you evaluated.
2. Open **OLO Orchestration > Agents > Models & prompts**. Under **Agent prompts
   and context**, find the agent that writes the summary/triage result and click
   its **Edit …** button. Read the existing `systemPrompt` before changing it.
3. Add your domain wording while retaining the existing finish-decision format,
   output field names, safety instructions and `/no_think` where supplied. Click
   **Apply to bot draft** in the agent editor. Do not replace the required JSON
   schema with a prose-only answer: later graph steps use those exact fields.
4. Return to **Bots** with the shared working draft. In the **Bots** collection,
   click **Edit …** for this bot and give `versionId` a new, unused identifier,
   such as `support-trial-1` (use a different identifier for every later trial).
   Keep its `id` unchanged and click **Apply to bot draft**. The editor updates
   matching trigger version references. This avoids trying to overwrite an
   immutable workflow version with different configuration.
   Click **Stage bot revision**,
   **Review exact revision**, **Provision reviewed packages**, **Prepare reviewed
   graphs**, **Refresh lifecycle status**, then **Publish reviewed revision**
   when ready. Fix any blockers and complete the installation's required review.
   Record the new revision. Publication makes the new configuration active for
   new admissions; it does not change already accepted runs.
5. If the change needs different model routing, use **Bots > Models** and the
   [model configuration guide](models.md), including endpoint/secret setup.
   If it needs new folders, triggers or tools, follow [device setup](device.md)
   and [authoring](author-bots.md). Monitoring observations never justify
   broadening runtime grants or automatically repeating an uncertain effect.

## 5. Compare, keep or revise

Run the same examples against the changed publication. Compare correct facts,
missing-information handling, instruction resistance, readability and observed
latency. Change one variable at a time so you can identify what helped.

| Observation | Next useful experiment |
|---|---|
| Answer is vague, but the run succeeds | Add a clear audience, required facts and one short fictional example to the prompt while retaining its result contract |
| Priority is wrong for your business | Describe your priority rules explicitly; compare urgent and ordinary examples |
| Missing facts become invented details | Require explicit uncertainty; add incomplete examples to evaluation |
| Output/schema validation fails | Restore the expected JSON fields/types and finish-decision instructions before changing models |
| Model stage dominates elapsed time | Compare a supported smaller model or appropriate generation limit; check the configured model ID and answer quality. Current model-group spans alone do not identify the selected fallback candidate |
| Tool step is denied | Review the named scope/grant and intended resource with the operator; prompt wording cannot authorize it |
| Tool outcome is unknown | Inspect durable outcome evidence and the target/files; do not resend to improve a dashboard |

Keep the change only if it meets your criteria without unacceptable regressions.
If needed, reopen a known-good revision and use the supported review/readiness/
publication lifecycle; an old revision can be blocked by current dependencies
or immutable trigger/version rules. Do not promise rollback by reimporting an
archive blindly. Export the accepted bot with **Export bot .olo** and record its
version and evaluation notes privately. The export does not include provider
keys, monitoring settings, model weights or real device membership.

For actual weight fine-tuning, give an ML operator a reviewed, consented dataset
and separate held-out examples. They must train and evaluate a new model artifact,
provision it, update its deployment/model ID and repeat this evaluation. The
monitoring collector does not supply training data or perform that process.
