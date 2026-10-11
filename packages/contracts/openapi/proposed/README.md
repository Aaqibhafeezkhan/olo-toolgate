# Proposed OpenAPI changes (design gate D1)

## Purpose

The HTTP operations decided at D1 that are not implemented yet. The served documents (`../control-v1.yaml`, `../gateway-v1.yaml`, `../quickstart-v1.yaml`) are what Control, the Gateway and the console use today; Control serves `control-v1.yaml` and the console generates its operations from it. Adding unimplemented operations there would advertise endpoints that do not exist, so they live here instead.

| File | Adds |
|---|---|
| `control-v1.proposed.yaml` | Package upload, versions, review and enable; deployment bindings and effective values; Tool Host registration; v2 bodies for `permits/consume` and `effects/report`; lease heartbeat; kill events, recoveries and break-glass |
| `gateway-v1.proposed.yaml` | Scoped MCP routes `/mcp/agent/{agent}`, `/mcp/agent-group/{group}`, `/mcp/agent-group/{group}/{agent}`; RFC 9728 metadata |
| `quickstart-v1.proposed.yaml` | Package upload with auto-enable |

Rules:
- Request and response bodies reference `../../schemas/v2/` only (errors keep the v1 `ErrorEnvelope`).
- When M1 implements an operation, the same PR moves it into the served file and deletes it here; the operation's shape does not change.
- An operation marked "Extends the existing endpoint" keeps the served v1 body working and adds the v2 body.
- `tests/contracts/test_contracts_v2.py` checks that no proposed operation is already served and that every schema reference resolves.
