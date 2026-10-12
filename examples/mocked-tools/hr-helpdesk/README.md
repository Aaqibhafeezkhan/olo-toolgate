<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# HR helpdesk (mocked)

Tools: `hr.employee`, `hr.policy`, `hr.my-leave`, `hr.request-leave`. Part of the [mocked tool examples](../README.md).

An HR helpdesk. `hr.employee` looks someone up in the directory. `hr.policy` answers a question from the policy handbook and cites the section. `hr.my-leave` returns the caller's leave balances and `hr.request-leave` books leave for the caller, counting working days and naming the approver.

It shows identity done properly: `my-leave` and `request-leave` act for the caller, whose employee id comes from the verified `employee_id` token claim (`@JwtClaim`), never from an argument, so nobody can read or book someone else's leave. In `toolgate dev` the claim comes from `toolgate.dev.json`; under ToolGate, from the caller's identity token. Without it the tools return `CLAIMS_UNAVAILABLE`.

Employees E-100 (engineering manager), E-101 and E-102 (engineers reporting to E-100), E-200 (HR partner).

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/hr-helpdesk-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "How much annual leave do I have left?"
- "Book me off from 2 to 6 November."
- "How much parental leave do we get, and who is my HR partner?"
