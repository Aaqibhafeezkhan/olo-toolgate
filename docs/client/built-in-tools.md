# Built-In Tools

## Purpose

Quickstart ships at least 10 safe tools: HotFolder operations, calculator, text transform, JSON validation, SHA-256, system info, plus optional configured web search.

Server tools use [registered device controls](../control-plane/device-registry.md).
`local-builtins` gates the fixed executor, including its HotFolder adapter.
HotFolder additionally checks `local-hotfolder` and authorizes with that device's
own runtime credential, allowing independent device filters. Disabling HotFolder
leaves compute available. Every effect still needs fresh Gateway authorization
and any required ASK permit; green readiness and registration do not grant access.

See [Run Your First Tool](../getting-started/first-tool.md) for the initial setup and calculator walkthrough.

## Validate JSON

Choose `json.validate` from the built-in tool catalog. Its only argument is
`text`, a string containing the JSON document to parse. It checks JSON syntax;
it does not validate the document against a JSON Schema.

Valid input:

```json
{
  "text": "{\"name\":\"Amina\",\"active\":true}"
}
```

Expected output:

```json
{
  "valid": true
}
```

Malformed input:

```json
{
  "text": "{\"name\":\"Amina\",}"
}
```

Expected result: the call is rejected with the safe `VALIDATION` error code.
The client does not expose parser details in the error response.

These examples demonstrate input handling only. The caller still needs the
appropriate tool access and current authorization; installing or discovering a
tool does not grant permission to run it.

## Self-healing tool selection

Installed authorization profiles describe the client executable and carry no grants.
At service start the client keeps an administrator's built-in tool selection working:

- Profiles exported by another client build (after an update or reinstall) are replaced
  with this build's own, so the service starts and reports matching tools.
- The selection (HotFolder settings and built-in tool IDs) is remembered in
  `local-tools.json` in the device state directory. A gateway whose tool settings were
  cleared by **Switch gateway** or **Repair gateway connection** gets it back, pointed at
  that gateway and its CA, with a fresh inert local token and no web-search secret.
  Before anything is remembered, a selection on a parked gateway profile is adopted.

Nothing is restored when the HotFolder directory no longer exists. To disable the
selection, remove the tool settings and `local-tools.json`. If healing fails, the
service starts with its configuration unchanged.
