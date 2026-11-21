<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Native graph: hot-folder issue triage

This fresh configuration pins the installed supervisor graph. Its JSON profile
selects the hotfolder-watch trigger, issue agent, model and graph independently
of the generic worker. Use the same native-phase1 Quickstart/runtime images as
the `native-log` configuration. Do not reuse an older stack's storage.

From WorkflowStack run `start.bat` and select `native-hotfolder`, or run
`python manage.py start --configuration native-hotfolder`. Put the configured
`model.gguf` in `../../models/` first. Building these development images requires
the source checkout; published image qualification is separate from this demo.

Place a text issue description in
`../../data/native-hotfolder/inbox/example.txt`. Use `example.txt` here as sample
content. Inspect the triage JSON under `../../data/native-hotfolder/results/` (the
exact folder is declared by deployment.fileAgent.output in workflow-profile.json),
Control run status and `../../state/native-hotfolder/workflow/results/`.
`python tools/workflows/verify_stack_demo.py --configuration native-hotfolder`
from the repository root produces an independent Control-confirmed acceptance.

Console: http://localhost:62391/console/. Credentials are in
`../../state/native-hotfolder/credentials/admin/`; do not put their values in JSON.
Use Workflows -> Edit ... JSON and Agents -> Edit ... JSON to edit the shared
profile draft, then stage, review and publish the new revision. The profile page
also has a complete Profile JSON text box. Existing workflows remain version-pinned.

To switch use case, stop this selected stack and start `native-log` from the same
numbered configuration list. Both profiles use the same worker image; their
graph selection and trigger settings differ. `stop.bat --configuration native-hotfolder`
preserves storage. To remove this stack and its owned storage, use
`destroy.bat --configuration native-hotfolder`; keep any outputs you need first.
