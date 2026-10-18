# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The Java HelloWorld contract, with only the package identity changed."""
from dataclasses import dataclass
from typing import Annotated

from toolgate import (EffectSafety, Param, ToolContext, client_tool, example,
                      server_tool, tool_package)

tool_package(id="ololabs.hello-world-python", name="Hello World", publisher="ololabs")


@dataclass
class Greeting:
    message: Annotated[str, Param(description="The greeting")]


def greet(context: ToolContext, text: str) -> Greeting:
    context.log.info("Producing greeting")
    response = Greeting("Hello " + text)
    context.progress(1, "Greeting complete")
    return response


@client_tool(name="client_greet", title="Hello World on a device",
             description="Greet someone on your selected client device",
             effect=EffectSafety.READ_ONLY, stateless=True)
@example(arguments={}, expected_output={"message": "Hello World"})
def client(text: Annotated[str, Param(description="Who to greet", max_length=128)] = "World",
           *, context: ToolContext) -> Greeting:
    return greet(context, text)


@server_tool(name="server_greet", title="Hello World",
             description="Greet someone on a Tool Host or supported client device",
             effect=EffectSafety.READ_ONLY, stateless=True)
@example(arguments={}, expected_output={"message": "Hello World"})
def server(text: Annotated[str, Param(description="Who to greet", max_length=128)] = "World",
           *, context: ToolContext) -> Greeting:
    return greet(context, text)
