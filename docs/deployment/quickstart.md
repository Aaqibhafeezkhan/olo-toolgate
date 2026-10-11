# Quickstart deployment and recovery

Quickstart runs Control, Gateway, the console and a real native executor in one non-HA local evaluation image. SQLite is the default; external PostgreSQL is supported with separate migration/runtime roles. All execution requires current Control. There is no local policy ALLOW or Redis authorization fallback.

Build and verify with `python tools/quickstart/check.py --build`. Use `--image IMAGE`, `--postgresql`, `--no-browser` or `--client-assets VERIFIED_DIRECTORY` as needed. The isolated gate records installation, real native CSR/mTLS, verified disabled-user intake, independently reviewed grants, HUMAN/SERVICE/ASK effects, exact retry, revocation, reviewed import/shadow, restart and browser accessibility. Small-fixture latency is measured in `build/quickstart/smoke.json`; it is not an enterprise throughput promise.

Run the image with a persistent `/data` volume, read-only root, dropped capabilities, no-new-privileges, a restricted `/tmp` and loopback ports 8080/8443. The verified gate uses 2 CPUs and 1536 MiB. [Compose helpers](../../deploy/compose/QuickStart/README.md) retain the volume through update/undeploy. For this repository's persistent debug environment, run `debug/start.bat`; it validates the candidate before replacing the debug container.

First boot installs protected default groups, separate local admin/reviewer-1/reviewer-2 identities and zero runtime rights. Tools and the local Agent begin disabled. Retrieve each bootstrap password privately from its `/data/bootstrap-password*` file and rotate it on first login. Password-free local mode changes login only. The new native Device appears pending: review its code/key, approve its connection, then independently review group grants and definitions. Device approval creates no Agent credential. Keep Agent configuration outside the diagnostic mimic script, which only calls and logs bounded output.

The first setup user (`admin`) cannot be deleted or disabled, even after another
Super Admin is assigned. Its initial email is `admin@admin.com`; set
`TOOLGATE_ADMIN_EMAIL` on the container before first boot to override it. Restarts
and upgrades never apply this variable again or change an existing email. Edit
the user's email in the console afterward; its stable `admin` login is unchanged.
Super Admin authority comes from an enabled Management Role with `SUPER_ADMIN`
classification on an enabled Team. Any enabled member receives the same management
privileges; other roles retain their editable rules and runtime grants.

For human access, enable the verified User and supply a complete Team/Human Role grant for Tool Group, action, bound Device Group, resources and conditions. SERVICE additionally needs explicit workload binding, Agent Group capability and SERVICE grant. Delegated execution additionally requires a verified human session and matching Team/Agent Group delegation. Management Roles do not create runtime rights.

Optional native downloads require a verified asset manifest included at image build. Unsigned Windows installers are refused. Keep private configuration and data outside Git; do not mount host home directories, Docker sockets or arbitrary host shells into this evaluation image.

Use the stopped supervisor's offline backup command under the exclusive volume lock. Restore requires an independent empty destination, exact manifest, two external reviewer signatures, separately pinned trust, live revision/epoch/sequence/session/credential floors and post-backup retirement evidence. It quarantines old authority and retains spent nonces/audit/history. Re-establish only reviewed management recovery, rotate credentials and review intended runtime grants before reopening traffic. See [the signed restore and migration runbook](../enterprise-access-control/operations.md) for SQLite and PostgreSQL. Copying an old database over live state is not a supported rollback.
