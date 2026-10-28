# JSON Tool SDK

Author a tool as a `toolgate.json` project, compile it through the Java SDK and
produce the same `.tgpkg` package used by Java projects. JSON is an authoring
language; the host never interprets this file during an invocation.

Build the distribution from the repository:

```text
./gradlew :sdk-json:installDist
```

Add `tool-sdk/json-sdk/build/install/toolgate-json-sdk/bin` and the verified
`toolgate` CLI to PATH. JDK 17 or later is required. The SDK version in the
project must match the installed distribution. Install this distribution on
the build worker too; Builder must call `JsonSdk.stage/check/pack/sourceArchive`
through its worker, without its own source generator or packager.

```text
toolgate-json-sdk check ./my-tool
toolgate-json-sdk pack ./my-tool
toolgate-json-sdk source ./my-tool
toolgate-json-sdk bundle ./my-tool ./build/exports
```

The `toolgate check` and `toolgate pack` project commands also recognize
`toolgate.json` and delegate to the installed JSON SDK. `toolgate-json-sdk dev`
builds first and starts the generated SDK runtime in development mode. Only
server-eligible exports are discoverable in standalone mode. Use ToolHarness
or an authorized governed device runtime to test client declarations.

Example project (replace `sdkVersion` with your installed SDK version; the
repository series and channel are defined in [version.json](../../version.json)):

```json
{
  "schemaVersion": 1,
  "sdkVersion": "1.0.0-dev.1",
  "id": "acme.greetings",
  "name": "Greetings",
  "publisher": "acme",
  "version": "1.0.0",
  "tools": [{
    "name": "hello",
    "kind": "SERVER",
    "title": "Hello World",
    "description": "Greet someone",
    "inputs": {
      "text": {"type": "STRING", "description": "Who to greet", "default": "World", "maxLength": 128}
    },
    "output": {"message": {"concat": [{"literal": "Hello "}, {"input": "text"}]}},
    "events": [{"level": "info", "message": "Producing greeting"}, {"fraction": 1, "message": "Greeting complete"}]
  }]
}
```

The initial operation set is deliberately closed: bounded string inputs and
concatenation of declared inputs and literals. Declarations generate typed Java
handlers, descriptors, defaults and examples using the existing SDK annotation
processor. There are no arbitrary expressions, script fields, template
evaluation, imports, shell commands or implicit filesystem/network access.
Unsupported operations fail validation rather than selecting another runtime.

Every input with no default is required. When all inputs have defaults, the
compiler generates an SDK example and checks its expected output. `CLIENT`
tools are eligible only on client devices; `SERVER` tools may run on either
target when the runtime and invocation authority allow it. Eligibility never
grants runtime access.

The single source ZIP contains `toolgate.json`, README and `.gitignore`.
`bundle` creates both exports and a verified digest receipt in a fresh output
directory. Builder's worker invokes that same operation; the receipt is written
only after package verification succeeds.
Generated Java is an internal build artifact. The CLI and Builder worker use
the same compiler, exact runtime dependency set and canonical Rust packager.
Byte-for-byte reproducibility requires the same pinned SDK distribution,
JDK/toolchain and source project. SDK/runtime JARs and ZIP entries exclude build
timestamps and job paths; web-framework or worker dependencies are not bundled.

Run `:sdk-json:test` for declaration validation, source-injection rejection,
SDK defaults/examples and deterministic staging/source export checks.
