<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# native-log

Editable source for the log-triage workflow. Build with `profiles/build.bat --configuration native-log` on Windows or `profiles/build.sh --configuration native-log` on Linux/macOS.

`workflow-profile.json` owns workflow/agent/model configuration, `referenced-data.json` owns referenced directory definitions, `connections.json` owns logical trigger connections, and `build.json` selects exact tool package source builds. All required tool and trigger archives are embedded in the output .olo; no README is added to the bundle.

Import the versioned .olo on the console Workflows page, import its embedded packages, configure referenced data under the existing Control policy, restore trigger mappings, review and publish. Provision devices, model services, vault secrets and trusted native graph factories separately.

Append matching records to the configured `deployment.source.config.file` on the approved device; its regex/window/threshold select events. Inspect the Control workflow run outcome for the summary.
