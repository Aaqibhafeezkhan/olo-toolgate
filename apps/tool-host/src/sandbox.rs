// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Local rootless OCI workers. No image pulls, host mounts, remote engine, or native fallback.
use crate::{broker::Invocation, runner::Ran};
use olo_toolgate_contracts::v2::ToolInput;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};
use tokio::sync::{oneshot, watch};

const IDS_PER_WORKER: usize = 1024;
const SECCOMP: &str = include_str!("../packaging/worker-seccomp.json");

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    pub podman_path: PathBuf,
    /// Delegated systemd service cgroup; the supervisor lives in its own subgroup.
    pub cgroup_parent: String,
    /// Already prepared, fully qualified name@sha256:digest, never a tag.
    pub python_image: String,
    pub io_device: String,
    pub io_bytes_per_second: u64,
    pub scratch_bytes: u64,
}
impl Config {
    pub fn validate(&self) -> Result<(), String> {
        if !cfg!(target_os = "linux") || !self.podman_path.is_absolute() {
            return Err("OCI_SANDBOX requires Linux and an absolute local Podman path".into());
        }
        if !self.cgroup_parent.starts_with('/')
            || !self.cgroup_parent.ends_with(".service")
            || self.cgroup_parent.split('/').any(|p| p == ".." || p == ".")
            || !self
                .cgroup_parent
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"/_.@:-".contains(&b))
        {
            return Err("a delegated service cgroupParent is required".into());
        }
        let (name, digest) = self
            .python_image
            .split_once('@')
            .ok_or("immutable pythonImage required")?;
        if name.is_empty()
            || !name.contains('/')
            || name.starts_with('-')
            || !name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"./_-:".contains(&b))
            || !sha256(digest)
        {
            return Err("immutable pythonImage required".into());
        }
        let parts: Vec<_> = self.io_device.split(':').collect();
        if parts.len() != 2
            || parts
                .iter()
                .any(|p| p.is_empty() || p.parse::<u32>().is_err())
            || self.io_bytes_per_second == 0
            || self.io_bytes_per_second > 1024 * 1024 * 1024
            || self.scratch_bytes < 1024 * 1024
            || self.scratch_bytes > 256 * 1024 * 1024
        {
            return Err("invalid sandbox I/O or scratch limits".into());
        }
        Ok(())
    }
    pub fn digest(&self) -> &str {
        self.python_image.split_once('@').expect("validated").1
    }
}
fn sha256(s: &str) -> bool {
    s.strip_prefix("sha256:").is_some_and(|h| {
        h.len() == 64
            && h.bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    })
}

