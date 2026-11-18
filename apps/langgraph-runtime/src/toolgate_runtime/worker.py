# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Claim and execute ToolGate workflows using a mounted workload credential."""
import argparse
import json
import os
from pathlib import Path
import re
import time
import threading

from .run_transport import ControlRuns, GatewayRuntime, RunTransportError, WorkflowGateway
from .runner import CompletionUnconfirmed, Denied, OwnershipLost, Runner


class WorkerPresence:
    """Renew capability presence during long runs without claiming another run."""
    def __init__(self,control,interval=5):
        self.control=control;self.interval=interval
        self.stopped=threading.Event();self.failed=threading.Event();self.thread=None

    def start(self):
        self.control.heartbeat()
        def renew():
            while not self.stopped.wait(self.interval):
                try:self.control.heartbeat()
                except Exception:
                    self.failed.set();return
        self.thread=threading.Thread(target=renew,name='workflow-worker-presence',daemon=True)
        self.thread.start()

    def require_current(self):
        if self.failed.is_set():raise RunTransportError('Worker presence is unavailable')

    def close(self):
        self.stopped.set()
        if self.thread is not None:self.thread.join(timeout=11)


def log_result(directory, lease, outcome):
    """Explicit opt-in private content logging, after the server acknowledges completion."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", lease.run_id):
        raise ValueError("Invalid result filename")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = directory / (lease.run_id + ".json")
    with open(target, "x", encoding="utf-8", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
        json.dump({"runId": lease.run_id, "workflowId": lease.workflow_id, "state": outcome.state,
                   "result": outcome.result, "code": outcome.code,
                   "graph": {key: lease.graph[key] for key in ("packageId", "version", "digest", "entryPoint")}}, stream, allow_nan=False, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


class ExecutionEvidence:
    """Bounded adapter observations, without prompts, arguments, results or tokens.

    These observations describe this worker's calls, not independent server attestation.
    """

    def __init__(self, adapter):
        self.adapter = adapter
        self.authorize_step = adapter.authorize_step
        self.discover_agents = adapter.discover_agents
        self.discover_tools = adapter.discover_tools
        self.finish = adapter.finish
        self.inferences = []
        self.tools = []

    def infer(self, lease, grant, **kwargs):
        decision = self.adapter.infer(lease, grant, **kwargs)
        self.inferences.append({"subjectKind": grant.subject_kind, "subjectId": grant.subject_id})
        return decision

    def call_tool(self, lease, grant, tool_id, arguments, **kwargs):
        observation = {"subjectKind": grant.subject_kind, "subjectId": grant.subject_id,
                       "toolId": tool_id, "state": "OUTCOME_UNKNOWN"}
        self.tools.append(observation)
        try:
            outcome = self.adapter.call_tool(lease, grant, tool_id, arguments, **kwargs)
        except RunTransportError as failure:
            if failure.stage is not None:observation['failureStage']=failure.stage
            raise
        except Denied:
            observation["state"] = "DENIED"
            raise
        except Exception as failure:
            observation['failureType']=type(failure).__name__ if type(failure).__name__ in ('AttributeError','KeyError','TypeError','ValueError') else 'Unexpected'
            raise
        observation["state"] = outcome.state
        return outcome

    def summary(self):
        return {"inferences": self.inferences, "tools": self.tools}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-origin", required=True)
    parser.add_argument("--gateway-origin", required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--control-ca-file")
    parser.add_argument("--gateway-ca-file")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--result-directory", type=Path, help="Explicitly log private terminal results in this directory")
    parser.add_argument("--graph-directory", type=Path, help="Read-only operator-installed graph packages")
    args = parser.parse_args(argv)

    def credential():
        with args.token_file.open(encoding="ascii") as stream:
            return stream.read(8193).strip()

    presence=None
    try:
        control = ControlRuns(args.control_origin, credential, ca_file=args.control_ca_file,graph_directory=args.graph_directory)
        gateway = GatewayRuntime(args.gateway_origin, ca_file=args.gateway_ca_file)
        presence=WorkerPresence(control);presence.start()
        while True:
            presence.require_current()
            lease = control.claim()
            if lease is not None:
                evidence = ExecutionEvidence(WorkflowGateway(control, gateway))
                runner = Runner(evidence, graph_directory=args.graph_directory)
                outcome = runner.execute(lease)
                if args.result_directory is not None:
                    log_result(args.result_directory, lease, outcome)
                print(json.dumps({"runId": lease.run_id, "workflowId": lease.workflow_id,
                                  "state": outcome.state, "code": outcome.code,
                                  "graph": {key: lease.graph[key] for key in ("packageId", "version", "digest", "entryPoint")},
                                  "execution": evidence.summary()}), flush=True)
            if args.once:
                return 0
            if lease is None:
                time.sleep(2)
    except KeyboardInterrupt:
        return 130
    except (RunTransportError, CompletionUnconfirmed, OwnershipLost, OSError, ValueError):
        print(json.dumps({"error": "WORKFLOW_WORKER_STOPPED"}), flush=True)
        return 2
    finally:
        if presence is not None:presence.close()


if __name__ == "__main__":
    raise SystemExit(main())
