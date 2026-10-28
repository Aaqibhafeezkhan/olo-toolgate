# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The protocol layer (tool-sdk/spec/protocol-layer.md): the ToolGate MCP 2026-07-28 core and its adapters.

A1 is :class:`ProtocolCore`, A2 :class:`Tasks`, A3 :class:`RequestStates` and A4 the identity verifiers. Python
uses this ToolGate core for now; the official Python MCP SDK takes its place once it ships the 2026-07-28 core.
"""

from .core import TASKS, VERSION, Connection, ProtocolCore, RequestMeta, ToolCatalog, error, http_status
from .errors import ProtocolError
from .identity import IdentityVerifier, Rejected, VerifiedIdentity
from .request_states import RequestStates
from .stdio import StdioServer
from .tasks import MemoryTaskStore, TaskRecord, Tasks, TaskStatus, TaskStore

__all__ = ["TASKS", "VERSION", "Connection", "ProtocolCore", "RequestMeta", "ToolCatalog", "error", "http_status",
           "ProtocolError", "IdentityVerifier", "Rejected", "VerifiedIdentity", "RequestStates", "StdioServer",
           "MemoryTaskStore", "TaskRecord", "Tasks", "TaskStatus", "TaskStore"]