#[derive(Clone)]
struct Active {
    cancel: watch::Sender<bool>,
    done: watch::Receiver<bool>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Record {
    name: String,
    slot: usize,
    #[serde(
        default,
        rename = "invocationId",
        skip_serializing_if = "Option::is_none"
    )]
    invocation_id: Option<String>,
}
pub struct Sandbox {
    config: Config,
    directory: PathBuf,
    policy: PathBuf,
    prefix: String,
    capacity: usize,
    active: Mutex<HashMap<String, Active>>,
    slots: Mutex<Vec<bool>>,
    ready: AtomicBool,
    poisoned: AtomicBool,
}
impl Sandbox {
    pub fn open(
        config: Config,
        work: &Path,
        host: &str,
        capacity: usize,
    ) -> Result<Arc<Self>, String> {
        config.validate()?;
        let directory = work.join("sandbox-spool");
        std::fs::create_dir_all(&directory).map_err(|e| e.to_string())?;
        let directory = std::fs::canonicalize(directory).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700))
                .map_err(|e| e.to_string())?;
        }
        let policy = directory.join("seccomp.json");
        std::fs::write(&policy, SECCOMP).map_err(|e| e.to_string())?;
        let prefix = format!(
            "tg-{}-",
            &toolgate_cli::canonical::sha256_tagged(host.as_bytes())[7..39]
        );
        Ok(Arc::new(Self {
            config,
            directory,
            policy,
            prefix,
            capacity,
            active: Mutex::new(HashMap::new()),
            slots: Mutex::new(vec![false; capacity]),
            ready: AtomicBool::new(false),
            poisoned: AtomicBool::new(false),
        }))
    }
    pub fn available(&self) -> bool {
        self.ready.load(Ordering::SeqCst) && !self.poisoned.load(Ordering::SeqCst)
    }
    pub fn accepts(&self, runtime: &str, digest: Option<&str>) -> bool {
        self.available() && runtime == "python" && digest == Some(self.config.digest())
    }
    fn command(&self) -> tokio::process::Command {
        let mut c = tokio::process::Command::new(&self.config.podman_path);
        // These are engine configuration, never worker environment. In particular
        // CONTAINER_HOST/CONTAINER_CONNECTION and registry credentials are not inherited.
        c.env_clear().env(
            "PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        );
        for key in ["HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"] {
            if let Some(v) = std::env::var_os(key) {
                c.env(key, v);
            }
        }
        c.arg("--remote=false")
            .arg("--runtime=crun")
            .arg("--cgroup-manager=cgroupfs")
            .kill_on_drop(true);
        c
    }
    async fn cli(&self, args: &[String]) -> Result<Vec<u8>, String> {
        let mut c = self.command();
        c.args(args);
        let output = tokio::time::timeout(Duration::from_secs(5), c.output())
            .await
            .map_err(|_| "sandbox engine timeout")?
            .map_err(|e| e.to_string())?;
        if output.stdout.len() > 1024 * 1024 || output.stderr.len() > 64 * 1024 {
            return Err("sandbox engine output limit".into());
        }
        if !output.status.success() {
            let detail = String::from_utf8_lossy(&output.stderr)
                .chars()
                .take(1024)
                .collect::<String>();
            return Err(format!(
                "sandbox engine refused {} (exit {:?}): {detail}",
                args.first().map(String::as_str).unwrap_or("operation"),
                output.status.code()
            ));
        }
        Ok(output.stdout)
    }
    fn journal(&self, record: &Record) -> Result<(), String> {
        let path = self.directory.join(format!("{}.json", record.name));
        let mut options = std::fs::OpenOptions::new();
        options.create_new(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        use std::io::Write;
        let mut file = options.open(path).map_err(|e| e.to_string())?;
        file.write_all(&serde_json::to_vec(record).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
        file.sync_all().map_err(|e| e.to_string())?;
        sync(&self.directory)
    }
    async fn cleanup(&self, name: &str) -> Result<(), String> {
        // An absent container is fine (including a crash before create). Engine
        // errors must not be confused with absence: exists uses exit code 1 only.
        let _ = self
            .cli(&strings(&["rm", "--force", "--time=0", name]))
            .await;
        let mut c = self.command();
        c.args(["container", "exists", name]);
        let status = tokio::time::timeout(Duration::from_secs(5), c.status())
            .await
            .map_err(|_| "sandbox cleanup timeout")?
            .map_err(|e| e.to_string())?;
        if status.code() != Some(1) {
            return Err("sandbox absence could not be established".into());
        }
        let path = self.directory.join(format!("{name}.json"));
        match std::fs::remove_file(path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.to_string()),
        }
        sync(&self.directory)
    }
    pub async fn qualify(self: &Arc<Self>) -> Result<(), String> {
        self.ready.store(false, Ordering::SeqCst);
        if self.poisoned.load(Ordering::SeqCst) {
            return Err("sandbox pool requires restart recovery".into());
        }
        let own = std::fs::read_to_string("/proc/self/cgroup")
            .map_err(|_| "service cgroup unavailable")?;
        if !own.lines().any(|line| {
            line.strip_prefix("0::")
                .is_some_and(|p| p.starts_with(&format!("{}/", self.config.cgroup_parent)))
        }) {
            return Err("supervisor must run in a subgroup of its delegated service".into());
        }
        let parent =
            Path::new("/sys/fs/cgroup").join(self.config.cgroup_parent.trim_start_matches('/'));
        let controllers = std::fs::read_to_string(parent.join("cgroup.controllers"))
            .map_err(|_| "delegated controllers unavailable")?;
        for required in ["cpu", "io", "memory", "pids"] {
            if !controllers.split_whitespace().any(|c| c == required) {
                return Err(format!("sandbox requires delegated {required} controller"));
            }
        }
        if !parent.join("cgroup.kill").exists() {
            return Err("service cgroup kill boundary unavailable".into());
        }
        std::fs::write(
            parent.join("cgroup.subtree_control"),
            "+cpu +io +memory +pids",
        )
        .map_err(|_| "service controllers are not delegated")?;
        let workers = parent.join("sandboxes");
        std::fs::create_dir_all(&workers).map_err(|_| "cannot create worker cgroup parent")?;
        std::fs::write(
            workers.join("cgroup.subtree_control"),
            "+cpu +io +memory +pids",
        )
        .map_err(|_| "worker controllers are not delegated")?;
        let info: Value =
            serde_json::from_slice(&self.cli(&strings(&["info", "--format=json"])).await?)
                .map_err(|e| e.to_string())?;
        if info.pointer("/host/security/rootless") != Some(&json!(true))
            || info.pointer("/host/security/seccompEnabled") != Some(&json!(true))
            || info.pointer("/host/cgroupVersion") != Some(&json!("v2"))
            || info.pointer("/host/serviceIsRemote") != Some(&json!(false))
        {
            return Err("local rootless seccomp/cgroup-v2 engine required".into());
        }
        for kind in ["uidmap", "gidmap"] {
            let entries = info
                .pointer(&format!("/host/idMappings/{kind}"))
                .and_then(Value::as_array)
                .ok_or("subordinate identities unavailable")?;
            if !entries.iter().any(|v| {
                v["container_id"] == 1
                    && v["size"]
                        .as_u64()
                        .is_some_and(|n| n >= ((self.capacity * IDS_PER_WORKER) as u64))
            }) {
                return Err("insufficient subordinate identities for sandbox capacity".into());
            }
        }
        for entry in std::fs::read_dir(&self.directory).map_err(|e| e.to_string())? {
            let path = entry.map_err(|e| e.to_string())?.path();
            if path.file_name().and_then(|p| p.to_str()) == Some("seccomp.json") {
                continue;
            }
            let record: Record =
                serde_json::from_slice(&std::fs::read(&path).map_err(|e| e.to_string())?)
                    .map_err(|_| "corrupt sandbox journal")?;
            if !record.name.starts_with(&self.prefix)
                || record.name.len() != self.prefix.len() + 32
                || !record.name[self.prefix.len()..]
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit())
                || path.file_name().and_then(|p| p.to_str())
                    != Some(&format!("{}.json", record.name))
            {
                return Err("foreign or corrupt sandbox journal".into());
            }
            self.cleanup(&record.name).await?;
        }
        let image: Value = serde_json::from_slice(
            &self
                .cli(&strings(&["image", "inspect", &self.config.python_image]))
                .await?,
        )
        .map_err(|e| e.to_string())?;
        if !image[0]["RepoDigests"].as_array().is_some_and(|ds| {
            ds.iter()
                .any(|d| d.as_str() == Some(&self.config.python_image))
        }) {
            return Err("prepared image digest missing".into());
        }
        let name = format!("{}{}", self.prefix, crate::random_hex(16));
        self.journal(&Record {
            name: name.clone(),
            slot: 0,
            invocation_id: None,
        })?;
        let mut args = self.create_args(&name, 0, 128 * 1024 * 1024, 30_000);
        args.extend(strings(&["-I", "-c", PROBE]));
        let result = async {
            self.cli(&args).await?;
            let out = self.cli(&strings(&["start", "--attach", &name])).await?;
            let observed: Value =
                serde_json::from_slice(&out).map_err(|_| "sandbox qualification output invalid")?;
            validate_probe(&observed, &self.config)
        }
        .await;
        self.cleanup(&name).await?;
        result?;
        self.ready.store(true, Ordering::SeqCst);
        Ok(())
    }
    fn create_args(&self, name: &str, slot: usize, memory: u64, remaining_ms: u64) -> Vec<String> {
        let mapping = format!("0:{}:{IDS_PER_WORKER}", 1 + slot * IDS_PER_WORKER);
        let mut args = strings(&[
            "create",
            "--name",
            name,
            "--pull=never",
            "--network=none",
            "--pid=private",
            "--ipc=private",
            "--uts=private",
            "--cgroupns=private",
            "--read-only",
            "--read-only-tmpfs=false",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--user=100:100",
            "--no-hosts",
            "--security-opt=mask=/etc/resolv.conf:/dev/full:/dev/random:/dev/tty:/dev/ptmx:/dev/pts",
            "--log-driver=none",
            "--interactive",
            "--unsetenv-all",
            "--entrypoint=/usr/local/bin/python3",
            "--env=PATH=/usr/local/bin:/usr/bin:/bin",
            "--workdir=/work",
            "--hostname=toolgate",
            "--http-proxy=false",
            "--image-volume=ignore",
            "--pids-limit=32",
            "--cpus=1",
            "--shm-size=16777216",
            "--env=HOME=/work",
            "--env=LANG=C.UTF-8",
            "--env=TOOLGATE_PROTOCOL=2",
            "--env=TOOLGATE_EXECUTION_TARGET=SERVER",
        ]);
        args.push(format!(
            "--cgroup-parent={}/sandboxes",
            self.config.cgroup_parent
        ));
        args.extend([
            format!("--uidmap={mapping}"),
            format!("--gidmap={mapping}"),
            format!("--memory={memory}"),
            format!("--memory-swap={memory}"),
            format!("--timeout={}", remaining_ms.div_ceil(1000).max(1)),
            format!("--security-opt=seccomp={}", self.policy.display()),
            format!(
                "--cgroup-conf=io.max={} rbps={} wbps={}",
                self.config.io_device,
                self.config.io_bytes_per_second,
                self.config.io_bytes_per_second
            ),
        ]);
        for target in ["/tmp", "/work"] {
            args.push(format!(
                "--tmpfs={target}:rw,nosuid,nodev,noexec,size={},mode=1777",
                self.config.scratch_bytes
            ));
        }
        args.push(self.config.python_image.clone());
        args
    }
    #[allow(clippy::too_many_arguments)]
    pub async fn run(
        self: &Arc<Self>,
        id: &str,
        artifact: &Path,
        input: &ToolInput,
        memory: u64,
        output: usize,
        frames: Arc<crate::events::Sink>,
        broker: Arc<Invocation>,
    ) -> Ran {
        if memory == 0 || memory > crate::config::CEILING.memory_bytes {
            return Ran::Crashed("sandbox memory limit must be nonzero and bounded".into());
        }
        if !self.available() {
            return Ran::Crashed("sandbox pool unavailable".into());
        }
        let slot = {
            let mut slots = self.slots.lock().unwrap_or_else(|e| e.into_inner());
            let Some(slot) = slots.iter().position(|used| !*used) else {
                return Ran::Crashed("sandbox capacity exhausted".into());
            };
            slots[slot] = true;
            slot
        };
        let (cancel, mut cancelled) = watch::channel(false);
        let (done, completion) = watch::channel(false);
        let (answer, receive) = oneshot::channel();
        {
            let mut active = self.active.lock().unwrap_or_else(|e| e.into_inner());
            if active.contains_key(id) {
                self.slots.lock().unwrap_or_else(|e| e.into_inner())[slot] = false;
                return Ran::Crashed("sandbox invocation already active".into());
            }
            active.insert(
                id.into(),
                Active {
                    cancel: cancel.clone(),
                    done: completion,
                },
            );
        }
        let guard = CancelOnDrop(cancel);
        let pool = self.clone();
        let id = id.to_string();
        let artifact = artifact.to_path_buf();
        let input = input.clone();
        tokio::spawn(async move {
            let name = format!("{}{}", pool.prefix, crate::random_hex(16));
            let mut ran = Ran::Crashed("sandbox setup refused".into());
            if pool
                .journal(&Record {
                    name: name.clone(),
                    slot,
                    invocation_id: Some(id.clone()),
                })
                .is_ok()
            {
                let mut args = pool.create_args(
                    &name,
                    slot,
                    memory,
                    input.deadline_unix_ms.saturating_sub(crate::now_ms()),
                );
                args.extend(strings(&["-I", "/opt/toolgate.pyz"]));
                if pool.cli(&args).await.is_ok()
                    && !*cancelled.borrow()
                    && pool
                        .cli(&strings(&[
                            "cp",
                            &artifact.to_string_lossy(),
                            &format!("{name}:/opt/toolgate.pyz"),
                        ]))
                        .await
                        .is_ok()
                    && !*cancelled.borrow()
                {
                    let mut command = pool.command();
                    command.args(["start", "--attach", "--interactive", &name]);
                    ran = tokio::select! {biased;_=cancelled.changed()=>Ran::TimedOut,result=crate::runner::exchange(command,&input,input.deadline_unix_ms,output,Some(&frames),&broker)=>result};
                }
                if pool.cleanup(&name).await.is_err() {
                    pool.poisoned.store(true, Ordering::SeqCst);
                    ran = Ran::Crashed("sandbox teardown unconfirmed; pool fenced".into());
                } else {
                    pool.slots.lock().unwrap_or_else(|e| e.into_inner())[slot] = false;
                }
            } else {
                pool.poisoned.store(true, Ordering::SeqCst);
            }
            let _ = answer.send(ran);
            let _ = done.send(true);
            pool.active
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&id);
        });
        let result = receive.await.unwrap_or_else(|_| {
            self.poisoned.store(true, Ordering::SeqCst);
            Ran::Crashed("sandbox lifecycle lost".into())
        });
        drop(guard);
        result
    }
    pub async fn stop(&self, id: &str) -> bool {
        let active = self
            .active
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(id)
            .cloned();
        if let Some(mut active) = active {
            let _ = active.cancel.send(true);
            let done = async {
                while !*active.done.borrow() {
                    if active.done.changed().await.is_err() {
                        break;
                    }
                }
            };
            if tokio::time::timeout(Duration::from_secs(20), done)
                .await
                .is_err()
            {
                self.poisoned.store(true, Ordering::SeqCst);
            }
        }
        !self.poisoned.load(Ordering::SeqCst)
    }
}
struct CancelOnDrop(watch::Sender<bool>);
impl Drop for CancelOnDrop {
    fn drop(&mut self) {
        let _ = self.0.send(true);
    }
}
fn strings(items: &[&str]) -> Vec<String> {
    items.iter().map(|s| s.to_string()).collect()
}
fn sync(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    std::fs::File::open(path)
        .and_then(|f| f.sync_all())
        .map_err(|e| e.to_string())?;
    let _ = path;
    Ok(())
}
const PROBE: &str = r#"import os,json,socket
def rd(p):
 return open(p).read().strip()
