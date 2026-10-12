<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Endpoint check

Tools: `endpoints.check`. Part of the [Java Tool SDK examples](../README.md).

Calls a health endpoint on the service the administrator points `ServiceHost` at and reports up or down, the HTTP status, latency, and the status the service reports about itself: Spring Boot Actuator (`{"status":"UP"}`), health+json (`{"status":"pass"}`), or a short text body such as `ok`. A timeout, refused connection or 5xx is a *down* result, not a tool error, so a model can report it plainly. With `expectStatus` the endpoint counts as up only when it reports that status.

## Safety

- The tool reaches only `ServiceHost`, over HTTPS, on two declared route shapes (`/a` and `/a/b`) with plain-name segments.
- Answers are capped at 64 KiB and calls at 10 s.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `ServiceHost` (destination) | none | Host of the service, such as `orders.internal.example.com`. |

One deployment checks one service. Deploy the package again with another `ServiceHost` for each service you want to watch, or add more `@Destination`s and routes.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/endpoint-check-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Is the orders service up?"
- "Check actuator/health and tell me if it reports UP."
