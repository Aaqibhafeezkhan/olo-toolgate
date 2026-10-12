# Marketplace end-to-end flow for the example tools

Status: plan, not implemented. This walks one real package from PR #89 from a developer's laptop to a production call, step by step, and then lists what each of the other example packages needs at the same steps. Design detail and reasons are in [long-term-plan.md](long-term-plan.md).

Running example: **`examples.notify`** (Webhook notify, tool `notify.send`). It is the most complete case because it needs a destination, a secret, globals and a business key. The other packages follow the same steps with less to configure.

Who is involved:
- **Developer**: a community member publishing the tool.
- **Moderator**: OLO Labs staff reviewing submissions on the website.
- **Admin**: a ToolGate Super Admin in an organization that wants to use the tool.
- **Agent**: an AI agent calling the tool through the ToolGate Gateway.

```text
DEVELOPER                 WEBSITE (Drupal) + MARKETPLACE API/WORKER           TOOLGATE (Control, Tool Host, Gateway)
 1 build + pack  ───────►
 2 sign up, 2FA, publisher
 3 upload .tgpkg ───────► 4 automatic checks, sandbox self-tests
                          5 moderator approves
                          6 signed, immutable release; listed
                                                     7 admin finds it ───────► 8 import: download by digest, verify
                                                                               9 deploy: values, secret, Tool Host
                                                                              10 enable (review) + grant
                                                                              11 agent calls notify.send via /mcp
 12 new version ────────► checks, review, sign ──────────────────────────────► 13 update offered, re-review if needed
                          14 advisory / revoke ───────────────────────────────► 15 alert, one-click suspend
```

## Part A: publish (developer and website)

### Step 1. Build and pack (exists today)

```sh
cd examples/java-tools/webhook-notify
./gradlew check            # unit tests and every @Example
toolgate pack              # build/toolgate/webhook-notify-0.1.0.tgpkg
toolgate validate build/toolgate/webhook-notify-0.1.0.tgpkg
```

The `.tgpkg` holds the fat jar, `toolgate-package.json`, one descriptor per tool, `tests/self-tests.json` and `CHECKSUMS.sha256`. Its package digest is the SHA-256 of `CHECKSUMS.sha256`; that digest identifies these exact bytes from here to production.

**New work:** the Java Gradle plugin also writes `marketplace/listing.json` from `@ToolPackage` (name, summary, publisher, license, categories, links) plus a `setup` block listing what an admin must provide. For `examples.notify` the generated `setup` is:

| Kind | Name | Notes |
|---|---|---|
| Destination | `WebhookHost` | host of the webhook receiver; the only egress the tool can use |
| Secret | `WebhookApiKey` | sent as `X-Api-Key` by the broker; the tool code never sees it |
| Global | `AllowedChannels` | default `ops-alerts` |
| Global | `Source` | default `toolgate` |
| Effect | `notify.send` is `NON_IDEMPOTENT` with business key `reference` | shown as "sends messages; repeats with the same reference are deduplicated" |

### Step 2. Account and publisher (website)

1. Sign up at the Marketplace website, verify the email, turn on 2FA (required to publish).
2. Create a publisher. Its slug is the package id prefix, so `examples.notify` belongs to the publisher `examples`.
   - For the PR #89 packages, `examples` is a reserved, verified publisher owned by OLO Labs. A community developer publishing their own copy uses their own prefix, for example `acme.notify`.

### Step 3. Upload

On **My packages → New version**, drop the `.tgpkg` (or later, `toolgate publish build/toolgate/webhook-notify-0.1.0.tgpkg --token …`).
- The browser uploads straight to quarantine storage through a presigned URL. Drupal never holds the file.
- The submission page opens and shows each check as it runs.

### Step 4. Automatic checks (Marketplace Worker)

