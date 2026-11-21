# Local group-only Quickstart

Run `manage.ps1 deploy` on Windows or `sh manage.sh deploy` on Linux/macOS. The scripts retain the data volume, validate database/login/online authority options and use a local image without pulling when its name has no registry prefix. `undeploy` stops the stack and retains state.

Set `QUICKSTART_IMAGE` and ports in `.env`. The default backend is SQLite. To use an existing PostgreSQL database, set `TOOLGATE_QUICKSTART_DATABASE_MODE=postgresql` and supply `env/.env.db` using the example in QuickStart/env. Runtime and migration roles must differ. Production requires verified database TLS; local loopback evaluation is explicitly marked development mode. Authorization always requires current Control; there is no Redis authorization fallback or offline ALLOW cache.

## Explicit password-free local demo

QuickStart also supplies the password-free demo option. Copy `.env.example` to
`.env`, set `TOOLGATE_DISABLE_ADMIN_PASSWORD=true`, then run `manage.bat deploy`
on Windows or `sh manage.sh deploy`. Keep both published ports bound to loopback
as supplied. This changes local login only; it creates no runtime grants.
Set the value back to `false` and deploy again to require passwords.
The launcher checks that the image implements the selected login mode.

The separate `QuickStart-WO-Password` deployment has been removed. Existing stores
are not moved, converted or deleted by this consolidation. QuickStart uses its own
project and data volume; preserve any separately held demo data before explicitly
removing that old installation.

## Authority and recovery

Optional external Redis/Valkey directory caching is configured using the `TOOLGATE_CONTROL_CACHE_*` settings in `.env` and protected files under `env/`. See [shared cache configuration](../../../docs/deployment/shared-cache.md). Local caching remains the default.

The first installation creates protected defaults and three independent local management identities. Retrieve each private bootstrap password locally and rotate it at login. Tool and Agent definitions start disabled with zero runtime grants. Review the native device enrollment, then create complete group grants through independent configuration review. Password-free mode changes local login only; it creates no runtime grants.

See [the complete deployment, enrollment and recovery guide](../../../docs/deployment/quickstart.md). Keep existing data and custody through updates; use signed restore quarantine rather than copying an old database over current state.
