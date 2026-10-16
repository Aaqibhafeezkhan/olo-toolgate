# Quickstart composition

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited.

See [Device registry and tool-call controls](../../docs/control-plane/device-registry.md).

One non-HA Linux image contains the real Control JVM, Rust Gateway, two Tool Hosts
(direct dispatch and pull mode) and the embedded console. It runs tools only from
verified packages; it has no built-in tools. SQLite and private generated keys live
under `/data`; no PostgreSQL, external identity provider or Docker socket is
required for its primary path.

Start with the [user guide](../../docs/getting-started/one-minute-quickstart.md).
Build, configure, debug, upgrade and recover using the
[operations guide](../../docs/deployment/quickstart.md). The accepted boundary
and storage exception are in [ADR 012a](../../docs/adr/012a-single-node-quickstart.md).

`python tools/quickstart/check.py --build` exercises the real production image,
including the browser. The SQLite store has JVM integration tests; Linux image
identity and backup tests live in `tests/quickstart`. Publication uses the
protected `quickstart-release` CI environment and the exact tested image.