| Check | For `examples.notify` |
|---|---|
| Archive, checksums, descriptors, digests: the same rules Control applies on upload | pass |
| Id prefix `examples` matches the publisher; `0.1.0` not already published | pass |
| Runtime Control can run today (Java server tool) | pass: one fat jar, `@ServerTool` |
| Listing complete (summary, SPDX license, `setup`) | pass |
| Secret scan, malware scan, SBOM and vulnerability scan | pass |
| Sandbox self-test: `java -jar` with an empty environment and **no network**, list tools (must equal the descriptor: `notify.send`), run `tests/self-tests.json` (the `@Example` cases, which use recorded exchanges such as `exchanges/sent.json`, so no network is needed) | pass |
| API diff and security delta against the previous version | first version, so everything is new: egress to `WebhookHost`, secret `WebhookApiKey`, a non-idempotent effect |

Any failure stops here with a reason on the submission page; the developer fixes it and uploads a new version.

### Step 5. Moderation

A first version always goes to a moderator. The review screen shows, in plain words: "Sends HTTP POST to one admin-configured host. Uses one secret as an API header. Non-idempotent; deduplicated by `reference`." Plus scan and self-test output. The moderator approves (or rejects with a reason).

### Step 6. Signed release and listing

- The Worker asks KMS to sign a `MarketplaceRelease` over `{packageId: examples.notify, version: 0.1.0, packageDigest, descriptorSetDigest, publisher: examples}`.
- The package moves to write-once storage at `releases/<digest>.tgpkg`; the version becomes `PUBLISHED` and can never point at other bytes.
- The package page goes live with the README, the tool table, **What this tool needs** (the `setup` table above), the security summary, versions, and a **Use in ToolGate** box showing id, version and digest, plus a download of `.tgpkg` with its `.release.json`.

## Part B: pull into ToolGate and run in production (admin)

### Step 7. Find it

- MK1: on the website, search "webhook", open `examples.notify`, read **What this tool needs**, download the `.tgpkg` and `.release.json`.
- MK2: in the ToolGate console, **Packages → Marketplace**, search, open the same detail, click **Import 0.1.0**.

### Step 8. Import (Control)

1. Fetch `releases/<digest>.tgpkg` and the release (MK2), or take the two uploaded files (MK1).
2. Check that SHA-256 of `CHECKSUMS.sha256` equals the release's `packageDigest`.
3. Verify the release signature against the Marketplace key pinned in Control settings.
4. Refuse if the version is yanked or revoked.
5. Run the existing `POST /api/control/v1/packages/uploads` path: archive rules, digests, sealed storage, state `VERIFIED`, recording source `marketplace`, publisher `examples` (verified).

From here Control holds its own copy. Production never calls the Marketplace again for this version.

**New work:** steps 2 to 4 and the source record in Control's upload; the console Marketplace page in MK2.

### Step 9. Deploy (exists today: `POST /api/control/v1/packages/deployments`, console Server packages page)

The admin fills the form generated from the package:

| Field | Example value |
|---|---|
| `WebhookHost` | `alerts.internal.example.com` (becomes the only route target in the service profile) |
| `AllowedChannels` | `ops-alerts,db-oncall` |
| `Source` | `toolgate-prod` |
| Secret `WebhookApiKey` | bound to a stored secret (Vault in enterprise) |
| Tool Hosts | the hosts in the network zone that can reach the receiver (none = every Tool Host) |

Control writes the service profile, the globals document, and `notify.send` into the directory as a **disabled** tool. Tool Hosts fetch the package from Control by digest and verify it before mounting.

### Step 10. Enable and grant (exists today)

1. **Enable** `notify.send`: a reviewed change that shows the security delta (new egress host, new secret, non-idempotent effect). Quickstart auto-approves for the admin's own agents; enterprise uses maker/checker.
2. **Grant** it to the Agent Group that should use it, for example "Ops agents".
3. The catalog revision bumps and connected MCP clients get `tools/list_changed`.

### Step 11. Production call

