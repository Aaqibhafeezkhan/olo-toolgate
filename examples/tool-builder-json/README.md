# JSON SDK Hello World

Open `toolgate.json` in Tool Builder's JSON SDK editor, save it in an authorized
Tool Group and build the saved revision. Download the standard `.tgpkg` and the
single canonical source-project ZIP.

The same source builds outside Builder:

```text
toolgate-json-sdk check .
toolgate-json-sdk pack .
```

Use the SDK distribution pinned by `sdkVersion`, a matching JDK/toolchain and the
verified ToolGate CLI. See [JSON SDK](../../tool-sdk/json-sdk/README.md).
Install the package and assign runtime access through normal governance before
invoking its `hello` tool. Its default input is `{"text":"World"}` and its
response is `{"message":"Hello World"}`.
