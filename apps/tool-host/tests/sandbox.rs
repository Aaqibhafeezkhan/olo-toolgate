// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Executed explicitly by tools/security/sandbox.py inside a delegated rootless service.
#![cfg(target_os = "linux")]
use olo_toolgate_contracts::v2::ToolInput;
use serde_json::{json, Value};
use std::{
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
    time::Duration,
};
use tool_host::{
    broker::Invocation,
    runner::Ran,
    sandbox::{Config, Sandbox},
};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}
fn area() -> PathBuf {
    let p = std::env::temp_dir().join(format!("tg-oci-test-{}", tool_host::random_hex(16)));
    std::fs::create_dir_all(&p).unwrap();
    p
}
fn config() -> Config {
    Config {
        podman_path: "/usr/bin/podman".into(),
        broker_image: std::env::var("TOOLGATE_SANDBOX_BROKER_IMAGE")
            .expect("pinned broker image required"),
        python_image: std::env::var("TOOLGATE_SANDBOX_IMAGE")
            .expect("pinned sandbox image required"),
        cgroup_parent: std::env::var("TOOLGATE_SANDBOX_CGROUP_PARENT")
            .expect("delegated service required"),
        io_device: std::env::var("TOOLGATE_SANDBOX_IO_DEVICE")
            .expect("backing block device required"),
        io_bytes_per_second: 64 * 1024 * 1024,
        scratch_bytes: 8 * 1024 * 1024,
    }
}
fn input(id: &str, args: Value) -> ToolInput {
    let fixtures: Value = serde_json::from_slice(
        &std::fs::read(root().join("tests/fixtures/contracts/v2/valid.json")).unwrap(),
    )
    .unwrap();
    let mut v = fixtures["ToolInput"].clone();
    v["invocationId"] = json!(id);
    v["arguments"] = args;
    v["deadlineUnixMs"] = json!(tool_host::now_ms() + 20_000);
    serde_json::from_value(v).unwrap()
}
fn artifact(dir: &Path, script: &str) -> PathBuf {
    std::fs::write(dir.join("__main__.py"), script).unwrap();
    assert!(
        std::process::Command::new("/usr/bin/python3")
            .args(["-m", "py_compile"])
            .arg(dir.join("__main__.py"))
            .status()
            .unwrap()
            .success(),
        "attack fixture must be syntactically valid before packing"
    );
    let path = dir.join("app.pyz");
    assert!(std::process::Command::new("/usr/bin/python3")
        .current_dir(dir)
        .args(["-m", "zipfile", "-c", "app.pyz", "__main__.py"])
        .status()
        .unwrap()
        .success());
    path
}
fn control() -> Arc<tool_host::control::Control> {
    Arc::new(
        tool_host::control::Control::new(&tool_host::config::ControlConfig {
            identity_trust_directory: None,
            tenant_id: "10000000-0000-4000-8000-000000000001".into(),
            url: "http://127.0.0.1:1".into(),
            client_certificate_path: None,
            client_key_path: None,
            ca_certificate_path: None,
            development_loopback_http: true,
        })
        .unwrap(),
    )
}
fn broker(id: &str, deadline: u64) -> Arc<Invocation> {
    let mut invocation = Invocation::new(
        id.into(),
        "fence".into(),
        deadline,
        "test-profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        vec![],
        vec![],
        vec![],
        vec![],
        vec![],
        Default::default(),
        true,
        control(),
    );
    // Containment-only fixture: never presented as cryptographic admission.
    invocation.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
        jws: "containment-fixture-only".into(),
    });
    Arc::new(invocation)
}
async fn execute(pool: Arc<Sandbox>, id: &str, artifact: &Path, args: Value) -> Value {
    let input = input(id, args);
    let (sink, _source) = tool_host::events::channel();
    match pool
        .run(
            id,
            artifact,
            &input,
            128 * 1024 * 1024,
            1024 * 1024,
            Arc::new(sink),
            broker(id, input.deadline_unix_ms),
        )
        .await
    {
        Ran::Frame(f) => serde_json::to_value(f).unwrap()["structuredContent"].clone(),
        other => panic!("sandbox did not produce a result: {other:?}"),
    }
}
const ATTACKS: &str = r#"import os,json,socket,ctypes,stat,platform
i=json.loads(input());a=i['arguments'];checks={}
def denied(name,action):
 try: action();checks[name]=False
 except (OSError,PermissionError): checks[name]=True