status=rd('/proc/self/status')
denied=False
try: socket.socket(socket.AF_INET,socket.SOCK_STREAM)
except PermissionError: denied=True
print(json.dumps(dict(uid=os.getuid(),pid=os.getpid(),status=status,networkDenied=denied,memory=rd('/sys/fs/cgroup/memory.max'),swap=rd('/sys/fs/cgroup/memory.swap.max'),pids=rd('/sys/fs/cgroup/pids.max'),cpu=rd('/sys/fs/cgroup/cpu.max'),io=rd('/sys/fs/cgroup/io.max'))))
"#;
fn validate_probe(v: &Value, c: &Config) -> Result<(), String> {
    let status = v["status"].as_str().unwrap_or("");
    if v["uid"] != 100
        || v["pid"] != 1
        || v["networkDenied"] != true
        || v["memory"] != "134217728"
        || v["swap"] != "0"
        || v["pids"] != "32"
        || v["cpu"] != "100000 100000"
        || !status.lines().any(|s| s == "NoNewPrivs:\t1")
        || !status.lines().any(|s| s == "CapEff:\t0000000000000000")
        || !status.lines().any(|s| s == "Seccomp:\t2")
        || !v["io"].as_str().unwrap_or("").contains(&format!(
            "{} rbps={} wbps={}",
            c.io_device, c.io_bytes_per_second, c.io_bytes_per_second
        ))
    {
        return Err("sandbox controls did not pass the live qualification probe".into());
    }
    Ok(())
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    fn config() -> Config {
        Config {
            podman_path: "/usr/bin/podman".into(),
            cgroup_parent: "/system.slice/toolgate.service".into(),
            python_image: format!("docker.io/library/python@sha256:{}", "a".repeat(64)),
            io_device: "8:0".into(),
            io_bytes_per_second: 64 * 1024 * 1024,
            scratch_bytes: 8 * 1024 * 1024,
        }
    }
    #[test]
    fn mutable_images_and_invalid_control_paths_are_refused() {
        let mut c = config();
        assert!(c.validate().is_ok());
        c.python_image = "python:latest".into();
        assert!(c.validate().is_err());
        c = config();
        c.cgroup_parent = "/system.slice/../toolgate.service".into();
        assert!(c.validate().is_err());
        c = config();
        c.io_device = "8:0 rbps=max".into();
        assert!(c.validate().is_err());
        c = config();
        c.scratch_bytes = 0;
        assert!(c.validate().is_err());
    }
    #[test]
    fn creation_has_no_mounts_or_network_and_each_slot_uses_another_uid_range() {
        let root = std::env::temp_dir().join(format!("tg-sandbox-args-{}", crate::random_hex(16)));
        let pool = Sandbox::open(config(), &root, "host-1", 2).unwrap();
        assert!(!pool.available());
        assert!(!pool.accepts("python", Some(pool.config.digest())));
        let first = pool.create_args("owned", 0, 128 * 1024 * 1024, 1200);
        let second = pool.create_args("other", 1, 128 * 1024 * 1024, 1200);
        for flag in [
            "--network=none",
            "--read-only",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--unsetenv-all",
            "--pull=never",
            "--timeout=2",
        ] {
            assert!(first.iter().any(|v| v == flag));
        }
        assert!(first.iter().any(|v| v == "--uidmap=0:1:1024"));
        assert!(second.iter().any(|v| v == "--uidmap=0:1025:1024"));
        assert!(!first
            .iter()
            .any(|v| v.starts_with("--volume") || v.starts_with("--mount") || v == "--privileged"));
        let policy: Value = serde_json::from_str(SECCOMP).unwrap();
        assert_eq!(policy["defaultAction"], "SCMP_ACT_ERRNO");
        assert!(!policy["syscalls"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|v| v["action"] == "SCMP_ACT_ALLOW")
            .flat_map(|v| v["names"].as_array().unwrap())
            .any(|v| [
                "socket", "connect", "fork", "vfork", "mount", "unshare", "setns", "ptrace", "bpf"
            ]
            .contains(&v.as_str().unwrap())));
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    #[ignore = "requires the delegated OCI conformance service"]
    async fn restart_reconciles_an_owned_running_container_before_reopening_the_pool() {
        let config = Config {
            python_image: std::env::var("TOOLGATE_SANDBOX_IMAGE").expect("pinned image required"),
            cgroup_parent: std::env::var("TOOLGATE_SANDBOX_CGROUP_PARENT")
                .expect("service required"),
            io_device: std::env::var("TOOLGATE_SANDBOX_IO_DEVICE").expect("I/O device required"),
            ..config()
        };
        let root =
            std::env::temp_dir().join(format!("tg-sandbox-recovery-{}", crate::random_hex(16)));
        let first = Sandbox::open(config.clone(), &root, "recovery", 1).unwrap();
        first.qualify().await.unwrap();
        let name = format!("{}{}", first.prefix, crate::random_hex(16));
        first
            .journal(&Record {
                name: name.clone(),
                slot: 0,
                invocation_id: Some("recovery-test".into()),
            })
            .unwrap();
        let mut args = first.create_args(&name, 0, 128 * 1024 * 1024, 60_000);
        args.extend(strings(&["-I", "-c", "import time; time.sleep(60)"]));
        first.cli(&args).await.unwrap();
        first.cli(&strings(&["start", &name])).await.unwrap();
        drop(first);
        let restarted = Sandbox::open(config, &root, "recovery", 1).unwrap();
        assert!(!restarted.available());
        restarted.qualify().await.unwrap();
        assert!(restarted.available());
        let mut exists = restarted.command();
        assert_eq!(
            exists
                .args(["container", "exists", &name])
                .status()
                .await
                .unwrap()
                .code(),
            Some(1)
        );
        std::fs::write(restarted.directory.join("unknown.json"), "{}").unwrap();
        let refused = Sandbox::open(restarted.config.clone(), &root, "recovery", 1).unwrap();
        assert!(refused.qualify().await.is_err());
        assert!(!refused.available());
        std::fs::remove_dir_all(root).unwrap();
    }
}
