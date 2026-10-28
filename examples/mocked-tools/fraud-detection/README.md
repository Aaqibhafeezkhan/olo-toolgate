<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Fraud detection (mocked)

Tools: `fraud.alerts`, `fraud.transaction`, `fraud.risk-score`, `fraud.freeze-card`. Part of the [mocked tool examples](../README.md).

A card-fraud analyst's desk. `fraud.alerts` lists transactions at or above a risk score, riskiest first. `fraud.transaction` returns the details. `fraud.risk-score` scores a transaction from named rules and says which fired, so the agent can explain the decision. `fraud.freeze-card` freezes the card and opens a case, once per `reference`.

| Rule | Points |
|---|---|
| Amount 10x or more the customer's average (`AMOUNT_SPIKE`) | 30 |
| Amount 3x or more (`AMOUNT_ABOVE_USUAL`) | 15 |
| Outside the customer's home country | 25 |
| Device not seen before | 20 |
| 3 or more card transactions in the previous 10 minutes | 25 |
| Gift cards, crypto or money transfer | 25 |

The score is capped at 100. 90 and over is CRITICAL (freeze the card), 70 HIGH (hold), 40 MEDIUM (ask the customer to confirm), otherwise LOW.

Account ACC-77 normally spends in Portugal: T-1 is a usual grocery payment (0), T-2 a large foreign electronics purchase from a new device (75), T-3 one of a burst of gift card purchases (100).

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/fraud-detection-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Which transactions need review right now?"
- "Why was T-2 flagged? Should we block it?"
- "Freeze the card for T-3 and give me the customer message."
