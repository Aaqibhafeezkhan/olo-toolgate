# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""The current supervisor is an installed graph, selected through the generic loader."""
from typing import TypedDict
from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from toolgate_runtime.graph_api import RunContext


class State(TypedDict, total=False):
    message: str
    phase: str
    active_agent: str | None
    supervisor_messages: list
    agent_messages: list
    supervisor_context_loaded: bool
    agent_context_loaded: bool
    outcome: None
    done: bool
    result: object


def build_graph(config):
    def advance(state: State, runtime: Runtime[RunContext]):
        if "phase" not in state:
            state = {**state, "phase": "supervisor", "active_agent": None,
                "supervisor_messages": [{"role": "user", "content": state["message"]}],
                "agent_messages": [], "supervisor_context_loaded": False,
                "agent_context_loaded": False, "outcome": None}
        update = runtime.context.agents._advance(state)
        outcome = update.pop("outcome", None)
        if outcome is not None:
            result = outcome.result
            if config.get("resultKey"):
                result = {config["resultKey"]: result}
            update.update(done=True, result=result)
        return {**state, **update}

    builder = StateGraph(State, context_schema=RunContext)
    builder.add_node("advance", advance)
    builder.add_edge(START, "advance")
    builder.add_conditional_edges("advance", lambda state: END if state.get("done") else "advance", ["advance", END])
    return builder
