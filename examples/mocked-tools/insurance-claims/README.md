<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Insurance claims (mocked)

Tools: `claims.policy`, `claims.claim`, `claims.assess`, `claims.file-claim`. Part of the [mocked tool examples](../README.md).

A first-notice-of-loss desk. `claims.policy` returns the cover, limit and deductible. `claims.claim` returns a claim's status and what is missing. `claims.assess` checks a loss against the policy (cover dates, covered and excluded perils), estimates the payout as the loss up to the limit less the deductible, lists the documents needed, and says whether it can be fast-tracked (payout of 5,000 or less). `claims.file-claim` files the claim, once per `reference`, and refuses a loss the policy does not cover with `NOT_COVERED` and the reason.

Policies: POL-2001 home (storm, fire, theft, burst pipes; flood excluded), POL-2002 motor (collision, theft, glass), POL-2003 a home policy that lapsed on 2026-06-30. Claim CLM-3001 is in review on POL-2002, waiting for a repair estimate.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/insurance-claims-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "A storm took tiles off my roof on 1 October; repairs are about 4,200. Am I covered on POL-2001?"
- "What's happening with claim CLM-3001?"
- "My basement flooded. Can I claim on POL-2001?"
