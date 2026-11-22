<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Five bot use cases and multitenant acceptance

This plan contains remaining deployment and end-to-end acceptance. Current setup
and behavior belong in each linked bot guide and the
[multitenant operator guide](deploy/docker/multitenant/dev/README.md). All are
standard configurations under `bot/configurations`, with ordinary build/import
and startup-menu paths. There is no recording-only bot format.

## 1. Incident handover

Bot: [tenant-incident-triage](bot/configurations/tenant-incident-triage/README.md).
Two agents classify a supplied incident and combine the triage and original text
into a factual handover. The bot does not repair a service.

- [ ] Run the supplied incident on matching deployed artifacts and verify
  `triage.severity`, `triage.assessment`, `summary` and both agent stages.
- [ ] Correlate the real result, published revision and model attempts. Do not
  describe an incident's historical operator action as an effect of this bot.

Acceptance: both governed stages finish with attributable input-based results;
incident text cannot confer permissions or select another tenant.

## 2. Governed device file processing

Bot: [device-file-processing](bot/configurations/device-file-processing/README.md).
An existing device watcher selects a workflow that reads an approved input,
obtains a model proposal, writes a summary and archives the unchanged source.

- [ ] Provision the destination client, native tools, declared groups, model
  custody and owned directories; verify watcher readiness.
- [ ] Run the supplied fixture and inspect actual summary/archive files and the
  associated device/run/tool identities.
- [ ] Deny out-of-scope access and verify absence of protected effects.

Acceptance: authorized effects occur only on the declared device and paths.
Device approval and a model proposal never replace current effect authority.

## 3. Authority enforcement

Bot: [governed-file-processing](bot/configurations/governed-file-processing/README.md).
This is a separately scoped file-processing bot. Existing administration changes
an ordinary user's grants; prompt text does not choose authorization.

- [ ] Complete a manual baseline as an ordinary enabled user with the full HUMAN
  ceiling plus independent service/effect authority.
- [ ] Inspect its terminal outcome and deliberately reset the fixture. Remove one
  required grant and submit a fresh manual request as the same user.
- [ ] Show refusal and no new protected output, source move or private disclosure.
  A tenant Super Admin is unsuitable for the ordinary missing-grant test.

Acceptance: removing required authority stops the same configured action before
its protected effect. This does not establish comprehensive prompt-injection
resistance or add a human-approval mechanism.

## 4. Publication switching

Bot configurations: [publication-switch-a](bot/configurations/publication-switch-a/README.md)
and [publication-switch-b](bot/configurations/publication-switch-b/README.md).
Both share `publication-switch` and are published sequentially. A/B are alternative
revisions, not independently active bot IDs.

- [ ] Publish A, verify its watcher, run its fixture and inspect the terminal result.
- [ ] Import/review/prepare/publish B, verify its assignment and run its distinct
  input/output paths with the same runtime image.
- [ ] Verify each run pins the intended publication. Record actual assignment
  behavior without inferring watcher drain or in-flight continuation.

Acceptance: both runs finish under their intended publications without a runtime
rebuild. This does not establish failover, restart continuation or exactly-once effects.

## 5. Customized-bot portability

Bot: [portable-summary](bot/configurations/portable-summary/README.md).
Rehearse this scenario first: the actual saved customization is the central
cross-environment assertion. Incident triage can use the same transfer path.

- [ ] Customize and publish the prompt in Quickstart, run the fixture and verify
  the declared customization marker.
- [ ] Export the intended `.olo`; inspect its saved prompt/workflow and retain
  the immutable archive digest.
- [ ] Import those same bytes independently into both destination tenants; bind
  local model custody/authority, prepare/publish and run the fixture.
- [ ] Verify saved customization, concurrent tenant-owned results, independent
  updates and denial of foreign state/secret/result access.

Acceptance: the actual user-customized export runs in both destinations under
their own authority. Starter-bundle import, structural validation or a checksum
alone does not satisfy this requirement. Live model wording need not be identical.

## Three-model acceptance for all five use cases

All six configurations above declare the active order **QLoRA in Ollama →
lightweight Qwen in Ollama → premium OpenRouter**, in agent groups and the
workflow ceiling. Requests use candidates only as needed, not all three on every
inference. Both scoped model secrets, approved HTTPS origins and suitable local
and public-provider trust are destination prerequisites. See [models](docs/help/models.md).

- [ ] Verify destination model IDs, availability, licensing and required structured
  output before describing a local model as open source.
- [ ] Execute primary success, an eligible first-provider failure advancing to
  the second local model, and eligible failures of both local models advancing
  to premium. Retain actual attempt/outcome evidence without credentials.
- [ ] Verify missing premium custody blocks activation; permission/configuration
  denial stops inference instead of causing escalation.
- [ ] Verify inference fallback does not replay completed or uncertain tool effects.
  Do not claim quality-based fallback or unimplemented token/cost trace attributes.

## Shared destination and delivery

- [ ] Supply reviewed tenant initialization, JWT identity, TLS, signing/vault
  custody and per-tenant workload credentials under
  `deploy/docker/multitenant/dev/credentials`. The wrapper does not generate
  identities or host Quickstart's password endpoint.
- [ ] Verify console ports A `18444` / B `19444` and device TLS ports `18443` /
  `19443`, using distinct registered hostnames. Qualify hosted DNS/certificates
  separately when switching to real subdomains.
- [ ] Run matching-source application/tenant runtime, PostgreSQL, recovery,
  contract and component gates. Retain source/image/bot identities and actual
  execution results in PR/CI artifacts, not progress tables in this plan.
- [ ] Complete the five end-to-end scenarios before claiming deployment qualification.

Use fresh current formats only. Do not add compatibility aliases, schema
conversion or automatic replay of unknown effects to make a demonstration pass.
Record missing capabilities and failed assertions directly.

## Additional positioning using existing primitives

The [upstream MCP proxy](docs/gateway/mcp-proxy.md) governs a tested HTTPS server
through reviewed tools, grants and audit. Qualify actual client/upstream/auth
combinations before naming them publicly. Its menu choice provides setup guidance,
not an invented bot or an automatic upstream registration.

Existing [log triage](bot/configurations/log-triage/README.md) is a scoped
investigation use case. It reads input and writes output; verify source/config
write isolation. Restricted writes do not prevent disclosure through authorized
reads, model requests, results or telemetry. Cloud processing requires an explicit
data-handling review. Neither use case needs another required video.
