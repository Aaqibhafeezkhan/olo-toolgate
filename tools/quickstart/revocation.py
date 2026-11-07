# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Owned actual Control/Gateway/Tool Host revocation observations.

The fixture's long-lived read-only Java starter is never a production package.
These observations qualify their declared healthy HOST_NATIVE composition;
they do not establish OCI containment, native endpoint or A4 teardown bounds.
"""
import concurrent.futures
import json
import secrets
import subprocess
import time
import ssl
import urllib.error

TOOL = "acme.orders/greet"
HOST = "tool-host-remote"


def verify(api, ok, tokens, mcp, exec_text, run, container, report, operator, operator_port, grace_ms=None, deploy=None):
    observations = []
    host_id = HOST
    generation = 0
    report["revocation"] = dict(passed=False, observations=observations)
    def observe(value):
        observations.append(value)
        print("PASS S22 "+json.dumps(value,sort_keys=True),flush=True)
    prefix = "/api/control/v1/"
    admin = tokens["admin"]

    def until(action, seconds, reason):
        deadline = time.monotonic() + seconds
        while True:
            result = action()
            if result:
                return result
            if time.monotonic() >= deadline:
                raise AssertionError(reason)
            time.sleep(.025)

    def workers():
        code = """import json,os,pathlib,sys
result=[]
for entry in pathlib.Path('/proc').glob('[0-9]*'):
 try:
  cwd=os.readlink(entry/'cwd')
  if cwd.startswith('/data/'+('tool-host' if sys.argv[1]=='tool-host-local' else sys.argv[1])+'/work/i/'):
   stat=(entry/'stat').read_text().rsplit(')',1)[1].split()
   result.append(dict(pid=int(entry.name),startTicks=stat[19]))
 except (OSError,ProcessLookupError):pass
print(json.dumps(result))
"""
        return json.loads(exec_text("/opt/quickstart-python/bin/python", "-c", code, host_id))

    def execution():
        code = """import sys,json
sys.path.insert(0,'/opt/quickstart')
import supervisor as s
s.TENANT=json.loads((s.DATA/'state/tenant.json').read_text())['tenantId']
with s.Database() as db:
 rows=db.execute("SELECT i.invocation_id,i.expires_at,l.expires_at FROM control_enterprise_invocations i JOIN control_execution_leases l ON l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id JOIN control_invocation_kill_scopes k ON k.tenant_id=i.tenant_id AND k.operation_id=i.invocation_id AND k.attempt_id=l.fencing_token WHERE i.tenant_id=? AND k.tool_id=? AND k.device_id=? AND i.state='EXECUTING' ORDER BY i.created_at DESC LIMIT 1",(s.TENANT,'acme.orders/greet',sys.argv[1])).fetchall()
print(json.dumps([list(row) for row in rows]))
"""
        rows = json.loads(exec_text("/opt/quickstart-python/bin/python", "-c", code, host_id))
        return rows[0] if rows else None

    def invocation_state(invocation_id):
        code = """import sys,json
sys.path.insert(0,'/opt/quickstart')
import supervisor as s
s.TENANT=json.loads((s.DATA/'state/tenant.json').read_text())['tenantId']
with s.Database() as db:
 row=db.execute("SELECT state FROM control_enterprise_invocations WHERE tenant_id=? AND invocation_id=?",(s.TENANT,sys.argv[1])).fetchone()
