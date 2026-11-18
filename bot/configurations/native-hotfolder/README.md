<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# native-hotfolder

Editable source for the hotfolder-intake workflow. Build with `bot/build.bat --configuration native-hotfolder` on Windows or `bot/build.sh --configuration native-hotfolder` on Linux/macOS.

`bot.json` owns workflow/agent/model configuration, `referenced-config.json` owns referenced directory definitions, `trigger-connections.json` owns logical trigger connections, and `package-build.json` selects exact tool package source builds. All required tool and trigger archives are embedded in the output .olo; no README is added to the bundle.

Import the versioned .olo on the console Workflows page, import its embedded packages, configure referenced data under the existing Control policy, restore trigger mappings, review and publish. Provision devices, model services, vault secrets and trusted native graph factories separately.

Place a text file in the configured `deployment.fileAgent.inbox`; results and archival behavior are defined by `deployment.fileAgent.output`, `processed` and `archive` in this folder’s workflow profile. See `example.txt`.
