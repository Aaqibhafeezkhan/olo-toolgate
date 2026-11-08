# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Owned real Control/Native T5/managed Python SDK runtime qualification."""
import concurrent.futures
import copy
import hashlib
import json
import os
from pathlib import Path
import selectors
import secrets
import shutil
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
import zipfile

ROOT=Path(__file__).resolve().parents[2]
TOOL="ololabs.hello-world-python/client_greet"
BASE="python@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1"


class Tunnel(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True
    def __init__(self, port, target):
        self.target=target
        super().__init__(("127.0.0.1",port),TunnelHandler)


class TunnelHandler(socketserver.BaseRequestHandler):
    def handle(self):
        # Raw TLS bytes only. The real endpoint authenticates the native peer;
        # this fixture cannot assert a certificate or fabricate signed replies.
        with socket.create_connection(("127.0.0.1",self.server.target),timeout=5) as backend:
            with selectors.DefaultSelector() as selector:
                selector.register(self.request,selectors.EVENT_READ,backend)
                selector.register(backend,selectors.EVENT_READ,self.request)
                while True:
                    events=selector.select(15)
                    if not events:return
                    for key,_ in events:
                        data=key.fileobj.recv(65536)
                        if not data:return
                        key.data.sendall(data)


class NativeFixture:
    def __init__(self, run):
        self.run=run
        self.temporary=tempfile.TemporaryDirectory(prefix="s22-native-")
        self.directory=Path(self.temporary.name).resolve()
        self.directory.chmod(0o700)
        self.project=Path(tempfile.mkdtemp(prefix="s22-native-image-",dir=ROOT/".dev"))
        self.tag="olo-toolgate-s22-native:"+secrets.token_hex(12)
        self.process=None
        self.tunnels=[]
        self.log=None
        self.image=None

    def prepare(self):
        project=self.project/"project"
        shutil.copytree(ROOT/"core-mcp/hello-world-python",project,
                        ignore=shutil.ignore_patterns("build","__pycache__","*.egg-info"))
        source=project/"src/hello_world_python/__init__.py"
        value=source.read_text()
        anchor='    context.log.info("Producing greeting")'
        assert anchor in value
        source.write_text(value.replace(anchor,anchor+'\n    import time\n    if text.startswith("__s22_"):time.sleep(6 if text=="__s22_short__" else 90)'))
        env=dict(os.environ,TOOLGATE_PYTHON=os.sys.executable,
                 PYTHONPATH=str(ROOT/"tool-sdk/python-sdk/src"))
        target=Path(os.environ.get("CARGO_TARGET_DIR",ROOT/"target"))
        if not target.is_absolute():target=ROOT/target
        cli=Path(os.environ["TOOLGATE_CLI"]).resolve(strict=True) if os.environ.get("TOOLGATE_CLI") else target/"debug/toolgate"
        for command in ("check","pack"):
            self.run([str(cli),command,str(project)],env=env,capture_output=True,text=True)
        archives=list((project/"build/toolgate").glob("*.tgpkg"))
        assert len(archives)==1,archives
        self.run([str(cli),"validate",str(archives[0])],capture_output=True)
        with zipfile.ZipFile(archives[0]) as archive:
            manifest=json.loads(archive.read("toolgate-package.json"))
            paths=[a["path"] for a in manifest["artifacts"] if a["path"].endswith(".pyz")]
            assert len(paths)==1
            artifact=archive.read(paths[0])
        (self.project/"tool.pyz").write_bytes(artifact)
        (self.project/"Dockerfile").write_text(
            "FROM "+BASE+"\nLABEL io.olo.s22.fixture="+self.tag.split(":")[1]+"\n"
            "RUN mkdir -p /opt/tool/ololabs.hello-world-python\n"
            "COPY --chmod=0444 tool.pyz /opt/tool/ololabs.hello-world-python/tool.pyz\n"
            "USER 65532:65532\nENTRYPOINT []\nCMD []\n")
        self.run(["docker","build","-t",self.tag,str(self.project)],capture_output=True,text=True)
        info=json.loads(self.run(["docker","image","inspect",self.tag],capture_output=True,text=True).stdout)[0]
        self.image=info["Id"]
        self.version=next(v.split("=",1)[1] for v in info["Config"]["Env"] if v.startswith("PYTHON_VERSION="))
        self.artifact_digest=hashlib.sha256(artifact).hexdigest()
        result=self.run(["cargo","test","-p","olo-toolgate-client","--test","revocation_joined",
                         "--no-run","--locked","--message-format=json"],capture_output=True,text=True)
        binaries=[json.loads(line)["executable"] for line in result.stdout.splitlines()
                  if line.startswith("{") and json.loads(line).get("reason")=="compiler-artifact"
                  and json.loads(line).get("executable") and json.loads(line).get("target",{}).get("kind")==["test"]]
        assert len(binaries)==1,binaries
        self.binary=binaries[0]
        path=self.directory/"config.json"
        path.write_text(json.dumps(self.configuration("00000000-0000-4000-8000-000000000001")))
        path.chmod(0o600)
        result=self.run([self.binary,"native_owner_joins_current_control_and_managed_runtime",
                         "--ignored","--exact","--nocapture"],env=dict(os.environ,
                         TOOLGATE_NATIVE_REVOCATION_CONFIG=str(path),TOOLGATE_NATIVE_REVOCATION_CONFIG_ONLY="1"),
                         capture_output=True,text=True)
        assert "test native_owner_joins_current_control_and_managed_runtime ... ok" in result.stdout,result.stdout

    def configuration(self,tenant):
        ca=self.directory/"control-ca.pem"
        input_schema=dict(type="object",additionalProperties=False,
                          properties=dict(text=dict(type="string",default="World",maxLength=128)),required=[])
        output_schema=dict(type="object",additionalProperties=False,
                           properties=dict(message=dict(type="string")),required=["message"])
        definition=dict(id=TOOL,name="Owned Native greeting",description="Owned read-only SDK greeting",
                        actions=[dict(name="invoke",resourceKinds=["CUSTOM"])],
                        inputSchema=input_schema,outputSchema=output_schema)
        profile=dict(tool=dict(id=TOOL,name="Owned Native greeting",enabled=True,revision=1,version="1.0.0",
                               packageDigest="a"*64,extractorId="extract-s22-native",definition=definition),
                     extractor=dict(id="extract-s22-native",name="Owned Native resources",enabled=True,
                                    revision=1,extractorKind="FIELDS",version="1.0.0",fields=[],
                                    fixedResources=[dict(kind="CUSTOM",locator="tool://"+TOOL)],maxResources=1))
        registration=dict(toolId=TOOL,action="invoke",runtimeId="python-sdk",
            entryPoint="/opt/tool/ololabs.hello-world-python/tool.pyz",inputSchema=input_schema,
            outputSchema=output_schema,limits=dict(timeoutMs=10000,memoryMiB=256,maxInputBytes=4096,maxOutputBytes=8192),
            authorizationProfile=profile)
        state=self.directory/"state"
        config=dict(tenantId=tenant,serverUrl="https://localhost:18443",stateDirectory=str(state),
                    ipcEndpoint=str(self.directory/"ipc.sock"),authorizedPeers=[str(os.getuid())],
                    caCertificatePath=str(ca),requestTimeoutSeconds=3,
                    execution=dict(enginePath=shutil.which("docker"),engineEndpoint="unix:///var/run/docker.sock",
                                   stateDirectory=str(state/"runtimes"),allowFirstUsePull=False,pullTimeoutSeconds=30,
                                   runtimes=[dict(id="python-sdk",kind="PYTHON_SDK",image=self.image,version=self.version)],
                                   tools=[registration]))
        return config

    def start(self, container, tls_port, tenant):
        for port in (18443,8443):
            tunnel=Tunnel(port,int(tls_port))
            self.tunnels.append(tunnel)
            threading.Thread(target=tunnel.serve_forever,daemon=True).start()
        ca=self.directory/"control-ca.pem"
        self.run(["docker","cp",container+":/data/keys/device-ca.crt",str(ca)],capture_output=True)
        ca.chmod(0o600)
        config=self.configuration(tenant)
        path=self.directory/"config.json"
        path.write_text(json.dumps(config));path.chmod(0o600)
        self.log=(self.directory/"service.log").open("w")
        self.process=subprocess.Popen([self.binary,"native_owner_joins_current_control_and_managed_runtime",
                                       "--ignored","--exact","--nocapture"],cwd=ROOT,
            env=dict(os.environ,TOOLGATE_NATIVE_REVOCATION_CONFIG=str(path),TOOLGATE_NATIVE_REVOCATION_CONFIG_ONLY="0"),
            stdout=self.log,stderr=subprocess.STDOUT)

    def read(self,name):
        assert self.process is not None and self.process.poll() is None, (
            "Owned Native service owner exited",self.process.returncode,
            (self.directory/"service.log").read_text())
        try:return json.loads((self.directory/name).read_text())
        except (OSError,json.JSONDecodeError):return None

    def workers(self):
        result=self.run(["docker","ps","--filter","ancestor="+self.image,
                         "--format","{{.ID}}"],capture_output=True,text=True)
        return sorted(filter(None,result.stdout.splitlines()))

    def close(self):
        (self.directory/"stop").touch()
        if self.process is not None:
            try:self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
        if self.log:self.log.close()
        for tunnel in self.tunnels:tunnel.shutdown();tunnel.server_close()
        if self.image:
            # The nonce label makes the fixture image unique; never enumerate
            # or remove any other engine owner's containers or images.
            for identifier in self.workers():
                self.run(["docker","rm","-f","-v",identifier],capture_output=True)
            subprocess.run(["docker","image","rm",self.tag],cwd=ROOT,capture_output=True)
        if self.project.resolve().parent != (ROOT/".dev").resolve() or not self.project.name.startswith("s22-native-image-"):
            raise ValueError("Owned Native fixture path required")
        shutil.rmtree(self.project)
        self.temporary.cleanup()


def verify(fixture, api, ok, tokens, mcp, exec_text, container, tls_port, tenant,
           reviewed, select_execution, scope, report):
    """Join actual Native HTTPS consumption, signed delivery and engine teardown."""
    observations=[]
    report["nativeRevocation"]=dict(passed=False,observations=observations)
    prefix="/api/control/v1/"
    admin=tokens["admin"]
    pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)
    def until(action,seconds,reason):
        deadline=time.monotonic()+seconds
        while True:
            result=action()
            if result:return result
            assert time.monotonic()<deadline,reason
            time.sleep(.05)
    def document(name):
        return fixture.read(name)
    def observe(value):
        observations.append(value)
        print("PASS S22 Native "+json.dumps(value,sort_keys=True),flush=True)
    def apply(kind,body):
        proposal=ok(api(prefix+"kill-events"+("/recoveries" if kind=="RECOVERY" else ""),admin,body,"POST"),202)
        assert api(prefix+"kill-events/proposals/"+proposal["id"]+"/decisions",admin,
                   dict(expectedRevision=proposal["revision"],approve=True),"POST")[0]==403
        for reviewer in ("reviewer-1","reviewer-2"):
            committed=time.monotonic()
            proposal=ok(api(prefix+"kill-events/proposals/"+proposal["id"]+"/decisions",
                            tokens[reviewer],dict(expectedRevision=proposal["revision"],approve=True),"POST"))
        assert proposal["state"]=="APPLIED",proposal
        return proposal,committed
    def kill(mode):
        return apply("KILL",dict(scope=dict(kind="TENANT"),mode=mode,reason="Owned actual Native revocation"))
    def recover(event):
        return apply("RECOVERY",dict(supersedes=[event["eventId"]],reason="Owned actual Native recovery"))
    def sql_execution():
        code="""import sys,json
sys.path.insert(0,'/opt/quickstart')
import supervisor as s
s.TENANT=sys.argv[1]
with s.Database() as db:
 rows=db.execute("SELECT i.invocation_id,i.expires_at,l.expires_at,i.state FROM control_enterprise_invocations i JOIN control_execution_leases l ON l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id JOIN control_invocation_kill_scopes k ON k.tenant_id=i.tenant_id AND k.operation_id=i.invocation_id AND k.attempt_id=l.fencing_token WHERE i.tenant_id=? AND k.tool_id=? AND k.device_id=? ORDER BY i.created_at DESC LIMIT 1",(s.TENANT,sys.argv[2],sys.argv[3])).fetchall()
print(json.dumps([list(row) for row in rows]))
"""
        rows=json.loads(exec_text("/opt/quickstart-python/bin/python","-c",code,tenant,TOOL,device))
        return rows[0] if rows else None
    def start(marker):
        assert not fixture.workers()
        future=pool.submit(mcp,"tools/call",dict(name=TOOL,arguments=dict(text=marker)),secrets.token_hex(16))
        owned=until(fixture.workers,20,"Actual Native SDK worker did not start")
        accepted=until(lambda:(row if (row:=sql_execution()) and row[3]=="EXECUTING" else None),
                       5,"No actual Native T5 frozen scope/lease")
        assert accepted[2]>int(time.time()*1000)
        return future,owned,accepted
    def gone(owned):
        return not set(owned).intersection(fixture.workers())
    def receipt():
        epoch=ok(api(prefix+"kill-events/status",admin))["state"]["authorizationEpoch"]
        def current():
            overview=ok(api(prefix+"kill-events/status",admin))
            component=next((c for c in overview["components"] if c["kind"]=="ENDPOINT"
                            and c["componentId"]==device),None)
            return component if component and component.get("authorizationEpoch",-1)>=epoch else None
        value=until(current,15,"Actual Native owner did not acknowledge signed state")
        assert value["deliveryPath"]=="CHECK_IN",value
        return value
    def denied(committed):
        status,value=mcp("tools/call",dict(name=TOOL,arguments=dict(text="must-not-run")))
        assert status!=200 or "error" in value or value.get("result",{}).get("isError") is True,value
        elapsed=time.monotonic()-committed
        assert elapsed<=1,elapsed
        return elapsed
    try:
        fixture.start(container,tls_port,tenant)
        prompt=until(lambda:document("prompt.json"),30,"Actual Native enrollment did not begin")
        review=ok(api(prefix+"endpoint/enrollments/review?code="+prompt["userCode"],admin))
        assert review["keyFingerprint"]==prompt["keyFingerprint"]
        ok(api(prefix+"endpoint/enrollments/decision",admin,
               dict(userCode=review["userCode"],keyFingerprint=review["keyFingerprint"],choice="APPROVE"),"POST"))
        def ready():
            value=document("health.json")
            return value if value and value["device"]["ready"] and value["runtime"]["ready"] else None
        until(ready,60,"Actual Native enrollment/runtime preparation did not become ready")
        journal=json.loads((fixture.directory/"state/journal.json").read_text())
        assert journal["formatVersion"]==4
        device=journal["identity"]["deviceId"]
        profile=until(lambda:document("profile.json"),5,"Native installed profile was not exported")
        reviewed(prefix+"extractors",profile["extractor"])
        reviewed(prefix+"tools",profile["tool"])
        reviewed(prefix+"tool-groups",dict(id="s22-native-tools",name="Owned Native tools",enabled=True,
                                         revision=1,toolIds=[TOOL]))
        reviewed(prefix+"device-groups",dict(id="s22-native-devices",name="Owned Native devices",enabled=True,
                                           revision=1,deviceIds=[device]))
        binding=ok(api(prefix+"bindings/binding-default",admin))
        reviewed(prefix+"bindings",{**binding,"id":"binding-s22-native","name":"Owned Native execution",
                 "revision":1,"toolGroupId":"s22-native-tools","deviceGroupId":"s22-native-devices",
                 "allowedPackageDigests":[profile["tool"]["packageDigest"]]})
        grant_scope=copy.deepcopy(scope)
        grant_scope.update(toolGroups=dict(ids=["s22-native-tools"],all=False),
                           deviceGroups=dict(ids=["s22-native-devices"],all=False),
                           resources=[dict(kind="CUSTOM",locator="tool://"+TOOL,match="EXACT")])
        for purpose in ("SERVICE","CAPABILITY"):
            reviewed(prefix+"grants",dict(id="s22-native-"+purpose.lower(),name="Owned Native "+purpose,
                     enabled=True,revision=1,sourceType="AGENT_GROUP",sourceId="default-agents",
                     purpose=purpose,scope=grant_scope))
        select_execution("binding-s22-native")
        until(lambda:any(t["name"]==TOOL for t in ok(mcp("tools/list"))["result"]["tools"]),
              30,"Actual Native installed catalog was not discoverable")
        future,owned,accepted=start("__s22_short__")
        event,committed=kill("STOP_NEW")
        refusal=denied(committed)
        assert not gone(owned),"STOP_NEW cancelled Native consumed work"
        status,result=future.result(timeout=20)
        assert status==200 and result.get("result",{}).get("structuredContent")=={
            "message":"Hello __s22_short__"},(status,result)
        until(lambda:sql_execution()[3]=="SUCCEEDED",10,"Native STOP_NEW original execution did not succeed")
        assert gone(owned)
        receipt()
        recover(event)
        receipt()
        observe(dict(case="revocation/native-stop-new",invocationId=accepted[0],connectivity="DEGRADED",
                     executionState="SUCCEEDED",refusalSeconds=refusal,tier="MANAGED_NATIVE_SDK"))
        future,owned,accepted=start("__s22_long__")
        event,committed=kill("TERMINATE")
        refusal=denied(committed)
        until(lambda:gone(owned),5,"Native signed cancellation did not precede the ten-second runtime deadline")
        elapsed=time.monotonic()-committed
        assert elapsed<=5,elapsed
        receipt()
        future.result(timeout=20)
        recover(event)
        receipt()
        assert gone(owned)
        observe(dict(case="revocation/native-terminate",invocationId=accepted[0],connectivity="DEGRADED",
                     teardownSeconds=elapsed,refusalSeconds=refusal,tier="MANAGED_NATIVE_SDK"))
        # Every native callback is real HTTPS and runtime execution, not a
        # test AuthorizationPort. An actual Control-process outage cannot renew.
        future,owned,accepted=start("__s22_long__")
        remaining=max(0,(accepted[2]-int(time.time()*1000))/1000)
        assert 0<remaining<=30 and accepted[1]-accepted[2]>10000,accepted
        code="""import pathlib,os,signal
ids=[int(p.parent.name) for p in pathlib.Path('/proc').glob('[0-9]*/cmdline') if b'/app/quarkus-run.jar' in p.read_bytes().split(bytes([0]))]
assert len(ids)==1,ids
os.kill(ids[0],signal.SIGSTOP)
print(ids[0])
"""
        core=int(exec_text("/opt/quickstart-python/bin/python","-c",code))
        partitioned=time.monotonic()
        try:
            until(lambda:gone(owned),min(remaining,10)+2,"Partitioned Native worker outlived its lease/runtime bound")
            elapsed=time.monotonic()-partitioned
        finally:
            exec_text("/opt/quickstart-python/bin/python","-c",
                      "import os,signal,sys;os.kill(int(sys.argv[1]),signal.SIGCONT)",str(core))
        assert elapsed<=min(remaining,10)+2,(elapsed,remaining)
        until(lambda:api(prefix+"kill-events/status",admin)[0]==200,10,"Control did not recover")
        future.result(timeout=20)
        assert gone(owned)
        observe(dict(case="revocation/native-control-partition",invocationId=accepted[0],
                     connectivity="PARTITIONED",remainingLeaseSeconds=remaining,runtimeLimitMs=10000,
                     teardownSeconds=elapsed,tier="MANAGED_NATIVE_SDK"))
        (fixture.directory/"stop").touch()
        fixture.process.wait(timeout=20)
        assert fixture.process.returncode==0,(fixture.directory/"service.log").read_text()
        result=dict(passed=True,observations=observations,imageId=fixture.image,
                    fixtureSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    ownerTestSourceSha256=hashlib.sha256((ROOT/"apps/endpoint-client/tests/revocation_joined.rs").read_bytes()).hexdigest(),
                    sdkArtifactSha256=fixture.artifact_digest,
                    limitations=["Actual Linux ClientService owner, not OS daemon lifecycle or A4 acceptance",
                                 "Manual current registration with reviewed scope; unknown placement facts match kills conservatively",
                                 "Business finality is not inferred from runtime completion",
                                 "Partition teardown is bounded by the earlier task deadline or accepted lease; independent lease timing has separate component vectors"])
        report["nativeRevocation"]=result
        return result
    finally:
        pool.shutdown(wait=True,cancel_futures=True)
        logs=ROOT/"build/security/stages/s22"
        logs.mkdir(parents=True,exist_ok=True)
        if (fixture.directory/"service.log").exists():
            shutil.copyfile(fixture.directory/"service.log",logs/"native-owner-current.log")
