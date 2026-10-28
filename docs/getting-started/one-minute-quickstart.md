# Run Quickstart locally

Quickstart combines Control, Gateway, the console and local execution in one
container, with SQLite by default. It is a single-node evaluation deployment.
Docker is required; a source build also needs the [development prerequisites](../development/README.md).

## Start the deployment

Use the [Quickstart Compose directory](../../deploy/compose/QuickStart/README.md).
Set `QUICKSTART_IMAGE` and the desired ports in its `.env` using the checked-in
examples. Choose an image from your release or build and verify a local image with
`python tools/quickstart/check.py --build`.

From `deploy/compose/QuickStart`, run:

```powershell
.\manage.ps1 deploy
```

On Linux/macOS:

```sh
sh manage.sh deploy
```

The helper retains the data volume. Use its configured console URL and retrieve
the generated administrator credential from the container's private
`/data/bootstrap-password` or `/data/credentials.json` file. Do not copy credentials
into issues or logs. See [first login](first-login.md).

## Establish access

Fresh installation creates protected default groups and local management
identities. Tools and the local Agent begin disabled with zero runtime rights.
An explicit initialization file can install reviewed demo configuration; those
demo grants are not universal defaults.

Review a native device's enrollment and fingerprint, approve its connection, and
separately configure the required group grants and execution binding. Enable only
the intended identities/tools. Follow [your first tool](first-tool.md) for the
authorization path. Device approval never creates an Agent credential.

## Preserve state

Keep `/data` through container replacement. It holds configuration, identity,
audit, credentials and local data. Use the helper's `undeploy` action to stop while
retaining state. Do not delete the volume to troubleshoot an approval failure.

Read [configuration, TLS enrollment and reviewed recovery](../deployment/quickstart.md)
before changing custody or restoring a backup. Use
[production preparation](../deployment/production.md) for separate services.
