# Administrative API scripting

`control.py` calls the same authenticated Control APIs used by the Admin UI.
It needs Python 3.11+ and uses the standard library. It has no Docker access,
database credentials, implicit approvals or automatic retries. HTTPS certificate
and hostname verification are mandatory; redirects are refused.

An operator supplies a **current administrator bearer token** in a private file,
obtained through the installation's identity provider/login process. This is not
the administrator password file. Keep tokens and request/response files in an
ignored credentials directory with access restricted to the operator. Never
commit them or put the token on the command line. For example, on Windows:

```powershell
$private = Join-Path (Get-Location) 'debug/profiled/credentials/admin'
New-Item -ItemType Directory -Force -Path $private | Out-Null
$operator = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $private /inheritance:r /grant:r "${operator}:(OI)(CI)F"
```

Place the token in `operator.token` there. For a local installation, obtain its
public CA certificate through the existing trusted enrollment/setup process and
save it as `gateway-ca.crt`. Do not replace the CA merely because a request fails.
Remote deployments can use their system-trusted HTTPS origin without `--ca-file`.

```powershell
$api = @('--origin', 'https://localhost:18450', '--ca-file', "$private/gateway-ca.crt", '--token-file', "$private/operator.token")
python tools/admin/control.py @api --path /api/control/v1/endpoint/devices
```

Stdout is an explicit JSON envelope with `httpStatus` and `body`. Responses may
contain private configuration: redirect them to the private directory when
needed. HTTP 202 means a pending configuration change, **not an applied change**.
HTTP refusals and transport failures return a nonzero exit code; the script does
not retry a possibly committed effect. Keep the original idempotency key and
exact input for an explicit retry, and re-read state after an unknown outcome.

## Explicit remote device administration

Enrollment must already have been submitted by the real device. Inspect its
enrollment code/key fingerprint using device management before approving it.
Enablement, approval and membership are independent; profile import does none
of them automatically. First read the current directory record/membership and
endpoint record to obtain their separate revision values:

```powershell
$device = Read-Host 'Enrolled device ID'
python tools/admin/control.py @api --path "/api/control/v1/devices/$device"
python tools/admin/control.py @api --path "/api/control/v1/endpoint/devices/$device"
python tools/admin/control.py @api --path "/api/control/v1/devices/$device/groups"
```

Create the following JSON request files in the private directory, substituting
the actual revisions and device/group IDs. `expectedRevision: 0` is only for an
enrolled identity with no directory record yet. Approval uses the endpoint's
**approvalRevision**, rather than its heartbeat revision. This example requests
one hour of approval; it does not silently request unlimited access.

```powershell
$directoryRevision = [long](Read-Host 'Current directory revision (0 if absent)')
$approvalRevision = [long](Read-Host 'Current approvalRevision')
$groupRevision = [long](Read-Host 'Current membership revision')
$group = Read-Host 'Existing enabled device group ID'
@{expectedRevision=$directoryRevision; enabled=$true} | ConvertTo-Json | Set-Content -Encoding ascii "$private/enable.json"
@{expectedApprovalRevision=$approvalRevision; approved=$true; connectionExpiresAtUnixMs=[DateTimeOffset]::UtcNow.AddHours(1).ToUnixTimeMilliseconds()} | ConvertTo-Json | Set-Content -Encoding ascii "$private/approve.json"
@{entityType='DEVICE'; entityId=$device; groupIds=@($group); revision=$groupRevision} | ConvertTo-Json | Set-Content -Encoding ascii "$private/groups.json"
$enableKey = [guid]::NewGuid().ToString()
$approvalKey = [guid]::NewGuid().ToString()
$groupKey = [guid]::NewGuid().ToString()
python tools/admin/control.py @api --method POST --path "/api/control/v1/endpoint/devices/$device/enabled" --json-file "$private/enable.json" --idempotency-key $enableKey
python tools/admin/control.py @api --method POST --path "/api/control/v1/endpoint/devices/$device/approval" --json-file "$private/approve.json" --idempotency-key $approvalKey
python tools/admin/control.py @api --method PUT --path "/api/control/v1/devices/$device/groups" --json-file "$private/groups.json" --revision $groupRevision --idempotency-key $groupKey
```

Membership replaces the complete group list; include every intended group. If
an earlier operation changes the directory/membership revision, re-read it and
update `groups.json`/`--revision` before the membership request. Stop on any
failure or HTTP 202 and resolve its required review in the console. Do not treat
a successful enablement as device approval or package execution permission.

## Workflow provisioning APIs and current limits

Fresh profiled installations start blank with one password-protected admin.
Use **Workflows â†’ Import workflow .olo** for bundle transfer. The backend still
has separate package upload, referenced-data review, workflow staging/review,
provisioning, graph preparation and publication operations. `control.py` can
call these existing APIs with bounded JSON input and exact `.olotool` bytes:

```powershell
$package = Read-Host 'Exact .olotool file path'
python tools/admin/control.py @api --method POST --path /api/control/v1/packages/uploads --package-file $package
```

See the canonical [Control API](../../packages/contracts/openapi/control-v1.yaml),
particularly `/workflow-profiles`, `/{profileId}/review`, `/provision`,
`/graphs:prepare`, `/publish`, `/configuration` and trigger endpoints. A
`--json-file` supplies the documented body; `--revision` sends `If-Match` where
required. The server continues to enforce current management permissions,
review, live device readiness and runtime capability checks.

This generic client **does not yet orchestrate `.olo` import to activation** or
automatically apply referenced configuration/restore connections. It does not
accept `.olo` as an `.olotool` package. That durable orchestration remains open
in the [hot-swap plan](../../docs/plans/workflows.md). No script
success should be interpreted as a runnable workflow or end-to-end acceptance.

## Automated device policy and allocation

`provision_devices.py` uses the same settings API as the Admin console's
`#config-device` page. With a current administrator token and trusted CA:

```powershell
python tools/admin/provision_devices.py @api --owner admin --days 30
python tools/admin/provision_devices.py @api --owner admin --days 30 --device DEVICE_ID --group GROUP_ID --wait-seconds 180
```

The first command explicitly enables tenant-wide automatic approval of new
reachable enrollments, with accountable owner and bounded approval duration.
It does not grant execution permission. The second waits for the named device,
enables it if needed, then unions the requested enabled groups with its current
membership using the latest revision. No arbitrary discovered device is silently
allocated to a group. For an already pending named device, the script re-reads its enrollment review
and submits an exact-key decision through the enrollment API before waiting. A
changed key or ambiguous identity stops without approval. Read-only polling
can wait for a not-yet-created endpoint; authorization failures are never retried.
Membership/enablement mutations are not automatically retried.

`import_workflow.py WORKFLOW.olo @api` orchestrates verified embedded package
uploads, nonconflicting reference import, workflow staging/review, provisioning,
graph preparation and readiness-gated publication. It refuses copied accounts,
workload credentials and device allocations. Destination service bindings are
created locally for referenced agents. It does not restart containers. This is
not yet a durable server-owned deployment operation; see the workflow status for
candidate-readiness isolation, cleanup and execution-finality limitations.
