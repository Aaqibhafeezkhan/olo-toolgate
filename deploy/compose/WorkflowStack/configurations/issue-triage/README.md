<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Issue-log triage

This is the working issue-log configuration moved into a selectable directory.
`workflow-profile.json` publishes `hotfolder-intake` / `issue-triage-v3`, using
`langchain-linux-agent` to read the file and triage it through the local model.
The workflow's `deployment.fileAgent` selects the host folders and JSON result projection.
The workflow pins one `MODEL` agent stage, so a supervisor cannot skip the
configured agent or invent a result before its configured file read.

From `deploy/compose/WorkflowStack`, select `CONFIGURATION=issue-triage` in `.env` and run
`start.bat --configuration issue-triage`. Published images require no source build;
see the [stack guide](../../README.md) for image selection and prerequisites.
Its profile declares console port 58091 (https/API 58451, registry 58552), so
it can also run beside text-summary using `--configuration issue-triage`.
Admin login files are `state/issue-triage/credentials/admin/username.txt` and
`password.txt`. The UI is also accessible at `http://<docker-host-ip>:58091/console/`.

Drop a UTF-8 `.txt` issue log containing an `ISSUE-ID`, service, symptoms and
observations into `data/issue-triage/inbox/`. For example:

```powershell
Copy-Item configurations/issue-triage/example.txt data/issue-triage/inbox/issue-001.txt
```

Look in `data/issue-triage/results/` for
`issue-001-<delivery-id>.result.json`. It contains `issueId`, `summary`, `priority`,
and `nextAction`. Source identity comes from Control's admitted message, never
from a path echoed by the model. The file agent verifies the
successful Control result before creating this file. The original stays in
`inbox/`; this case does not archive it into `processed/`.

Use unique simple filenames, such as `issue-002.txt`: letters, digits, `_`, `-`
and `.` only. Files must contain 1–16384 UTF-8 bytes with no NUL. Finish writing
the file before dropping it; concurrent edits are refused. The trigger polls
roughly once a minute, followed by local model execution. Allow a few minutes.
Use a new filename for a new delivery; leaving an unchanged file does not rerun it.

Private acknowledged worker results are also in
`state/issue-triage/workflow/results/<run-id>.json`. Inspect
`docker compose logs --tail 50 workflow-worker workflow-listener folder-agent`
if no output appears. Failed/unconfirmed runs do not create a successful output
or authorize automatic tool retries. Treat `state/` and generated results as
private. [Monitoring setup](../../MONITORING.md) applies to this configuration.
