# Python Hello World

This is the Python equivalent of the adjacent Java `hello-world` package. Both
export `client_greet` and `server_greet`, accept `{"text":"Ada"}` (default
`World`, maximum 128 characters), return `{"message":"Hello Ada"}`, and emit
the same log and progress messages.

From the repository root:

```text
python -m pip install -e tool-sdk/python-sdk
toolgate check core-mcp/hello-world-python
toolgate pack core-mcp/hello-world-python
```

The shared core build also builds, checks and validates this package. Quickstart
ships it alongside Java Hello World through the same fresh deployment profile.
With the same grants, binding and arguments, change only the MCP tool name from
`ololabs.hello-world/server_greet` to
`ololabs.hello-world-python/server_greet`. The reply remains `Hello Ada`.

`tools/quickstart/check.py` tests both names through a real Gateway, Control and
Tool Host. `tools/client/runtime_check.py` tests both SDKs under the managed-device
container sandbox. These gates use isolated test resources.

```text
python tools/quickstart/check.py --image YOUR_SOURCE_BUILT_QUICKSTART --hello-world-only
python tools/client/runtime_check.py --cli /absolute/path/to/toolgate
```

The focused Gateway gate writes `build/quickstart/hello-world-smoke.json`; the
device gate writes `build/client/runtime-smoke.json`. The full enterprise smoke
suite remains available by omitting `--hello-world-only`.
