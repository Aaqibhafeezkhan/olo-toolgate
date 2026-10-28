<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Bank loan approval (mocked)

Tools: `loans.applicant`, `loans.credit-report`, `loans.evaluate`, `loans.offer`, `loans.record-decision`. Part of the [mocked tool examples](../README.md).

A retail loan desk. `loans.applicant` and `loans.credit-report` return the applicant's profile and bureau summary. `loans.evaluate` applies written underwriting rules (credit score, recent defaults, debt-to-income including the new instalment, employment history) and returns APPROVE, REFER or DECLINE with every reason, the rate for the score band, the instalment, and the largest amount that would keep debt-to-income at 40%. `loans.offer` builds the EMI schedule. `loans.record-decision` records the outcome once per `reference`.

| Applicant | Situation | Typical outcome |
|---|---|---|
| A-1001 | Salaried, score 782, low debt | APPROVE |
| A-1002 | Score 668, high existing debt | REFER, or DECLINE for a large amount |
| A-1003 | Score 541, recent default | DECLINE |
| A-1004 | Self-employed, under a year in business | REFER |

The rules are deliberately simple and live in one method, so they are easy to read and to replace with your own credit policy.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/bank-loan-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Can we approve A-1001 for 25,000 over 36 months? Explain the decision."
- "A-1002 wants 20,000. What is the most we could lend them at 36 months?"
- "Show me the first three EMIs for A-1001's offer and record the approval for case 77."
