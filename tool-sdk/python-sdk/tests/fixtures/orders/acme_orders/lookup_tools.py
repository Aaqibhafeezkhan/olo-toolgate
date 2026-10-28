# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Descriptor vector tool-02. Mirrors the Java processor fixture LookupTools."""

from dataclasses import dataclass
from enum import Enum
from typing import Annotated, List

from toolgate import (EffectSafety, ErrorDecl, Global, JwtClaim, Param, Secret, ToolContext, ValueType,
                      server_tool)


class Status(Enum):
    OPEN = "OPEN"
    SHIPPED = "SHIPPED"
    CANCELLED = "CANCELLED"


@dataclass
class Item:
    sku: str
    quantity: int


@dataclass
class Order:
    orderId: str
    status: Status
    items: List[Item]


@Global(name="TimeoutSeconds", type=ValueType.INT, default_value="10")
@Secret(name="ApiKey")
@JwtClaim(name="UserName", claim="preferred_username")
class LookupTools:
    @server_tool(name="orders.lookup", title="Look up an order", description="Returns status and items for one order.",
                 use_when="The user asks about a specific order number.", effect=EffectSafety.READ_ONLY,
                 routes="orders.get", errors=[ErrorDecl("ORDER_NOT_FOUND", "No such order")])
    def lookup(self, order_id: Annotated[str, Param(name="orderId", description="Order number",
                                                    pattern="^[0-9]{6,12}$")],
               since: Annotated[int | None, Param(description="Only events after this epoch second",
                                                  required=False)] = None,
               *, context: ToolContext) -> Order:
        return context.http.route("orders.get").path_var("orderId", order_id).query("since", since).send(Order)