assert row is not None
print(row[0])
"""
        return exec_text("/opt/quickstart-python/bin/python","-c",code,invocation_id)

    def apply(kind, body):
        path = "kill-events" + ("/recoveries" if kind == "RECOVERY" else "")
        proposal = ok(api(prefix + path, admin, body, "POST"), 202)
        assert api(prefix + "kill-events/proposals/" + proposal["id"] + "/decisions", admin,
                   dict(expectedRevision=proposal["revision"], approve=True), "POST")[0] == 403
        for reviewer in ("reviewer-1", "reviewer-2"):
            before = time.monotonic()
            proposal = ok(api(prefix + "kill-events/proposals/" + proposal["id"] + "/decisions",
                              tokens[reviewer], dict(expectedRevision=proposal["revision"], approve=True), "POST"))
        assert proposal["state"] == "APPLIED", proposal
        # Starting before the final approval conservatively includes commit and reply latency.
        return proposal, before

    def kill(mode):
        return apply("KILL", dict(scope=dict(kind="PACKAGE", value="acme.orders"),
                                  mode=mode, reason="Owned S22 runtime qualification"))

    def recover(proposal):
        return apply("RECOVERY", dict(supersedes=[proposal["eventId"]],
                                     reason="Owned S22 verified recovery"))

    def denied(committed):
        before = time.monotonic()
        status, body = mcp("tools/call", dict(name=TOOL, arguments=dict(name="must-not-run")))
        assert status in (200, 403, 404), (status, body)
        assert status != 200 or "error" in body or body.get("result", {}).get("isError") is True, body
        elapsed = time.monotonic() - before
        since_commit = time.monotonic() - committed
        assert since_commit <= 1, ("New work refusal exceeded one second from final approval", since_commit)
        return dict(requestSeconds=elapsed, sinceApprovalSeconds=since_commit)

    def receipt(push_host=None):
        state = ok(api(prefix + "kill-events/status", admin))
        epoch = state["state"]["authorizationEpoch"]
        # Exercise the real Gateway catalog/ack path before observing receipt.
        ok(mcp("tools/list"))
        def current():
            value = ok(api(prefix + "kill-events/status", admin))
            host = next((c for c in value["components"] if c["kind"] == "TOOL_HOST" and c["componentId"] == host_id), None)
            gateway = [c for c in value["components"] if c["kind"] == "GATEWAY"]
            pushed = next((c for c in value["components"] if c["kind"]=="TOOL_HOST"
                           and c["componentId"]==push_host),None) if push_host else None
            push_ok = push_host is None or (pushed and pushed.get("authorizationEpoch",-1)>=epoch
                                            and pushed.get("deliveryPath")=="PUSH")
            return value if host and host.get("authorizationEpoch", -1) >= epoch and gateway and all(
                c.get("authorizationEpoch", -1) >= epoch for c in gateway) and push_ok else None
        return until(current, 10, "Actual host/Gateway did not acknowledge committed epoch")

    def link(mode, target=None):
        nonlocal generation
        generation += 1
        value = dict(version=1, generation=generation, mode=mode, hostId=target or host_id)
        code = "import sys;sys.path.insert(0,'/opt/quickstart');import supervisor as s;s.atomic(s.DATA/'s22-link/mode.json',sys.argv[1])"
        exec_text("/opt/quickstart-python/bin/python", "-c", code, json.dumps(value))
        return generation

    def stats():
        return json.loads(exec_text("/opt/quickstart-python/bin/python", "-c",
            "import pathlib;print(pathlib.Path('/data/s22-link/stats.json').read_text())"))["events"]

    def remaining_bound(accepted):
        remaining=max(0,(accepted[2]-int(time.time()*1000))/1000)
        assert 0 < remaining <= 30, accepted
        return min(remaining, grace_ms/1000) if grace_ms is not None else remaining

    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    def start(marker):
        assert not workers(), "Unexpected pre-existing worker in owned host"
        future = pool.submit(mcp, "tools/call", dict(name=TOOL, arguments=dict(name=marker)), secrets.token_hex(16))
        owned = until(workers, 15, "Actual Java starter never started")
        accepted = until(execution, 5, "No actual executing invocation/live lease")
        return future, owned, accepted

    def gone(owned):
        return not any(item in workers() for item in owned)

    try:
        # STOP_NEW preserves already consumed work while removing discovery and new authority.
        future, owned, accepted = start("__s22_short__")
        proposal, committed = kill("STOP_NEW")
        refusal = denied(committed)
        listed = ok(mcp("tools/list"))["result"]["tools"]
        assert all(tool["name"] != TOOL for tool in listed)
        assert not gone(owned), "STOP_NEW terminated consumed running work"
        status, result = future.result(timeout=15)
        assert status == 200, result
        reply=result["result"]
        outcome=reply.get("_meta",{}).get("io.ololabs.toolgate/operationOutcome",{})
        if reply.get("isError"):
            assert outcome.get("state") == "OUTCOME_UNKNOWN", result
        else:
            assert reply["structuredContent"] == {"text":"Hi, __s22_short__"}, result
        assert invocation_state(accepted[0]) == "SUCCEEDED", result
        assert gone(owned), "Successful original worker did not exit"
        # This tier's execution completion is not upgraded into business finality.
        # No scope-review/new MCP replay is performed while STOP_NEW is effective.
        receipt()
        versions = ok(api(prefix + "packages", admin))["items"]
        assert next(v for v in versions if v["packageId"] == "acme.orders")["state"] == "SUSPENDED"
        recover(proposal)
        assert next(v for v in ok(api(prefix + "packages", admin))["items"] if v["packageId"] == "acme.orders")["state"] == "VERIFIED"
        receipt()
        observe(dict(case="revocation/stop-new", invocationId=accepted[0],
                                 refusal=refusal, executionState="SUCCEEDED",
                                 operationState=outcome.get("state"), tier="HOST_NATIVE", connectivity="CONNECTED"))

        future, owned, accepted = start("__s22_long__")
        proposal, committed = kill("TERMINATE")
        refusal = denied(committed)
        until(lambda: gone(owned), max(.01, 5-(time.monotonic()-committed)),
              "Connected kill exceeded five-second worker teardown target")
        teardown = time.monotonic() - committed
        assert teardown <= 5, teardown
        receipt()
        value = invocation_state(accepted[0])
        assert value in ("OUTCOME_UNKNOWN", "CANCELLED"), value
        try:
            status, result = future.result(timeout=15)
            assert status != 200 or "error" in result or result.get("result", {}).get("isError") is True, result
        except (TimeoutError, OSError):
            # Caller transport loss cannot turn persisted uncertainty into success.
            pass
        recover(proposal)
        receipt()
        assert gone(owned), "Recovery restarted the killed worker"
        observe(dict(case="revocation/connected-terminate", invocationId=accepted[0], teardownSeconds=teardown,
                                 refusal=refusal, tier="HOST_NATIVE", connectivity="CONNECTED"))

        # Drop signed delivery only while real device heartbeats still succeed.
        future, owned, accepted = start("__s22_long__")
        dropped = link("KILLS")
        proposal, committed = kill("TERMINATE")
        refusal = denied(committed)
        bound = min(10, grace_ms/3000) if grace_ms is not None else 10
        until(lambda: gone(owned), bound+2, "Degraded heartbeat did not revoke the actual worker")
        elapsed=time.monotonic()-committed
        assert elapsed <= bound+2, (elapsed,bound)
        events=stats()
        assert any(e["generation"]==dropped and e["outcome"]=="DROPPED" for e in events), events
        # A real successful heartbeat may be followed by its authoritative refusal.
        # The independent lease path must run despite failed delivery GETs.
        assert any(e["generation"]==dropped and e["outcome"]=="FORWARDED"
                   and "heartbeat" in e["path"] for e in events), events
        link("HEALTHY")
        receipt()
        recover(proposal)
        receipt()
        assert gone(owned)
        future.result(timeout=15)
        observe(dict(case="revocation/degraded-delivery",invocationId=accepted[0],
                     connectivity="DEGRADED",teardownSeconds=elapsed,refusal=refusal,
                     controlLossGraceMs=grace_ms,tier="HOST_NATIVE"))

        # Control remains healthy; only the remote Host's own device link fails.
        future, owned, accepted = start("__s22_long__")
        bound=remaining_bound(accepted)
        dropped=link("ALL")
        partitioned=time.monotonic()
        proposal,committed=kill("TERMINATE")
        refusal=denied(committed)
        until(lambda: gone(owned),max(.01,bound+2-(time.monotonic()-partitioned)),
              "Host-only partition exceeded accepted lease/stricter grace")
        elapsed=time.monotonic()-partitioned
        assert elapsed <= bound+2,(elapsed,bound)
        assert api(prefix+"kill-events/status",admin)[0]==200
        events=stats()
        assert any(e["generation"]==dropped and e["outcome"]=="DROPPED" for e in events),events
        link("HEALTHY")
        receipt()
        recover(proposal)
        receipt()
        assert gone(owned)
        future.result(timeout=15)
        observe(dict(case="revocation/host-only-partition",invocationId=accepted[0],
                     connectivity="PARTITIONED",teardownSeconds=elapsed,acceptedBoundSeconds=bound,
                     refusal=refusal,controlLossGraceMs=grace_ms,tier="HOST_NATIVE"))

        # Force a combined kill/recovery snapshot before a heartbeat can observe
        # the intermediate event. Briefly isolate both delivery and heartbeats;
        # recovery cannot revive the older execution.
        future, owned, accepted = start("__s22_long__")
        dropped=link("ALL")
        proposal,committed=kill("TERMINATE")
        recover(proposal)
        assert not gone(owned),"Coalesced-snapshot fixture lost its running worker before delivery"
        assert any(e["generation"]==dropped and e["outcome"]=="DROPPED" for e in stats())
        resumed=time.monotonic()
        link("HEALTHY")
        until(lambda: gone(owned),5,"Recovered signed snapshot revived consumed work")
        elapsed=time.monotonic()-resumed
        assert elapsed<=5,elapsed
        receipt()
        future.result(timeout=15)
        observe(dict(case="revocation/coalesced-kill-recovery",invocationId=accepted[0],
                     connectivity="CONNECTED",teardownSeconds=elapsed,tier="HOST_NATIVE"))
        # Fresh authority after recovery must work.
        future,owned,fresh=start("__s22_short__")
        future.result(timeout=15)
        until(lambda: invocation_state(fresh[0])=="SUCCEEDED",5,"Recovery refused fresh consumed work")
        assert gone(owned)

        # The local host has a dedicated real mTLS dispatch route. With direct
        # delivery GETs blocked, measure the independent PUSH receipt and the
        # connected combined-supervision teardown bound separately.
        assert deploy is not None,"Owned reviewed deployment callback required"
        deploy("tool-host-local")
        host_id="tool-host-local"
        deadline=time.monotonic()+30
        while True:
            status,value=mcp("tools/call",dict(name=TOOL,arguments=dict(name="push-ready")))
            if status==200 and value.get("result",{}).get("structuredContent")=={"text":"Hi, push-ready"}:break
            assert time.monotonic()<deadline,(status,value)
            time.sleep(.1)
        future,owned,accepted=start("__s22_long__")
        link("KILLS")
        proposal,committed=kill("TERMINATE")
        refusal=denied(committed)
        until(lambda: gone(owned),max(.01,5-(time.monotonic()-committed)),
              "Automatic mTLS PUSH exceeded connected teardown bound")
        elapsed=time.monotonic()-committed
        assert elapsed<=5,elapsed
        delivery=receipt()
        component=next(c for c in delivery["components"] if c["kind"]=="TOOL_HOST"
                       and c["componentId"]==host_id)
        assert component["deliveryPath"]=="PUSH",component
        link("HEALTHY")
        recover(proposal)
        receipt()
        future.result(timeout=15)
        observe(dict(case="revocation/automatic-mtls-push",invocationId=accepted[0],
                     connectivity="CONNECTED",teardownSeconds=elapsed,deliveryPath="PUSH",
                     refusal=refusal,tier="HOST_NATIVE"))
        deploy(HOST)
        host_id=HOST

        # SIGSTOP preserves Control storage while making its real process unreachable.
        # The independently running host must expire its last accepted lease.
        future, owned, accepted = start("__s22_long__")
        remaining = max(0, (accepted[2] - int(time.time()*1000))/1000)
        operation_remaining = max(0, (accepted[1] - int(time.time()*1000))/1000)
        assert 0 < remaining <= 30, accepted
        assert operation_remaining > remaining + 10, (
            "Lease-expiry evidence requires an operation deadline materially later than the lease", accepted)
        code = """import json,os,pathlib,signal