denied('socket',lambda:socket.socket(socket.AF_INET,socket.SOCK_STREAM))
denied('unixSocket',lambda:socket.socket(socket.AF_UNIX,socket.SOCK_STREAM))
denied('rawSocket',lambda:socket.socket(socket.AF_INET,socket.SOCK_RAW,socket.IPPROTO_ICMP))
def wire_denied(name,host,port,kind=socket.SOCK_STREAM):
 try:
  sock=socket.socket(socket.AF_INET,kind);sock.settimeout(.1)
  if kind==socket.SOCK_DGRAM: sock.sendto(b'test',(host,port))
  else: sock.connect((host,port))
  checks[name]=False
 except OSError as e: checks[name]=e.errno==1
for name,host,port,kind in [
 ('publicTcp','192.0.2.1',443,socket.SOCK_STREAM),
 ('nodeTcp','10.0.0.1',443,socket.SOCK_STREAM),
 ('kubeApi','10.96.0.1',443,socket.SOCK_STREAM),
 ('metadataTcp','169.254.169.254',80,socket.SOCK_STREAM),
 ('otherWorker','127.0.0.1',8080,socket.SOCK_STREAM),
 ('rawDns','8.8.8.8',53,socket.SOCK_DGRAM),
 ('dot','8.8.8.8',853,socket.SOCK_STREAM),
 ('quic','192.0.2.1',443,socket.SOCK_DGRAM)]:
 wire_denied(name,host,port,kind)

