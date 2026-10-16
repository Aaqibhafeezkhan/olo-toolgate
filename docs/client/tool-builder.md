# Tool Builder and JSON SDK packages

Tool Builder at `/console/#builder` edits canonical JSON SDK projects. Its worker
uses `tool-sdk/json-sdk`, which generates Java SDK source, processes the SDK
annotations, checks examples, stages the standard runtime and invokes the
canonical Rust `.tgpkg` packager. The source file is authoring data, not a runtime
registration. See [JSON SDK](../../tool-sdk/json-sdk/README.md) and
[the example project](../../examples/tool-builder-json/README.md).

## Author and build

1. Select **Add project** and choose the Tool Group that governs its authors.
2. Edit the JSON SDK project. The example accepts `text` with default `World`
   and responds with `Hello <text>`. The initial supported operations are
   bounded strings and concatenation of literals/declared inputs.
3. Save the project. Builds always pin the saved revision and canonical source
   digest, even if someone edits the draft while a build is queued.
4. Select **Build package**. The queue reports queued, compiling/checking,
   ready or a safe failure code.
5. Download **package** (`.tgpkg`) and **source** (the one canonical JSON SDK
   project ZIP). The exported source rebuilds with the same SDK distribution,
   JDK and CLI. Generated Java is internal to compilation.

Successful package versions are immutable. Increase the project's `version`
before changing the contents of an already built version. Optimistic revisions
and idempotency keys protect authoring/build requests from stale updates and
accidental duplicate submissions.

`tool_builder.read` permits viewing projects/builds and downloading their
verified artifacts within the authorized Tool Group. Edits and builds require
both read and `tool_builder.build` authority. Control resolves current roles and
group membership for each request and rechecks authoring authority before
claiming a build and publishing its result. Its minimal authoring-context list
does not require permission to list unrelated Tool Group membership data.
The UI reports a missing worker and disables builds until deployment configures it.
Revocation during compilation prevents publication. The setup-login and fallback
Super Admin protections continue to use the central permission resolver.

## Install and test

Use normal package import, assignment, activation and access grants to install
the built package. Build permission does not confer invocation authority.
`CLIENT` declarations are eligible only on client devices; `SERVER` declarations
may run on hosts and supported client devices. Runtime capability and permit
checks still apply. Runtime testing belongs to the installed tool's governed
Test action, not the SDK compilation queue.

## Configure the worker

Deployment supplies the fixed SDK distribution, JDK, canonical CLI and a private
data directory. Configure these Quarkus settings (or their normal uppercase
underscore environment equivalents):

```properties
toolgate.control.builder.enabled=true
toolgate.control.builder.java-executable=/opt/java/openjdk/bin/java
toolgate.control.builder.sdk-libraries=/opt/toolgate/json-sdk/lib
toolgate.control.builder.cli-executable=/usr/local/bin/toolgate
toolgate.control.builder.data-directory=/data/builder
```

The Java executable must be a JDK, since compilation runs in the child process.
Runtime-only Control installations leave the worker disabled until a deployment
supplies that toolchain. Authors cannot select executables, classpaths, compiler
options or runtime images in a request.

The adapter supports the closed JSON SDK language. It launches a new process
with a clean environment and its own private workspace, fixed entry point,
512 MiB heap, bounded diagnostics and a two-minute deadline. It verifies the
package and source ZIP before accepting outputs. The web process never compiles
or executes author code. Adding a future SDK that accepts arbitrary source
requires its reviewed build isolation adapter; this adapter is not an arbitrary
Java/script build service.

Build snapshots and immutable version metadata are tenant-transactional with
audit and replay records. Artifacts are addressed and rechecked by digest in
`<data-directory>/artifacts`; include that directory with the database backup.
Build rows may be pruned seven days after their lease expires when queuing new
builds; immutable version identities remain. Restore the database and artifact
directory together and verify the pinned toolchain/trust configuration before
resuming work.

## Verification

`JsonSdkTest` checks source validation, defaults, examples, deterministic staging
and source export. `SdkBuilderTest` checks pinned revisions, role revocation,
version immutability and verified downloads. `:control-plane:sdkWorkerTest`, with
`TOOLGATE_CLI` set, performs a real build through the worker and directly through
the SDK and compares package/source bytes. The same test checks timeout cleanup.