ids=[]
for entry in pathlib.Path('/proc').glob('[0-9]*'):
 try:
  argv=(entry/'cmdline').read_bytes().split(bytes([0]))
  if b'/app/quarkus-run.jar' in argv:ids.append(int(entry.name))
 except OSError:pass
assert len(ids)==1,ids
os.kill(ids[0],signal.SIGSTOP)
print(ids[0])
"""
        core = int(exec_text("/opt/quickstart-python/bin/python", "-c", code))
        partitioned = time.monotonic()
        bound=min(remaining,grace_ms/1000) if grace_ms is not None else remaining
        try:
            until(lambda: gone(owned), bound + 2, "Partitioned worker outlived its accepted lease/stricter grace")
            elapsed = time.monotonic() - partitioned
        finally:
            exec_text("/opt/quickstart-python/bin/python", "-c",
                      "import os,signal,sys;os.kill(int(sys.argv[1]),signal.SIGCONT)", str(core))
        assert elapsed <= bound + 2, (elapsed, bound)
        until(lambda: api(prefix+"kill-events/status",admin)[0] == 200,10,
              "Owned Control composition did not become ready after process recovery")
        # A kill committed after Control recovery must reach the existing host;
        # recovered connectivity never resumes its expired worker.
        # The dedicated listener is unavailable while its owning Control process
        # is stopped. After recovery only a fresh quorum-signed add-kill is valid.
        link("KILLS","tool-host-local")
        request_id=secrets.token_hex(16)
        assert operator.send(operator_port,request_id,operator.body(request_id,count=1))[0] == 400
        foreign=secrets.token_hex(16)
        assert operator.send(operator_port,foreign,operator.body(foreign,tenant="00000000-0000-0000-0000-000000000001"))[0] == 403
        body=operator.body(request_id)
        for context in (operator.no_peer,operator.wrong_peer):
            try:
                refusal_status,_=operator.send(operator_port,request_id,body,context)
                assert refusal_status == 401,refusal_status
            except (ssl.SSLError,urllib.error.URLError,ConnectionError):
                pass  # Certificate refusal may happen before HTTP authentication.
        status,event=operator.send(operator_port,request_id,body)
        assert status == 201,(status,event)
        assert operator.send(operator_port,request_id,body)[0] == 409
        receipt(push_host="tool-host-local")
        link("HEALTHY","tool-host-local")
        assert gone(owned)
        assert invocation_state(accepted[0]) in ("OUTCOME_UNKNOWN","CANCELLED")
        recover(dict(eventId=event["eventId"]))
        receipt()
        observe(dict(case="revocation/control-process-partition", mode="Control process stopped; external 2-of-3 operator kill after recovery",
                                 invocationId=accepted[0], remainingLeaseSeconds=remaining,
                                 remainingOperationSeconds=operation_remaining, teardownSeconds=elapsed, operatorDeliveryPath="PUSH", tier="HOST_NATIVE", connectivity="PARTITIONED"))
        # The complete owned composition is the managed unit for this native
        # evaluation tier. A dead host supervisor must cause that unit to exit,
        # which tears down every remaining process in its PID namespace.
        future, owned, accepted = start("__s22_long__")
        code = """import os,pathlib,signal
