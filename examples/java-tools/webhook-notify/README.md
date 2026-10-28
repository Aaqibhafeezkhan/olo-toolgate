<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Webhook notify

Tools: `notify.send`. Part of the [Java Tool SDK examples](../README.md).

Posts a notification (title, text, severity, source, reference) as JSON to `/hooks/<channel>` on the webhook the administrator approved: an internal alert relay, n8n, an incident tool or a chat bridge. Change the route path in the source to match your receiver.

## Safety

- The API key is route auth (`X-Api-Key` from the `WebhookApiKey` secret). Under ToolGate the broker adds it, so the code never sees it.
- The tool is `NON_IDEMPOTENT` with a business key: `reference`. A repeat with the same reference returns the first result instead of sending twice, and the reference goes upstream as `Idempotency-Key` so the receiver can deduplicate as well.
- Only channels in `AllowedChannels` can be posted to. Messages are capped at 16 KiB.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `WebhookHost` (destination) | none | Host of the webhook receiver. |
| `WebhookApiKey` (secret) | none | API key sent as `X-Api-Key`. |
| `AllowedChannels` | `ops-alerts` | Comma-separated channels callers may post to. |
| `Source` | `toolgate` | Sender name included in every message. |

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/webhook-notify-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Alert ops-alerts that /var on app-1 is 93% full; reference disk-app-1-2026-10-12."
