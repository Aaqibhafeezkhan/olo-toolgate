# ADR-028: Python SDK follows the Java invocation runtime

**Status:** Accepted.

The partial Python SDK in PR 143 implemented declarations and shared descriptor
vectors, with an unwired port of Java's protocol primitives. Java is the primary
behavioral reference for completing the Python SDK.

Python uses the ToolGate-owned 2026-07-28 core, Tasks, request-state, identity and
transport adapters with Java's observable behavior. This extends A1's ownership
in the protocol-layer specification to Python. It avoids introducing different
error, task or invocation semantics while finishing the existing implementation.
The official MCP Python SDK is not a runtime dependency of this implementation.
The historical official-SDK compatibility record remains unchanged: it records
tests of those official versions, not proof of this runtime's conformance.

Python packages carry the same generation-1 semantic descriptors and v2 package
manifest. Their runtime id is `python`, entry point is `toolgate.runtime`, and
their single artifact is a deterministic `tool.pyz`. The artifact includes the
SDK, tool sources, generated index and any explicitly hash-locked dependencies.
It extracts into a private temporary directory to support native wheel modules.
Native wheels require a matching target platform and interpreter.

Standalone, test and governed invocation share one pipeline. Python coroutines
are an implementation choice independent of protocol Task declarations. A
non-cooperative synchronous handler retains its concurrency slot until it stops;
its timed-out result is abandoned. Governed executors additionally enforce the
process deadline. No unsafe thread termination is used.

Governed Python uses ADR 027's stdio broker, with no alternate credential or
network channel. The Tool Host has a configured Python interpreter; managed
devices use a digest-pinned approved Python image and fixed launch vector.
Control releases Python packages only when an approved Python base is configured.
Existing Java eligibility, authorization, signatures and isolation rules apply.

Legacy MCP session support and the Tool Host's existing transport/isolation gaps
remain separately tracked. Adapter retirement requires the same observable
behavior and conformance evidence; source similarity alone is not acceptance.
