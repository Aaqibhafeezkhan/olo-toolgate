# Docker Compose deployments

- [WorkflowStack](WorkflowStack/README.md): complete profile-selected workflows, two-agent text summarization, host inbox/archive/output folders, and monitoring.

- [QuickStart](QuickStart/README.md): one container, SQLite, vault, Tool Hosts and UI.
- [GatewayControl](GatewayControl/README.md): separate Gateway/Control and an HTTPS proxy, consuming externally provisioned PostgreSQL, identity and key custody.

QuickStart and GatewayControl use Docker Hub image references with batch,
PowerShell and POSIX shell management scripts. They have separate project names
and can run together on default ports. Select matching reviewed image versions;
image references alone do not establish runtime readiness.

Run `manage.bat deploy` or `sh manage.sh deploy` in the selected folder. Supported
operations include deploy, update, undeploy, status and logs. QuickStart is the
self-contained local evaluation option; password-free login is an explicit setting
in its `.env`. Its optional external database credentials use ignored `env/.env.db`.
GatewayControl requires `env/control.env` and protected `custody/control`,
`custody/gateway` and `custody/proxy` inputs before deployment. It does not provision
a database, identity provider or private keys and offers no dependency-selection
prompt. Follow its README before running the launcher. Both launchers copy default
image/port settings from `.env.example` when `.env` is absent.
