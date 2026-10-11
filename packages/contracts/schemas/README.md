# ToolGate schemas contracts

Canonical wire definitions live in `packages/contracts/schemas/v1`. The frozen v2 set (design gate D1) is in [`v2`](v2/README.md); its bindings are generated into a separate `v2` namespace in every language. Bindings are
generated with `python tools/contracts/generate.py` and drift-checked in CI.
Validate canonical schemas at untrusted boundaries; bindings provide structural
models and serialization rather than a complete JSON Schema validator.

See [foundation workflow](../../../docs/development/foundation.md) for build,
tests, stable package identities, dependency modes, publishing and compatibility.
