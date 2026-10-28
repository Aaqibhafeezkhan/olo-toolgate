# Local group-only Quickstart

Run `manage.ps1 deploy` on Windows or `sh manage.sh deploy` on Linux/macOS. The scripts retain the data volume, validate database/login/online authority options and use a local image without pulling when its name has no registry prefix. `undeploy` stops the stack and retains state.

Set `QUICKSTART_IMAGE` and ports in `.env`. The default backend is SQLite. To use an existing PostgreSQL database, set `TOOLGATE_QUICKSTART_DATABASE_MODE=postgresql` and supply `env/.env.db` using the example in QuickStart/env. Runtime and migration roles must differ. Production requires verified database TLS; local loopback evaluation is explicitly marked development mode. Authorization always requires current Control; there is no Redis authorization fallback or offline ALLOW cache.

Optional external Redis/Valkey directory caching is configured using the `TOOLGATE_CONTROL_CACHE_*` settings in `.env` and protected files under `env/`. See [shared cache configuration](../../../docs/deployment/shared-cache.md). Local caching remains the default.

The first installation creates protected defaults and three independent local management identities. Retrieve each private bootstrap password locally and rotate it at login. Tool and Agent definitions start disabled with zero runtime grants. Review the native device enrollment, then create complete group grants through independent configuration review. Password-free mode changes local login only; it creates no runtime grants.

See [the complete deployment, enrollment and recovery guide](../../../docs/deployment/quickstart.md). Keep existing data and custody through updates; use signed restore quarantine rather than copying an old database over current state.
