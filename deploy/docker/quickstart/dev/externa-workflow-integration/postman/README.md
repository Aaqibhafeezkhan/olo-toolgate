<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Postman: external workflow integration

Import [the collection](olo-workflow-v1.postman_collection.json) and
[the environment template](quickstart-local.postman_environment.json), then select
that environment in Postman. This collection targets the V1 implementation being
developed on `feature/workflow-application-integration`. Existing published images
may not implement its external login or ordinary-user authorization yet.

1. Set `baseUrl` to your installation origin and copy `tenantId` from its console.
   The example UUID belongs to one installation; replace it for another tenant.
   Use verified HTTPS remotely. The default loopback HTTP origin is for local
   evaluation. Do not disable TLS verification to reach a remote installation.
2. Enter the existing enabled OLO user's `username` and `password` as private
   local environment values. Their roles/grants must explicitly permit the bot
   and all required agents, models, tools, devices and resources. Administration
   alone does not supply execution permissions.
3. Send **1. Login**. It sends the tenant header and password once and saves the
   returned user/tenant-bound `accessToken` in the selected environment. Other
   requests use Bearer authorization without a separate tenant header. Login
   again after expiry; V1 does not require OAuth or a refresh-token endpoint.
4. Use **2** and **2a** to inspect the accessible publication/catalog. Set
   `profileId`, `profileDigest` (publication `digest`), `expectedGeneration`
   (publication `generation`), `workflowId` and an entry-eligible `entryAgentId`.
   Check that the publication is ready and choose the intended bot explicitly.
5. Set `message` to input appropriate for that bot and send **3. Submit**.
   It creates an idempotency key if blank and saves the admitted `runId`.
   This request can cause the bot's configured effects: review the bot and input
   before sending. Do not run the entire collection repeatedly as a polling loop.
6. Send **4** for new progress; its script advances `progressCursor`. Send **5**
   for state and final response. Poll these reads about once per second while
   queued/running. Admission (`201`) is not completion; inspect the final state
   and `outcome`. Approval, failure, cancellation and expiry are not success.

Retain the same `idempotencyKey` and body if a submission's response is lost.
Clear the key only for a new logical submission. A changed body with the old key
must conflict; a new key can start another run. The submission script serializes the message safely as JSON, including quotes,
backslashes and newlines, and validates that generation is a positive JSON number.

The environment template contains no password or access token. Do not commit,
share or export populated credentials, authorization headers or private responses.
If collection discovery is denied, review permissions; do not switch to a shared
administrator credential to bypass user authorization.

Use **6** for durable events and **7** for a live workflow stream. **8–11**
create, verify, inspect and revoke callbacks. **12–13** observe permitted trigger
status; **14** revokes the user's current login sessions. Streams reconnect after
at most 60 seconds; save the numeric event cursor. Callback registration uses
`webhookAfter`, separately from polling cursors, so polling does not skip callbacks.
Keep the webhook idempotency key and expiry unchanged on create retries.

See the [API guide](../../../../../../docs/api/workflow-applications.md) for exact
permissions, request contracts, retention, signatures, expiry and transport limits,
and the [all-bot matrix](../../../../../../bot/PERMISSIONS.md) for resource setup.

## Example callback receiver

`webhook_receiver.py` is a standard-library sample. Put its private registration
file and SQLite deduplication state outside the repository. After registration,
write a private JSON file mapping the returned subscription ID to:

```json
{"<subscription-id>":{"signingSecret":"<private-returned-secret>","tenantId":"<your-tenant-uuid>"}}
```

Run `python webhook_receiver.py --help` for file arguments. It listens only on
`127.0.0.1:8787`; expose its `/events` path through your reviewed public HTTPS
ingress, add that exact hostname to the installation callback allowlist, and set
`callbackUrl` accordingly. The sample does not open ports or provision ingress.
Protect private files with owner-only permissions. Configure the receiver before
sending **9. Verify**. It validates signatures, freshness, tenant/event identity
and ownership challenges and durably deduplicates notifications before ACK.
It performs no workflow business effects and does not fetch private results.
Use the result endpoint with current Bearer authorization for the bot's response.

Subscriptions expire no later than the login token. Renew through a fresh login
and a newly verified registration using the last acknowledged cursor. Do not
export populated Postman environments or commit receiver secrets/state.
See the [installation guide](../../README.md#invoke-a-bot-from-another-application)
for deployment prerequisites.
