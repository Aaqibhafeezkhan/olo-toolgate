<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Configure and update OLO devices

Bots declare packages, trigger settings and target groups. A destination
operator approves/enables a real connected device and assigns its membership.
Packages and live settings travel through Gateway to that device.

## Choose the installation

| Mode | Launcher | Private Admin folder | Compose project |
|---|---|---|---|
| Local source build | `debug/profiled/start.bat` | `debug/profiled/credentials/admin` | `toolgate-profiled` |
| Published images, once available | `deploy/docker/quickstart/dev/start.bat` | `deploy/docker/quickstart/dev/credentials/admin` | `olo-quickstart-dev` |

The [published-image runbook](../../deploy/docker/quickstart/dev/README.md)
provides its exact Compose command, first login and optional model/collector
settings. Select its `$compose` array before using the bot file commands below;
bot guides retain an explicitly selected array. The logical Admin/API steps are
the same. The examples below otherwise default to the source-build installation.

## Prepare a destination

Start the [profiled installation](../../debug/profiled/README.md), open
`http://127.0.0.1:18090/console/`, and use the Admin credentials in private
`debug/profiled/credentials/admin/`. Super Admin may import, stage, review,
provision, publish and run; runtime grants remain separate.

1. Import the selected `.olo` in **Bots**, including its referenced data.
2. In **Devices**, review the connected device's ID and enrollment fingerprint,
   approve and enable it, then add all groups referenced by the bot's packages
   and execution bindings. The A/B examples use `hotswap-ReadAndWriteDeviceGroup` and
   `hotswap-langchain-linux-devices`; the other cases use prefixed equivalents.
3. In **Administration > Device**, permit the intended file root/extensions.
   The local root is `/var/lib/olo-toolgate/files`; include `.txt`, `.json` and
   `.log` for these cases. Restrict access to the intended deployment. Supported
   live settings apply on device sync.
4. Provision reviewed packages. Native files also require explicit
   owner/confinement qualification and current capability/service grants.
   Approval alone does not authorize effects. Read the persistent red bot
   diagnostics for missing packages, groups, worker or scope.

Scripts `tools/admin/provision_devices.py` and
`tools/admin/qualify_native_files.py` use the same backend APIs as the console;
see the [admin scripts](../../tools/admin/README.md). The provisioning script
deliberately enables auto-approval for future devices: use it only when that
installation policy is intended. Native qualification requires explicit
`--acknowledge-unfenced-files`; read the current confinement guarantees and
limitations before accepting it. These scripts do not change Admin credentials.

## Folders and permissions are an operator action

Import, publication and switching never seed, clear, move or delete device
files. Prepare the exact folders in the selected bot's README yourself. From
the repository root, in PowerShell:

```powershell
if (-not $compose) { $compose = @('compose','-p','toolgate-profiled','-f','debug/profiled/state/compose.json') }
docker @compose exec -T device python3 -c "from pathlib import Path; import os; root=Path('/var/lib/olo-toolgate/files'); paths=[root/'inbox-a',root/'processed',root/'summaries']; [(p.mkdir(parents=True,exist_ok=True),p.chmod(0o700)) for p in paths]"
```

This creates only those demonstration directories and grants the current debug device owner access. For a host bind mount, apply host Windows ACL/Linux ownership
instead. Files must have the same owner as the device broker, mode 0600,
and one hard link; unsafe entries are refused. The debug broker runs as root
inside the container, separately from isolated package execution. On Linux,
for example:

```sh
sudo install -d -o root -g root -m 0700 /srv/olo/files/inbox-a /srv/olo/files/processed /srv/olo/files/summaries
```

Hot-folder/log watchers use the existing device connection: no inbound port is
needed. A webhook package's listener/proxy/signature/firewall settings are
operator responsibilities; follow its package README and open only its specific
configured port. Bot import never changes container mounts/network/ports.

## Change a folder or group

Edit the bot's package trigger configuration and matching trigger mapping in
the Admin UI. Keep assignment/subscription names and trigger IDs consistent.
Stage/review/provision/publish. Desired state propagates to the connected device
and reconciles its watcher without restarting it. Prepare permissions on the
new folder first. Old folders/content stay under your control; explicitly stop
a retired assignment when no longer wanted. New devices need explicit group
membership even when enrollment is auto-approved.
