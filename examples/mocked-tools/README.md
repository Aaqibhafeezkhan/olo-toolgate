<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Mocked tool examples

Seven tool packages for high-demand business use cases, each returning fixed, fictional data. Use them to design and demo an agent flow (look up, decide, explain, act) before any real system is connected, to test prompts and approvals in ToolGate against predictable answers, or as the starting contract for a real integration. They are built with the ToolGate Java Tool SDK exactly like the [Java example tools](../java-tools/README.md), with unit tests, `@Example` self-tests and a fat-jar `.tgpkg` each.

**All people, accounts, records and numbers are made up. Nothing here is financial, medical, legal or insurance advice.**

| Example | Tools (MCP name) | Scenario | Shows |
|---|---|---|---|
| [bank-loan](bank-loan/README.md) | `loans.applicant`, `loans.credit-report`, `loans.evaluate`, `loans.offer`, `loans.record-decision` | Loan approval: APPROVE, REFER or DECLINE with reasons, rate, EMI and the largest amount that fits | Explainable rule-based decisions, recording an outcome once |
| [healthcare](healthcare/README.md) | `care.patient`, `care.medications`, `care.labs`, `care.interactions`, `care.slots`, `care.book` | Clinic assistant: patient summary, labs, drug interaction check, booking | `sensitive` inputs kept out of logs and audit |
| [server-recovery](server-recovery/README.md) | `incidents.incidents`, `incidents.host-status`, `incidents.crash-report`, `incidents.runbook`, `incidents.remediate`, `incidents.verify`, `incidents.update-incident` | A server crash: triage, root cause, runbook, restart or rollback, verify, resolve | An action tool that runs once per reference |
| [customer-support](customer-support/README.md) | `support.customer`, `support.order`, `support.refund-eligibility`, `support.create-ticket` | E-commerce support: order tracking, refund policy, tickets | Policy answers that quote the rule applied |
| [hr-helpdesk](hr-helpdesk/README.md) | `hr.employee`, `hr.policy`, `hr.my-leave`, `hr.request-leave` | HR: directory, policy answers with citations, leave balance and requests | Acting for the caller from a verified token claim (`@JwtClaim`) |
| [fraud-detection](fraud-detection/README.md) | `fraud.alerts`, `fraud.transaction`, `fraud.risk-score`, `fraud.freeze-card` | Card fraud: alerts, explainable risk score, freezing a card | Scores built from named rules an agent can explain |
| [insurance-claims](insurance-claims/README.md) | `claims.policy`, `claims.claim`, `claims.assess`, `claims.file-claim` | First notice of loss: cover check, payout estimate, filing | Business errors (`NOT_COVERED`) the model can act on |

The database example, which queries a real (embedded) database rather than returning fixed data, is [db-query](../java-tools/db-query/README.md) in the Java example tools.

## Build and try

As for the [Java example tools](../java-tools/README.md#build-and-test): publish the SDK and build the `toolgate` CLI once, then here:

```sh
./gradlew check toolgatePackage     # every example: tests, @Example self-tests, build/toolgate/<example>-0.1.0.tgpkg
cd fraud-detection
cp toolgate.dev.example.json toolgate.dev.json
toolgate dev                        # serve it over stdio to an MCP client
```

None of them need settings, network or files, so each `tool.jar` also runs as is from an MCP client with `java -jar tool.jar`. The exception is the HR example: its leave tools act for the caller, so standalone it starts only with an identity verifier (`TOOLGATE_AUTH_*`) or with `TOOLGATE_MODE=development` and an `employee_id` claim in `toolgate.dev.json` (the example file sets it to E-101).

## From mock to real

Each package keeps its fictional records in one nested `Data` class and its business rules in small, plain methods. To connect a real system, keep the tool names, parameters and outputs (the contract your agent and its prompts were built against) and replace what is behind them:

1. Replace the `Data` lookups with calls to your system: a declared `@HttpRoute` through `context.http()` for an API (the broker adds credentials under ToolGate), or a JDBC query as in [db-query](../java-tools/db-query/README.md).
2. Replace the rules (`loans.evaluate`, `fraud.risk-score`, `claims.assess`, refund eligibility) with calls to your decision engine, or keep them as a pre-check.
3. Keep the business keys (`reference`) on every tool that changes something, so a retried call acts once, and keep the declared error codes so the agent knows what went wrong.
4. Under ToolGate, require approval for the tools that act (`loans.record-decision`, `incidents.remediate`, `fraud.freeze-card`, `care.book`) and grant them only to the groups that may use them.
