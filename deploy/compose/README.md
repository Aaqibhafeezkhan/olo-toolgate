# Docker Compose deployments

- [QuickStart](QuickStart/README.md): one container, SQLite, vault, Tool Hosts and UI.
- [GatewayControl](GatewayControl/README.md): Gateway/Control, PostgreSQL and local HTTPS/identity.
- [QuickStart-WO-Password](QuickStart-WO-Password/README.md): isolated local demo with automatic admin entry.

Both use published Docker Hub images with batch and POSIX shell management scripts.
They have separate project names and volumes and can run together on default ports.

Run `manage.bat deploy` or `sh manage.sh deploy` in the selected folder. Supported
operations include deploy, update, undeploy, status and logs. GatewayControl asks
whether to deploy bundled dependencies (default Y); n configures an existing DB
and optional existing HTTPS proxy. Default `.env` settings are copied from the
checked-in `.env.example`; database credentials use ignored `env/.env.db`.
