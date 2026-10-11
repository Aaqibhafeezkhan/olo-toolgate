<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Identity fixtures

## Purpose

The shared tokens for adapter A4 ([protocol-layer.md](../../../spec/protocol-layer.md) §3), used by case `official-sdk/a4-fixtures`. Every SDK runs the same files and must reach the same outcome for each token.

## Files

| File | Contents |
|---|---|
| [`jwks.json`](jwks.json) | Public keys `rs-1` (RSA 2048) and `es-1` (P-256) |
| [`jwks-rotated.json`](jwks-rotated.json) | The same keys plus `rs-2`, served after a refresh |
| [`tokens.json`](tokens.json) | `now`, `issuer`, `audience`, the allowlist and skew, each token with `expect` and `why`, and the introspection answers |

A verifier runs the tokens with its clock set to `now` (epoch seconds), the configured issuer and audience, and the allowlist `RS256,ES256`.

- `accept`: the token verifies against `jwks.json`, and the identity has `sub` `user-1` and `preferred_username` `fixture.user`.
- `reject`: it is refused.
- `refresh` (`unknown-kid`): it is refused while `jwks.json` is current and no refresh is due. Once 30 s have passed since the last fetch, the verifier fetches again, gets `jwks-rotated.json`, and accepts it.

The introspection answers map an opaque token (`opaque`) to the endpoint's response. Only `active` is accepted. `revoked` is `active: false`; the others are active but expired, or for another issuer or audience.

## Regenerating

```bash
python3 tool-sdk/conformance/fixtures/identity/generate.py
```

It needs the `cryptography` package. Each run makes new keys and throws the private halves away, so no signing key is ever committed and the output differs between runs. The tokens are fixture values for the `.invalid` issuer: they are not credentials for any system.
