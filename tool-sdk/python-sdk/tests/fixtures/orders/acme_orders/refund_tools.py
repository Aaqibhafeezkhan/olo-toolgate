# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Descriptor vector tool-03: non-ASCII names and UTF-16 key order. Mirrors the Java processor fixture RefundTools.

``€amount`` is not a Python identifier and Python folds ``Ａlpha`` to ``Alpha`` (NFKC), so both take their
property names from Param.
"""

from dataclasses import dataclass
from enum import Enum
from typing import Annotated, Any, Dict

from toolgate import (EffectSafety, ErrorDecl, KeyNamespace, Param, Secret, ToolContext, ValueType, idempotency,
                      reconcile, server_tool)


class Reason(Enum):
    DAMAGED = "DAMAGED"
    LATE = "LATE"
    ÉCHANGE = "ÉCHANGE"


@dataclass
class Refund:
    zeta: bool
    état: str
    amount: Annotated[int, Param(name="€amount", description="", type=ValueType.LONG, min="0")]
    note: Annotated[str, Param(name="😀note", description="")]
    alpha: Annotated[str, Param(name="Ａlpha")]


@Secret(name="AcmeClient", type=ValueType.OBJECT)
class RefundTools:
    @server_tool(name="orders.refund", title="Refund an order",
                 description="Refunds an order in full. Café orders are refunded to the original card; the amount is"
                             " in €.",
                 effect=EffectSafety.NON_IDEMPOTENT, destructive=True, async_=True, stateless=True, max_concurrency=4,
                 routes="orders.refund", business_key_namespace=KeyNamespace.TENANT,
                 business_key_argument="refundKey",
                 errors=[ErrorDecl("PSP_BUSY", "The payment provider asked us to retry", retryable=True),
                         ErrorDecl("ALREADY_REFUNDED", "The order was already refunded")],
                 toolsets="payments")
    @idempotency(header="Idempotency-Key")
    async def refund(self, order_id: Annotated[str, Param(name="orderId", description="Order number",
                                                          pattern="^[0-9]{6,12}$")],
                     reason: Annotated[Reason, Param(description="Reason shown to the customer")],
                     refund_key: Annotated[str, Param(name="refundKey", description="Business key for this refund",
                                                      min_length=8, max_length=64)],
                     *, context: ToolContext) -> Refund:
        return await context.http.route("orders.refund").path_var("orderId", order_id) \
            .body({"reason": reason}).send_async(Refund)

    @server_tool(name="orders.refund-status", title="Check a refund", description="Reconciles an uncertain refund.",
                 effect=EffectSafety.READ_ONLY)
    @reconcile(tool="orders.refund")
    def refund_status(self, order_id: Annotated[str, Param(name="orderId", description="Order number")]
                      ) -> Dict[str, Any]:
        return {}