denied('hostFile',lambda:open(a['hostFile']).read())
denied('hostWrite',lambda:open(a['hostFile'],'w').write('escaped'))
denied('supervisorProc',lambda:open('/proc/'+str(a['supervisorPid'])+'/environ').read())
denied('rootWrite',lambda:open('/usr/escape','w').write('x'))
denied('artifactWrite',lambda:open('/opt/toolgate.pyz','ab').write(b'x'))
denied('fork',lambda:os.fork())
libc=ctypes.CDLL(None,use_errno=True)
checks['mount']=libc.mount(b'none',b'/tmp',b'tmpfs',0,None)==-1 and ctypes.get_errno()==1
checks['ptrace']=libc.ptrace(0,0,None,None)==-1 and ctypes.get_errno()==1
checks['unshare']=libc.unshare(0x40000000)==-1 and ctypes.get_errno()==1
checks['mknod']=libc.mknod(b'/work/device',stat.S_IFCHR|0o600,os.makedev(1,3))==-1 and ctypes.get_errno()==1
keyctl_nr={'x86_64':250,'aarch64':219}[platform.machine()]
checks['keyring']=libc.syscall(keyctl_nr,1,0,0,0,0)==-1 and ctypes.get_errno()==1
denied('hostDevice',lambda:open('/dev/mem','rb').read(1))
denied('scratchLimit',lambda:open('/work/full','wb').write(b'x'*(9*1024*1024)))
checks['noResolver']=open('/etc/resolv.conf').read()==''
checks['noRoute']=len(open('/proc/net/route').read().splitlines())<=1
checks['noAmbientEnvironment']='TOOLGATE_SUPERVISOR_SECRET' not in os.environ
checks['uid']=os.getuid();checks['pid']=os.getpid()
checks['noSocketMount']=not os.path.exists('/var/run/docker.sock') and not os.path.exists('/run/podman/podman.sock')
print(json.dumps(dict(kind='RESULT',resultType='complete',isError=False,content=[],structuredContent=checks)))
"#;
#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn rootless_workers_deny_ambient_authority_and_preserve_the_stdio_protocol() {
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "oci-conformance", 2).unwrap();
    pool.qualify()
        .await
        .expect("actual sandbox qualification failed");
    assert!(pool.accepts("python", Some(config().digest())));
    assert!(!pool.accepts("python", None));
    assert!(!pool.accepts("jvm", Some(config().digest())));
    assert!(!pool.accepts("python", Some(&format!("sha256:{}", "0".repeat(64)))));
    let secret = dir.join("host-secret");
    std::fs::write(&secret, "must not reach worker").unwrap();
    let app = artifact(&dir, ATTACKS);
    std::env::set_var("TOOLGATE_SUPERVISOR_SECRET", "supervisor-only-test-marker");
    let result = execute(
        pool,
        "attacks",
        &app,
        json!({"hostFile":secret,"supervisorPid":std::process::id()}),
    )
    .await;
    std::env::remove_var("TOOLGATE_SUPERVISOR_SECRET");
    for key in [
        "socket",
        "unixSocket",
        "rawSocket",
        "publicTcp",
        "nodeTcp",
        "kubeApi",
        "metadataTcp",
        "otherWorker",
        "rawDns",
        "dot",
        "quic",
        "hostFile",
        "hostWrite",
        "supervisorProc",
        "rootWrite",
        "artifactWrite",
        "fork",
        "scratchLimit",
        "noSocketMount",
        "mount",
        "ptrace",
        "unshare",
        "mknod",
        "keyring",
        "hostDevice",
        "noResolver",
        "noRoute",
        "noAmbientEnvironment",
    ] {
        assert_eq!(result[key], true, "attack succeeded: {key}, {result}");
    }
    assert_eq!(result["uid"], 100);
    assert_eq!(result["pid"], 1);
    assert_eq!(
        std::fs::read_to_string(secret).unwrap(),
        "must not reach worker"
    );
    assert_eq!(
        std::fs::read_dir(dir.join("sandbox-spool"))
            .unwrap()
            .count(),
        1,
        "only the seccomp policy may remain"
    );
    std::fs::remove_dir_all(dir).unwrap();
}
#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn concurrent_workers_have_distinct_ids_and_private_scratch() {
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "oci-identities", 2).unwrap();
    pool.qualify().await.unwrap();
    let app = artifact(
        &dir,
        r#"import os,json,time
i=json.loads(input());role=i['arguments']['role']
if role=='writer': open('/work/private-marker','w').write('private');time.sleep(1)
print(json.dumps(dict(kind='RESULT',resultType='complete',isError=False,content=[],structuredContent=dict(mapping=open('/proc/self/uid_map').read(),sawOther=os.path.exists('/work/private-marker') and role!='writer'))))
"#,
    );
    let (one, two) = tokio::join!(
        execute(pool.clone(), "one", &app, json!({"role":"writer"})),
        execute(pool.clone(), "two", &app, json!({"role":"reader"}))
    );
    assert_ne!(one["mapping"], two["mapping"]);
    assert_eq!(two["sawOther"], false);
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn frame_and_diagnostic_flooding_end_the_worker_before_the_deadline() {
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "oci-flood", 1).unwrap();
    pool.qualify().await.unwrap();
    for stream in ["stdout", "stderr"] {
        let script=format!("import json,sys\ni=json.loads(input())\nwhile True: sys.{stream}.write('x'*65536);sys.{stream}.flush()\n");
        let app = artifact(&dir, &script);
        let input = input("flood", json!({}));
        let (sink, _source) = tool_host::events::channel();
        let started = std::time::Instant::now();
        let result = pool
            .run(
                "flood",
                &app,
                &input,
                128 * 1024 * 1024,
                1024 * 1024,
                Arc::new(sink),
                broker("flood", input.deadline_unix_ms),
            )
            .await;
        assert!(matches!(result, Ran::Crashed(_)), "{result:?}");
        assert!(started.elapsed() < Duration::from_secs(10));
        assert!(pool.available());
    }
    std::fs::remove_dir_all(dir).unwrap();
}
#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn broker_works_but_cancellation_and_deadline_remove_the_entire_worker() {
    use axum::{routing::get, Router};
    let calls = Arc::new(AtomicUsize::new(0));
    let seen = calls.clone();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = Router::new().route(
        "/ping",
        get(move || {
            let seen = seen.clone();
            async move {
                seen.fetch_add(1, Ordering::SeqCst);
                "ok"
            }
        }),
    );
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "oci-cancellation", 1).unwrap();
    pool.qualify().await.unwrap();
    let artifact = artifact(
        &dir,
        r#"import json,time
i=json.loads(input())
print(json.dumps(dict(kind='BROKER_REQUEST',requestId='r1',operation='ROUTE_CALL',request=dict(route='ping',method='GET',destination='api',variables={},query={},headers={}))),flush=True)
assert json.loads(input())['result']['status']==200
time.sleep(60)
"#,
    );
    for cancel in [true, false] {
        let mut input = input("stop", json!({}));
        input.deadline_unix_ms = tool_host::now_ms() + if cancel { 20_000 } else { 2000 };
        let route=serde_json::from_value(json!({"id":"ping","destination":"api","method":"GET","path":"/ping","query":[],"headers":[],"requestContentTypes":[],"maxRequestBytes":0,"maxResponseBytes":4096,"streaming":"NONE","timeoutMs":1000})).unwrap();
        let destination = serde_json::from_value(
            json!({"id":"api","host":"127.0.0.1","port":port,"tls":{"spkiPins":[]},"scheme":"https","mode":"BROKERED"}),
        )
        .unwrap();
        let mut invocation = Invocation::new(
            "stop".into(),
            "fence".into(),
            input.deadline_unix_ms,
            "test-profile".into(),
            format!("sha256:{}", "a".repeat(64)),
            vec![route],
            vec![destination],
            vec![],
            vec![],
            vec![],
            Default::default(),
            true,
            control(),
        );
        invocation.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
            jws: "containment-fixture-only".into(),
        });
        let broker = Arc::new(invocation);
        let (sink, _source) = tool_host::events::channel();
        let worker = pool.clone();
        let artifact = artifact.clone();
        let before = calls.load(Ordering::SeqCst);
        let task = tokio::spawn(async move {
            worker
                .run(
                    "stop",
                    &artifact,
                    &input,
                    128 * 1024 * 1024,
                    1024 * 1024,
                    Arc::new(sink),
                    broker,
                )
                .await
        });
        tokio::time::timeout(Duration::from_secs(10), async {
            while calls.load(Ordering::SeqCst) == before {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("stdio broker did not reach the approved route");
        if cancel {
            task.abort();
            assert!(pool.stop("stop").await);
        } else {
            assert!(matches!(task.await.unwrap(), Ran::TimedOut));
        }
        assert!(pool.available());
        assert_eq!(
            std::fs::read_dir(dir.join("sandbox-spool"))
                .unwrap()
                .count(),
            1
        );
    }
    server.abort();
    std::fs::remove_dir_all(dir).unwrap();
}

fn engine() -> tokio::process::Command {
    let mut command = tokio::process::Command::new("/usr/bin/podman");
    command.env_clear().env("PATH", "/usr/bin:/bin");
    for key in ["HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"] {
        if let Some(value) = std::env::var_os(key) {
            command.env(key, value);
        }
    }
    command.args([
        "--remote=false",
        "--runtime=crun",
        "--cgroup-manager=cgroupfs",
    ]);
    command.kill_on_drop(true);
    command
}

#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn broker_crash_cancels_worker_and_reclaims_both_owned_containers() {
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "broker-crash", 1).unwrap();
    pool.qualify().await.unwrap();
    let app = artifact(
        &dir,
        "import json,time\ni=json.loads(input());time.sleep(60)\n",
    );
    let input = input("broker-crash", json!({}));
    let invocation = broker("broker-crash", input.deadline_unix_ms);
    let (sink, _source) = tool_host::events::channel();
    let running = {
        let pool = pool.clone();
        let invocation = invocation.clone();
        tokio::spawn(async move {
            pool.run(
                "broker-crash",
                &app,
                &input,
                128 * 1024 * 1024,
                1024 * 1024,
                Arc::new(sink),
                invocation,
            )
            .await
        })
    };
    let (worker, broker) = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            for entry in std::fs::read_dir(dir.join("sandbox-spool")).unwrap() {
                let path = entry.unwrap().path();
                let Ok(bytes) = std::fs::read(path) else {
                    continue;
                };
                let Ok(record) = serde_json::from_slice::<Value>(&bytes) else {
                    continue;
                };
                if record["invocationId"] != "broker-crash" {
                    continue;
                }
                let worker = record["name"].as_str().unwrap().to_string();
                let broker = record["brokerName"].as_str().unwrap().to_string();
                let state = engine()
                    .args(["inspect", "--format={{.State.Running}}", &worker])
                    .output()
                    .await
                    .unwrap();
                if state.status.success() && state.stdout == b"true\n" {
                    return (worker, broker);
                }
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .expect("paired worker never started");
    assert!(engine()
        .args(["kill", "--signal=KILL", &broker])
        .stdout(std::process::Stdio::null())
        .status()
        .await
        .unwrap()
        .success());
    let result = tokio::time::timeout(Duration::from_secs(8), running)
        .await
        .expect("broker loss did not stop worker")
        .unwrap();
    assert!(matches!(result, Ran::Crashed(_)), "{result:?}");
    assert!(invocation.closed.load(Ordering::SeqCst));
    for name in [&worker, &broker] {
        assert_eq!(
            engine()
                .args(["container", "exists", name])
                .status()
                .await
                .unwrap()
                .code(),
            Some(1)
        );
    }
    assert!(pool.available());
    assert_eq!(
        std::fs::read_dir(dir.join("sandbox-spool"))
            .unwrap()
            .count(),
        1
    );
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn worker_cannot_impersonate_the_private_broker_authority_channel() {
    use axum::{routing::any, Router};
    let calls = Arc::new(AtomicUsize::new(0));
    let observed = calls.clone();
    let app = Router::new().fallback(any(move || {
        let calls = observed.clone();
        async move {
            calls.fetch_add(1, Ordering::SeqCst);
            axum::Json(json!({"value":"must-not-be-delivered"}))
        }
    }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let mut control_config: tool_host::config::ControlConfig = serde_json::from_value(json!({
        "tenantId":"10000000-0000-4000-8000-000000000001", "url":format!("http://{address}"), "developmentLoopbackHttp":true
    })).unwrap();
    control_config.identity_trust_directory = None;
    let control = Arc::new(tool_host::control::Control::new(&control_config).unwrap());
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "broker-forgery", 1).unwrap();
    pool.qualify().await.unwrap();
    let app = artifact(
        &dir,
        r#"import json,time
i=json.loads(input())
# A private authority envelope on worker stdio is never interpreted as privileged RPC.
print(json.dumps(dict(type="AUTHORITY",sequence=1,request=dict(type="SECRET",name="other-operation-secret"))),flush=True)
print(json.dumps(dict(kind="BROKER_REQUEST",requestId="forge",operation="GET_IDENTITY",request=dict(invocationId="foreign",fencingToken="foreign"))),flush=True)
time.sleep(60)
"#,
    );
    let input = input("broker-forgery", json!({}));
    let mut invocation = Invocation::new(
        "broker-forgery".into(),
        "fence".into(),
        input.deadline_unix_ms,
        "test-profile".into(),
        format!("sha256:{}", "a".repeat(64)),
        vec![],
        vec![],
        vec![],
        vec![],
        vec![],
        Default::default(),
        true,
        control,
    );
    invocation.invocation_permit = Some(olo_toolgate_contracts::v2::SignedInvocationPermit {
        jws: "containment-fixture-only".into(),
    });
    let invocation = Arc::new(invocation);
    let (sink, _source) = tool_host::events::channel();
    let result = tokio::time::timeout(
        Duration::from_secs(10),
        pool.run(
            "broker-forgery",
            &app,
            &input,
            128 * 1024 * 1024,
            1024 * 1024,
            Arc::new(sink),
            invocation.clone(),
        ),
    )
    .await
    .expect("forgery did not terminate pair");
    assert!(matches!(result, Ran::Crashed(_)), "{result:?}");
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    assert!(invocation.closed.load(Ordering::SeqCst));
    assert!(pool.available());
    assert_eq!(
        std::fs::read_dir(dir.join("sandbox-spool"))
            .unwrap()
            .count(),
        1
    );
    server.abort();
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn qualified_worker_interface_attacks_fail_without_recovering_to_success() {
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "interface-matrix", 1).unwrap();
    pool.qualify().await.unwrap();
    let result = r#"print(json.dumps(dict(kind="RESULT",resultType="complete",isError=False,content=[])),flush=True)"#;
    for (name, attack) in [
        ("malformed", "os.write(1,b'{bad}\\n')".to_string()),
        ("invalid-utf8", "os.write(1,b'\\xff\\n')".to_string()),
        ("duplicate-kind", r#"print('{"kind":"LOG","kind":"RESULT","level":"info","message":"bad"}',flush=True)"#.into()),
        ("unknown-field", r#"print('{"kind":"LOG","level":"info","message":"bad","extra":true}',flush=True)"#.into()),
        ("nested-duplicate", r#"print('{"kind":"RESULT","resultType":"complete","isError":false,"content":[],"structuredContent":{"x":1,"x":2}}',flush=True)"#.into()),
        ("invalid-progress", r#"print('{"kind":"PROGRESS","fraction":2}',flush=True)"#.into()),
        ("deep-frame", r#"value=None
for n in range(70): value=dict(nested=value)
print(json.dumps(dict(kind="RESULT",resultType="complete",isError=False,content=[],structuredContent=dict(data=value))),flush=True)"#.into()),
        ("many-frames", r#"for n in range(10000): print(json.dumps(dict(kind="PROGRESS",fraction=.5)),flush=True)"#.into()),
        ("large-log", r#"print(json.dumps(dict(kind="LOG",level="info",message="x"*(2*1024*1024))),flush=True)"#.into()),
        ("large-result", r#"print(json.dumps(dict(kind="RESULT",resultType="complete",isError=False,content=[],structuredContent=dict(data="x"*(5*1024*1024)))),flush=True)"#.into()),
        ("duplicate-result", result.into()),
        ("after-terminal", format!("{result}\nprint(json.dumps(dict(kind='PROGRESS',fraction=.5)),flush=True)")),
    ] {
        let script = format!("import json,os\ni=json.loads(input())\n{attack}\n{result}\n");
        let app = artifact(&dir,&script);
        let input = input(name,json!({}));
        let (sink,_source) = tool_host::events::channel();
        let ran = pool.run(name,&app,&input,128*1024*1024,4*1024*1024,Arc::new(sink),broker(name,input.deadline_unix_ms)).await;
        assert!(matches!(&ran, Ran::Crashed(message) if message.starts_with("worker ") || message == "invalid worker JSON frame" || message == "unknown worker frame kind"), "{name}: {ran:?}");
        assert!(pool.available(),"{name}");
        assert_eq!(std::fs::read_dir(dir.join("sandbox-spool")).unwrap().count(),1,"{name}");
    }
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn sequential_invocations_reuse_no_scratch_environment_or_broker_authority() {
    let dir = area();
    let pool = Sandbox::open(config(), &dir, "sequential-isolation", 1).unwrap();
    pool.qualify().await.unwrap();
    let app = artifact(
        &dir,
        r#"import json,os
i=json.loads(input());role=i['arguments']['role']
if role=='first':
 open('/work/credential-cache','w').write('invocation-only-value')
 os.environ['INVOCATION_ONLY_SECRET']='invocation-only-value'
 globals()['cached_secret']='invocation-only-value'
print(json.dumps(dict(kind='RESULT',resultType='complete',isError=False,content=[],structuredContent=dict(scratch=os.path.exists('/work/credential-cache'),environment='INVOCATION_ONLY_SECRET' in os.environ,memory='cached_secret' in globals(),pid=os.getpid()))))
"#,
    );
    let first = execute(pool.clone(), "first", &app, json!({"role":"first"})).await;
    assert_eq!(first["scratch"], true);
    assert_eq!(first["environment"], true);
    assert_eq!(first["memory"], true);
    let second = execute(pool.clone(), "second", &app, json!({"role":"second"})).await;
    for field in ["scratch", "environment", "memory"] {
        assert_eq!(second[field], false, "{field}");
    }
    assert_eq!(second["pid"], 1);
    let one = broker("owner-one", tool_host::now_ms() + 20_000);
    let two = broker("owner-two", tool_host::now_ms() + 20_000);
    assert_ne!(
        one.broker_policy().unwrap().broker_instance_id,
        two.broker_policy().unwrap().broker_instance_id
    );
    assert_eq!(
        std::fs::read_dir(dir.join("sandbox-spool"))
            .unwrap()
            .count(),
        1
    );
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
#[ignore = "requires a prepared rootless OCI service; run tools/security/sandbox.py"]
async fn worker_inherits_no_supervisor_file_descriptor_or_platform_credentials() {
    use std::io::Read;
    let dir = area();
    let sentinel = dir.join("supervisor-secret-sentinel");
    std::fs::write(&sentinel, "never-deliver-supervisor-value").unwrap();
    // Keep a real open supervisor descriptor alive across both broker and
    // worker launch; absence of a descriptor is not a meaningful leak test.
    let mut held = std::fs::File::open(&sentinel).unwrap();
    let pool = Sandbox::open(config(), &dir, "descriptor-isolation", 1).unwrap();
    pool.qualify().await.unwrap();
    let app = artifact(
        &dir,
        r#"import json,os
i=json.loads(input());targets=[]
for fd in os.listdir('/proc/self/fd'):
 try: targets.append(os.readlink('/proc/self/fd/'+fd))
 except OSError: pass
paths=['/var/run/secrets/kubernetes.io/serviceaccount/token','/root/.aws/credentials','/run/podman/podman.sock','/var/run/docker.sock']
print(json.dumps(dict(kind='RESULT',resultType='complete',isError=False,content=[],structuredContent=dict(leaked=any('supervisor-secret-sentinel' in p for p in targets),platformCredentials=any(os.path.exists(p) for p in paths),hostFile=os.path.exists(i['arguments']['hostFile'])))))
"#,
    );
    let result = execute(
        pool.clone(),
        "descriptor",
        &app,
        json!({"hostFile":sentinel}),
    )
    .await;
    assert_eq!(result["leaked"], false);
    assert_eq!(result["platformCredentials"], false);
    assert_eq!(result["hostFile"], false);
    let mut retained = String::new();
    held.read_to_string(&mut retained).unwrap();
    assert_eq!(retained, "never-deliver-supervisor-value");
    assert!(pool.available());
    assert_eq!(
        std::fs::read_dir(dir.join("sandbox-spool"))
            .unwrap()
            .count(),
        1
    );
    std::fs::remove_dir_all(dir).unwrap();
}
