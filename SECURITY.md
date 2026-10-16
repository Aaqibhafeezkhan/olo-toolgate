# Security Policy

Managed tool execution uses a trusted registered device, current owner/device enablement, installed-client connection approval, and the existing agent/tool/action/resource filters before every protected effect. Registration, green availability or deployment never grants execution. Temporary suspension retains the protected key; permanent revocation cannot be undone through approval or recovery.

See [Device registry and tool-call controls](docs/control-plane/device-registry.md).

Managed local runtime tools execute only in a non-root, digest-pinned sandbox
without host mounts, credentials, network or child processes. Missing or unsupported
runtime capabilities fail closed. Keep the trusted local engine/kernel patched;
the sandbox shares that kernel. [Runtime security](docs/client/local-runtimes.md).

Security issues should **not** be reported through a public GitHub issue.

Device tools run only from signed packages in a sandbox, with enrolled readiness
and a fresh online Gateway grant on every call. The device files area rejects traversal, symlinks,
reparse points and hardlinks, with bounded closed inputs and atomic replacement.
Delete and arbitrary execution are absent. Downloading an anonymous native client
does not grant enrollment or runtime permission. See the
[custody and recovery guide](docs/client/hotfolder.md).

## Reporting a Vulnerability

Preferred:

1. Use GitHub's **Private Vulnerability Reporting** for `olo-labs/olo-toolgate` when enabled.
2. If private reporting is temporarily unavailable, open a minimal non-sensitive maintainer contact request without publishing exploit details.

Do not include secrets, customer information, or working exploit details in public Discussions/issues.

## Scope

Security-sensitive areas include:

- Gateway authorization bypass
- execution-permit forgery/replay
- endpoint-client privilege bypass
- package signature bypass
- Marketplace supply-chain compromise
- sandbox escape
- credential leakage
- Vault integration
- OAuth/OIDC
- SSRF
- path traversal
- command injection
- package installation/update
- key management

## Security Principles

- default deny
- deterministic authorization
- no LLM security decision
- fail closed
- separate trust-domain signing keys
- credentials never enter ordinary prompts/packages/logs
- untrusted code never executes inside the Control Plane or Drupal web process
- published package versions are immutable

## Responsible Disclosure

Please allow maintainers time to investigate and coordinate a fix before public disclosure.
