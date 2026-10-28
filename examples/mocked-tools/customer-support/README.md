<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Customer support (mocked)

Tools: `support.customer`, `support.order`, `support.refund-eligibility`, `support.create-ticket`. Part of the [mocked tool examples](../README.md).

An e-commerce support desk. `support.customer` returns the customer and their tier, `support.order` the items, tracking and delivery status. `support.refund-eligibility` applies the written refund policy (damaged or wrong items refunded without a return, orders 3 or more days late refundable, change-of-mind returns within 30 days, a 10% store credit for gold customers whose order is slightly late) and quotes the rule it used. `support.create-ticket` opens a ticket, once per `reference`.

Customers C-501 (gold) and C-502 (standard). Orders: O-9001 delivered, O-9002 late in transit, O-9003 outside the refund window, O-9004 arrived damaged. The store's "today" is fixed at 2026-10-12 so the answers never drift.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/customer-support-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Where is order O-9002? It was due last week."
- "Can C-502 return order O-9003?"
- "O-9004 arrived broken. Sort out a refund and open a ticket."
