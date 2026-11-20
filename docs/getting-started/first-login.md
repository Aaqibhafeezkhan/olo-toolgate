# First login

The [community Quickstart](../../deploy/docker/quickstart/dev/README.md) starts
with one human `admin` account and no imported bot. Open
http://127.0.0.1:18090/console/ and read its private generated login files under
`deploy/docker/quickstart/dev/credentials/admin/`:

```powershell
Get-Content .\credentials\admin\username.txt
Get-Content .\credentials\admin\password.txt
```

Run those commands from the launcher directory. There is no universal password.
Do not attach credentials to issues or logs. Normal restarts preserve the account;
follow the deployment's credential-reconciliation instructions after a password change.

Import a bot and configure its model credentials, owned device enrollment and
group requirements. Management access does not grant tool execution. Review
fingerprints, enable intended identities, and configure complete grants and
execution bindings before invoking tools.

The separate [ToolGate-only Quickstart](../../deploy/compose/QuickStart/README.md)
can create multiple local management identities; its credential paths and login
options differ. Production uses [external identity](../deployment/production.md).
