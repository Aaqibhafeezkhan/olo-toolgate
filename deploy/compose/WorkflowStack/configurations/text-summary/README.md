<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Text summarization and processed-file archive

This profile uses two distinct configured agents:

| Agent | Responsibility |
| --- | --- |
| `langchain-linux-agent` | HotFolder file agent: read the input through the configured Gateway file tool; its trusted folder adapter writes confirmed summaries and archives originals in the configured host folders |
| `text-summarizer` | Summarization agent: turn the supplied text into a factual summary; it has model grants and no file-tool execution binding |

The `text-summary` workflow declares ordered `agentStages`: the file agent runs a
`CONTEXT` stage using its configured read tool, then the summarizer runs a `MODEL`
stage. The runtime transfers the full verified context directly. The file stage
needs no model inference, and a supervisor model cannot skip either stage or
rewrite the final result. The last agent's result becomes the workflow result.
After successful, schema-valid completion, the file agent adapter
independently verifies Control's result, profile and admitted source path before
writing files. Folder paths come from the workflow profile's `deployment.fileAgent`, never model output.
The adapter performs delivery writes; this does not qualify native mutation tools.
Version `text-summary-v5` returns only the summary. Context stages transfer
`message` and `context`; the model does not need to copy a device path.
The adapter binds the result to Control's admitted message and the private file journal.

From `deploy/compose/WorkflowStack`, stop the previous configuration, select
`CONFIGURATION=text-summary` in `.env`, then run `start.bat` and choose text-summary.
`start.bat --configuration text-summary` skips the menu. Published images need
no source build; see the [stack guide](../../README.md) for prerequisites.
The Admin UI is at `http://<docker-host-ip>:48091/console/`, with login files in
`state/text-summary/credentials/admin/username.txt` and `password.txt`.

Place a complete UTF-8 `.txt` file into `data/text-summary/inbox/`:

```powershell
Copy-Item configurations/text-summary/example.txt data/text-summary/inbox/meeting-001.txt
```

After processing:

```text
data/text-summary/
  inbox/                                   # meeting-001.txt is removed after confirmed delivery
  processed/meeting-001.txt                 # original contents, unchanged
  summaries/meeting-001-<id>.summary.txt     # generated summary
```

The private structured result is also in
`state/text-summary/workflow/results/<run-id>.json`. The folder agent logs
`FOLDER_DELIVERY_COMPLETE` with the run ID and output filename, without contents.
Check `docker compose logs --tail 50 workflow-worker folder-agent` and read the
actual `.summary.txt` file to see the outcome.

Use unique simple filenames containing letters/digits/`_`/`-`/`.` and ending in
`.txt`. Each file must contain 1–16384 UTF-8 bytes without NUL. Do not edit a file
while it is processing. Allow a few minutes for the trigger poll and model.
An existing processed filename or output with different contents is never
overwritten. A failed or unconfirmed run leaves the original unprocessed in
`inbox/`; inspecting a failed run does not retry its effects. The persistent
delivery journal recovers the same confirmed output after adapter restarts.
Private device snapshots are removed only after confirmed output delivery.

Edit the profile's agent prompts to change summarization behavior, using a new
workflow version ID. Edit the workflow's `deployment.fileAgent` to select different simple folder
names; redeploy to publish the reviewed profile and apply the mount selection.
See [monitoring](../../MONITORING.md) for local dashboards and external exporters.
