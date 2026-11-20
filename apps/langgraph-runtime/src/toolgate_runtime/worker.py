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
import signal

from .run_transport import ControlRuns, GatewayRuntime, RunTransportError, WorkflowGateway
from .runner import CompletionUnconfirmed, Denied, OwnershipLost, Runner
from .observability import Observability
from .availability import Availability, Backoff


class WorkerPresence:
    """Renew capability presence during long runs without claiming another run."""
    def __init__(self,control,interval=5,on_ready=lambda:None,on_failed=lambda _:None,probe=lambda:None):
        self.control=control;self.interval=interval
        self.on_ready=on_ready;self.on_failed=on_failed;self.auth_required=False
        self.probe=probe
        self.stopped=threading.Event();self.failed=threading.Event();self.thread=None

    def start(self):
        self.probe();self.control.heartbeat()
        self.on_ready()
        def renew():
            while not self.stopped.wait(self.interval):
                try:self.probe();self.control.heartbeat();self.on_ready()
                except Exception as failure:
                    self.auth_required=getattr(failure,'auth_required',False)
                    self.failed.set();self.on_failed(self.auth_required);return
        self.thread=threading.Thread(target=renew,name='workflow-worker-presence',daemon=True)
        self.thread.start()

    def require_current(self):
        if self.failed.is_set():raise RunTransportError('Worker presence is unavailable',auth_required=self.auth_required)

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

    def __init__(self, adapter, current=lambda:None):
        self.adapter = adapter
        self.current=current
        self.authorize_step = adapter.authorize_step
        self.discover_agents = adapter.discover_agents
        self.discover_tools = adapter.discover_tools
        self.finish = adapter.finish
        self.inferences = []
        self.tools = []

    def infer(self, lease, grant, **kwargs):
        self.current()
        decision = self.adapter.infer(lease, grant, **kwargs)
        self.inferences.append({"subjectKind": grant.subject_kind, "subjectId": grant.subject_id})
        return decision

    def call_tool(self, lease, grant, tool_id, arguments, **kwargs):
        self.current()
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


def run_worker(control,gateway,observability,stop,status,*,once=False,result_directory=None,graph_directory=None):
    """Reconnect only admission/presence. An admitted run is never resumed or replayed."""
    backoff=Backoff()
    while not stop.is_set():
        presence=WorkerPresence(control,on_ready=lambda:status.set('READY'),
            on_failed=lambda auth:status.set('AUTH_REQUIRED' if auth else 'BACKOFF'),probe=gateway.probe)
        status.set('CONNECTING')
        failure=None
        try:
            presence.start()
            while not stop.is_set():
                presence.require_current()
                gateway.probe()
                lease=control.claim()
                # Reset backoff only after a successful authenticated claim poll.
                backoff.reset()
                if lease is not None:
                    evidence=ExecutionEvidence(WorkflowGateway(control,gateway),presence.require_current)
                    with observability.run(lease) as observation:
                        def progress(kind,run=lease,observer=observation,**details):
                            presence.require_current()
                            control.progress(run,kind,**details);observer.event(kind,**details)
                        outcome=Runner(evidence,graph_directory=graph_directory,progress=progress).execute(lease)
                        observation.finish(outcome.state)
                    if result_directory is not None:log_result(result_directory,lease,outcome)
                    print(json.dumps({'runId':lease.run_id,'workflowId':lease.workflow_id,
                        'state':outcome.state,'code':outcome.code,
                        'graph':{key:lease.graph[key] for key in ('packageId','version','digest','entryPoint')},
                        'execution':evidence.summary()}),flush=True)
                if once:return 0
                if lease is None:stop.wait(2)
        except (RunTransportError,CompletionUnconfirmed,OwnershipLost,OSError,ValueError) as error:
            failure=error
        finally:presence.close()
        if failure is not None:
            state='AUTH_REQUIRED' if getattr(failure,'auth_required',False) else 'BACKOFF'
            status.set(state)
            print(json.dumps({'workerState':state}),flush=True)
            if once:return 2
            stop.wait(backoff.next())
    status.set('STOPPED')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-origin", required=True)
    parser.add_argument("--gateway-origin", required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--control-ca-file")
    parser.add_argument("--gateway-ca-file")
    parser.add_argument("--once", action="store_true")
    parser.add_argument('--status-file',type=Path,help='Ephemeral local readiness diagnostics, never authority')
    parser.add_argument("--result-directory", type=Path, help="Explicitly log private terminal results in this directory")
    parser.add_argument("--graph-directory", type=Path, help="Read-only operator-installed graph packages")
    args = parser.parse_args(argv)

    def credential():
        with args.token_file.open(encoding="ascii") as stream:
            return stream.read(8193).strip()

    observability=None;stop=threading.Event();status=Availability(args.status_file)
    signal.signal(signal.SIGTERM,lambda *_:stop.set());signal.signal(signal.SIGINT,lambda *_:stop.set())
    try:
        observability=Observability.from_environment()
        control = ControlRuns(args.control_origin, credential, ca_file=args.control_ca_file,graph_directory=args.graph_directory)
        gateway = GatewayRuntime(args.gateway_origin, ca_file=args.gateway_ca_file)
        return run_worker(control,gateway,observability,stop,status,once=args.once,
            result_directory=args.result_directory,graph_directory=args.graph_directory)
    except KeyboardInterrupt:
        return 130
    except (RunTransportError, CompletionUnconfirmed, OwnershipLost, OSError, ValueError):
        print(json.dumps({"error": "WORKFLOW_WORKER_STOPPED"}), flush=True)
        return 2
    finally:
        if observability is not None:observability.close()


if __name__ == "__main__":
    raise SystemExit(main())