ids=[]
for entry in pathlib.Path('/proc').glob('[0-9]*'):
 try:
  env=(entry/'environ').read_bytes().split(bytes([0]))
  if b'TOOLGATE_TOOL_HOST_CONFIG=/data/run/tool-host-remote.json' in env:
   argv=(entry/'cmdline').read_bytes().split(bytes([0]))
   if argv[0]==b'/usr/local/bin/olo-toolgate-tool-host':ids.append(int(entry.name))
 except OSError:pass
assert len(ids)==1,ids
os.kill(ids[0],signal.SIGKILL)
"""
        crashed=time.monotonic()
        exec_text("/opt/quickstart-python/bin/python","-c",code)
        def stopped():
            value=json.loads(run(["docker","inspect",container],capture_output=True,text=True).stdout)[0]
            return not value["State"]["Running"]
        until(stopped,5,"Owned unit did not tear down after host supervisor death")
        elapsed=time.monotonic()-crashed
        assert elapsed <= 5,elapsed
        # Restart the same current-schema store. The automatic watchdog must
        # retain uncertainty after lease expiry, and no old worker may resume.
        run(["docker","start",container],capture_output=True)
        restarted=time.monotonic()
        def ready_again():
            try:
                return exec_text("/opt/quickstart-python/bin/python","-c",
                    "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:9092/q/health/ready',timeout=2).status)") == "200"
            except (subprocess.CalledProcessError,OSError):
                return False
        until(ready_again,100,"Owned current-schema Control did not restart")
        def unknown_without_resume():
            assert not workers(),"Restart resumed the old invocation"
            return invocation_state(accepted[0]) == "OUTCOME_UNKNOWN"
        remaining=max(0,(accepted[2]-int(time.time()*1000))/1000)
        until(unknown_without_resume,remaining+15,"Automatic lease watchdog did not retain UNKNOWN after restart")
        observe(dict(case="revocation/supervisor-unit-death",invocationId=accepted[0],
                                 unitTeardownSeconds=elapsed,
                                 unknownAfterRestartSeconds=time.monotonic()-restarted,
                                 tier="HOST_NATIVE",connectivity="SUPERVISOR_DEAD",
                                 authority="Owned PID namespace exited; same-schema restart retained UNKNOWN without resuming work"))
        return dict(passed=True, observations=observations,
                    limitations=["Owned healthy HOST_NATIVE composition only; no A4 teardown bound",
                                 "Native endpoint and OCI containment need separate evidence",
                                 "Operator signatures and peer use owned ephemeral custody; production operator availability is not measured"])
    finally:
        pool.shutdown(wait=True, cancel_futures=True)