```text
Agent (MCP client) ── tools/call notify.send {channel: "ops-alerts", title, text, reference: "disk-app-1-2026-10-12"}
  └► Gateway /mcp: authenticate agent, policy, grant check, ASK if policy says so
     └► Control: permit for the Tool Host, business-key reservation on `reference`
        └► Tool Host: verify permit and package digest, run the jar in the sandbox
           └► broker: POST https://alerts.internal.example.com/hooks/ops-alerts
                      adds X-Api-Key from the secret, Idempotency-Key = reference
  ◄─ result to the agent; audit record with package digest, tool, agent, outcome
```

A retry with the same `reference` returns the first result instead of posting twice.

## Part C: keep it healthy

### Step 12–13. New version

The developer publishes `0.2.0` the same way (steps 1 to 6). If it adds an egress host or secret, the security delta is non-empty and a moderator reviews it again. Control's poll (every 15 minutes) shows **Update available** in the console with the API diff and security delta; the admin imports it (step 8) and deploys it; a non-empty security delta needs the enable review again. The previous version stays available for rollback.

### Step 14–15. Advisory or revocation

A moderator publishes a signed advisory, or revokes `0.1.0`. On its next poll Control verifies the advisory signature, raises a console alert and an audit event, and offers **Suspend** (kill switch). New imports of a revoked version are refused. Whether Control suspends it automatically is decision 4 in plan.md.

## What each example package needs at steps 9 and 10

All 51 tools are `@ServerTool`, so they run on a Tool Host. Tools that read the local machine (health, disk, processes, files, scripts) report on **the Tool Host they are placed on**, so the admin picks the target hosts in step 9 deliberately.

| Package | Admin provides at deploy | Review notes |
|---|---|---|
| `examples.notify` | `WebhookHost`, secret `WebhookApiKey`, `AllowedChannels`, `Source` | egress, secret, non-idempotent |
| `examples.endpoints` | `ServiceHost` | egress to one host |
| `examples.downloads` | `DownloadHost`, `DownloadDir`, `PublicBaseUrl` | egress, writes files |
| `examples.db` | `DatabaseFile`, `DbUser`, secret `DbPassword`, `AllowedTables`, `MaxRows`, `QueryTimeoutSeconds` | read-only queries |
| `examples.scripts` | `ScriptsDir`, `AllowedScripts`, shell paths, `MaxRunSeconds` | runs admin-placed scripts |
| `examples.cleanup` | `CleanupRoots`, `MinAgeDays`, `MaxDeletesPerCall` | deletes files; off until configured, dry run by default |
| `examples.csv` | `DataDir`, `MaxFileMegabytes` | reads files |
| `examples.disk` | `AllowedRoots`, `MaxFilesVisited` | reads file metadata |
| `examples.health` | warn and critical thresholds, `DiskPaths` (defaults work) | read-only |
| `examples.processes` | nothing | read-only |
| log-insights | **source missing on main** (see note) | n/a |
| `examples.loans`, `.support`, `.fraud`, `.care`, `.claims`, `.incidents` | nothing (fixed fictional data) | demo only; listing marks them "mocked" |
| `examples.hr` | nothing, but callers need a verified `employee_id` JWT claim mapped in Control | needs claim mapping |

**Note:** on main, `examples/java-tools/log-insights` has only its README and build files; its Java sources were not committed, most likely because the root `.gitignore` ignores `logs/` and the source package path contains it. It needs to be re-added (and the ignore rule narrowed) before it can be published.

## Build order to make this flow work

1. **MK0:** listing generation in the Java plugin; `MarketplaceRelease` v2 and Marketplace OpenAPI; threat model.
2. **MK1:** Marketplace API and Worker (steps 3 to 6); Drupal site (steps 2, 3, 5, 6, 7 on the website); Control verifies `.release.json` on upload (step 8 via manual download); publish the 17 working examples as `examples`. **Exit test:** publish `examples.notify` on the site, approve it, download, upload to Quickstart, deploy with a test receiver, enable, grant and call `notify.send` through `/mcp`.
3. **MK2:** console Marketplace page and one-click import, update and advisory polling, `toolgate publish`.
