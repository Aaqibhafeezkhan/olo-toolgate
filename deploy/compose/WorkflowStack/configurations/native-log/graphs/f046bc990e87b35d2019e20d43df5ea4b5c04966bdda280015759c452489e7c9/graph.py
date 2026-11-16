# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""A native configurable async agent graph; no workflow switch in the worker."""
from typing import TypedDict
from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from toolgate_runtime.graph_api import RunContext


class State(TypedDict, total=False):
    message: str
    result: object


def build_graph(config):
    async def summarize(state: State, runtime: Runtime[RunContext]):
        result = await runtime.context.agents.invoke(config["agentId"], state["message"])
        return {"result": {config["resultKey"]: result} if config.get("resultKey") else result}

    builder = StateGraph(State, context_schema=RunContext)
    builder.add_node("summarize", summarize)
    builder.add_edge(START, "summarize")
    builder.add_edge("summarize", END)
    return builder
