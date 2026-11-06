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
    /// Trusted broker image, independently pinned and prepared locally.
    pub broker_image: String,
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
        for (field, image) in [
            ("pythonImage", &self.python_image),
            ("brokerImage", &self.broker_image),
        ] {
            let (name, digest) = image
                .split_once('@')
                .ok_or_else(|| format!("immutable {field} required"))?;
            if name.is_empty()
                || !name.contains('/')
                || name.starts_with('-')
                || !name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"./_-:".contains(&b))
                || !sha256(digest)
            {
                return Err(format!("immutable {field} required"));
            }
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
    // Absent only for recovery of journals written before paired brokers.
    #[serde(
        default,
        rename = "brokerName",
        skip_serializing_if = "Option::is_none"
    )]
    broker_name: Option<String>,
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
        let output = match tokio::time::timeout(Duration::from_secs(5), c.output()).await {
            Ok(Ok(output)) => output,
            Ok(Err(_)) => {
                self.poisoned.store(true, Ordering::SeqCst);
                return Err(
                    "sandbox engine I/O uncertain; managed service restart required".into(),
                );
            }
            Err(_) => {
                self.poisoned.store(true, Ordering::SeqCst);
                return Err(format!(
                    "sandbox engine {} timeout; managed service restart required",
                    args.first().map(String::as_str).unwrap_or("operation")
                ));
            }
        };
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
    async fn remove_container(&self, name: &str) -> Result<(), String> {
        // An absent container is fine (including a crash before create). Engine
        // errors must not be confused with absence: exists uses exit code 1 only.
        let removal = self
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
        if self.poisoned.load(Ordering::SeqCst) {
            removal?;
        }
        Ok(())
    }
    async fn cleanup(&self, record: &Record) -> Result<(), String> {
        // Always attempt both removals; retain the durable pair until both
        // absences are proven. An engine error never releases the identity slot.
        let worker = self.remove_container(&record.name).await;
        let broker = match &record.broker_name {
            Some(name) => self.remove_container(name).await,
            None => Ok(()),
        };
        worker?;
        broker?;
        if self.poisoned.load(Ordering::SeqCst) {
            return Err(
                "sandbox engine uncertainty retains its journal until managed restart".into(),
            );
        }
        let path = self.directory.join(format!("{}.json", record.name));
        match std::fs::remove_file(path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.to_string()),
        }
        sync(&self.directory)
    }
    async fn qualify_service(&self) -> Result<(), String> {
        let unit = self
            .config
            .cgroup_parent
            .rsplit('/')
            .next()
            .ok_or("service name missing")?;
        let mut command = tokio::process::Command::new("/usr/bin/systemctl");
        command
            .env_clear()
            .env("PATH", "/usr/bin:/bin")
            .kill_on_drop(true);
        for key in ["HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"] {
            if let Some(value) = std::env::var_os(key) {
                command.env(key, value);
            }
        }
        command.arg(if self.config.cgroup_parent.starts_with("/user.slice/") {
            "--user"
        } else {
            "--system"
        });
        command.args([
            "show",
            unit,
            "--property=ControlGroup,Delegate,KillMode,KillSignal,SendSIGKILL",
        ]);
        let output = tokio::time::timeout(Duration::from_secs(5), command.output())
            .await
            .map_err(|_| "service property lookup timed out")?
            .map_err(|_| "service manager unavailable")?;
        if !output.status.success() || output.stdout.len() > 16384 || output.stderr.len() > 4096 {
            return Err("managed service properties unavailable".into());
        }
        validate_service_properties(&output.stdout, &self.config.cgroup_parent)
    }
    pub async fn qualify(self: &Arc<Self>) -> Result<(), String> {
        self.ready.store(false, Ordering::SeqCst);
        if self.poisoned.load(Ordering::SeqCst) {
            return Err("sandbox pool requires restart recovery".into());
        }
        self.qualify_service().await?;
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
                        .is_some_and(|n| n >= ((self.capacity * 2 * IDS_PER_WORKER) as u64))
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
            if record
                .broker_name
                .as_ref()
                .is_some_and(|n| n != &format!("{}-broker", record.name))
                || record.slot >= self.capacity
                || !record.name.starts_with(&self.prefix)
                || record.name.len() != self.prefix.len() + 32
                || !record.name[self.prefix.len()..]
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit())
                || path.file_name().and_then(|p| p.to_str())
                    != Some(&format!("{}.json", record.name))
            {
                return Err("foreign or corrupt sandbox journal".into());
            }
            self.cleanup(&record).await?;
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
        let broker_image: Value = serde_json::from_slice(
            &self
                .cli(&strings(&["image", "inspect", &self.config.broker_image]))
                .await?,
        )
        .map_err(|_| "broker image metadata invalid")?;
        let expected = self
            .config
            .broker_image
            .split_once('@')
            .expect("validated")
            .1;
        if broker_image[0]["Digest"].as_str() != Some(expected)
            && !broker_image[0]["RepoDigests"].as_array().is_some_and(|ds| {
                ds.iter()
                    .any(|d| d.as_str() == Some(&self.config.broker_image))
            })
        {
            return Err("prepared broker image digest missing".into());
        }
        let name = format!("{}{}", self.prefix, crate::random_hex(16));
        let broker_name = format!("{name}-broker");
        let record = Record {
            name: name.clone(),
            slot: 0,
            invocation_id: None,
            broker_name: Some(broker_name.clone()),
        };
        self.journal(&record)?;
        let mut probes = Vec::new();
        let result = async {
            let mut args = self.create_args(&name, 0, 128 * 1024 * 1024, 30_000);
            args.extend(strings(&["-I", "-c", PROBE]));
            self.cli(&args).await?;
            let (worker_process, worker) = self
                .live_probe(&name)
                .await
                .map_err(|error| format!("worker isolation {error}"))?;
            probes.push(worker_process);
            validate_probe(&worker, &self.config)?;
            let mut args = self.broker_args(&broker_name, 0, 30_000);
            *args.last_mut().expect("broker mode") = "--isolation-probe".into();
            self.cli(&args).await?;
            let (broker_process, broker) = self
                .live_probe(&broker_name)
                .await
                .map_err(|error| format!("broker isolation {error}"))?;
            probes.push(broker_process);
            validate_probe(&broker, &self.config)?;
            validate_pair(
                &worker,
                &broker,
                &isolation_identity(Path::new("/proc/self"))?,
            )?;
            Ok::<(), String>(())
        }
        .await;
        let cleanup = self.cleanup_probes(&record, probes).await;
        if let Err(error) = result {
            return Err(match cleanup {
                Ok(()) => error,
                Err(cleanup) => format!("{error}; probe cleanup failed: {cleanup}"),
            });
        }
        cleanup?;
        self.ready.store(true, Ordering::SeqCst);
        Ok(())
    }
    async fn cleanup_probes(
        &self,
        record: &Record,
        mut probes: Vec<tokio::process::Child>,
    ) -> Result<(), String> {
        // Keep attached engine clients and stdin alive until owned container
        // removal is complete. Dropping them first kills Podman mid-attachment
        // and can race conmon teardown on the deployed engine.
        let result = self.cleanup(record).await;
        for process in &mut probes {
            let _ = process.kill().await;
            let _ = tokio::time::timeout(Duration::from_secs(1), process.wait()).await;
        }
        result
    }
    async fn live_probe(&self, name: &str) -> Result<(tokio::process::Child, Value), String> {
        use tokio::io::{AsyncBufReadExt, AsyncReadExt};
        let mut command = self.command();
        command
            .args(["start", "--attach", "--interactive", name])
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        let mut child = command.spawn().map_err(|_| "probe process unavailable")?;
        let output = child.stdout.take().ok_or("probe output unavailable")?;
        let mut reader = tokio::io::BufReader::new(output).take(64 * 1024);
        let mut frame = Vec::new();
        tokio::time::timeout(Duration::from_secs(5), reader.read_until(b'\n', &mut frame))
            .await
            .map_err(|_| "probe output timeout")?
            .map_err(|_| "probe output unavailable")?;
        if frame.last() != Some(&b'\n') {
            return Err(failed_probe(&mut child, "probe frame missing or oversized").await);
        }
        let value = serde_json::from_slice(&frame).map_err(|_| "probe frame invalid")?;
        // stdin stays owned by the child, keeping both probes alive while
        // namespace identities are compared. Drop/cleanup closes both.
        Ok((child, value))
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
    fn broker_args(&self, name: &str, slot: usize, remaining_ms: u64) -> Vec<String> {
        let mut args =
            self.create_args(name, self.capacity + slot, 128 * 1024 * 1024, remaining_ms);
        for arg in &mut args {
            if arg == "--entrypoint=/usr/local/bin/python3" {
                *arg = "--entrypoint=/usr/local/bin/olo-toolgate-invocation-broker".into();
            }
        }
        *args.last_mut().expect("image") = self.config.broker_image.clone();
        args.push("--invocation-broker".into());
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
        if broker.require_isolated_broker().is_err() || broker.broker_policy().is_err() {
            return Ran::Crashed("isolated broker admission refused".into());
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
            let broker_name = format!("{name}-broker");
            let record = Record {
                name: name.clone(),
                slot,
                invocation_id: Some(id.clone()),
                broker_name: Some(broker_name.clone()),
            };
            if pool.journal(&record).is_ok() {
                // Neither executable receives authority until the entire pair
                // has a durable journal. Broker readiness precedes worker start.
                let remaining = input.deadline_unix_ms.saturating_sub(crate::now_ms());
                let mut child = None;
                let mut connection = None;
                let mut diagnostic = None;
                let setup = async {
                    pool.cli(&pool.broker_args(&broker_name, slot, remaining))
                        .await?;
                    let mut command = pool.command();
                    command
                        .args(["start", "--attach", "--interactive", &broker_name])
                        .stdin(std::process::Stdio::piped())
                        .stdout(std::process::Stdio::piped())
                        .stderr(std::process::Stdio::piped());
                    let mut process = command.spawn().map_err(|_| "broker process unavailable")?;
                    let reader = tokio::io::BufReader::new(
                        process.stdout.take().ok_or("broker stdout unavailable")?,
                    );
                    let writer = process.stdin.take().ok_or("broker stdin unavailable")?;
                    let mut errors = process
                        .stderr
                        .take()
                        .ok_or("broker diagnostics unavailable")?;
                    // Never log broker data; bound drain work and kill the pair
                    // if diagnostics exceed the fixed budget.
                    diagnostic = Some(tokio::spawn(async move {
                        use tokio::io::AsyncReadExt;
                        let mut bytes = [0u8; 4096];
                        let mut total = 0;
                        loop {
                            match errors.read(&mut bytes).await {
                                Ok(0) => return false,
                                Ok(n) => {
                                    total += n;
                                    if total > 64 * 1024 {
                                        return true;
                                    }
                                }
                                Err(_) => return true,
                            }
                        }
                    }));
                    child = Some(process);
                    connection = Some(
                        broker
                            .attach_broker(reader, writer)
                            .await
                            .map_err(str::to_string)?,
                    );
                    if *cancelled.borrow() {
                        return Err("invocation cancelled".into());
                    }
                    let mut args = pool.create_args(&name, slot, memory, remaining);
                    args.extend(strings(&["-I", "/opt/toolgate.pyz"]));
                    pool.cli(&args).await?;
                    pool.cli(&strings(&[
                        "cp",
                        &artifact.to_string_lossy(),
                        &format!("{name}:/opt/toolgate.pyz"),
                    ]))
                    .await?;
                    Ok::<(), String>(())
                }
                .await;
                if setup.is_ok() && !*cancelled.borrow() {
                    let connection = connection.as_ref().expect("broker readiness");
                    let mut failed = connection.failed();
                    let mut command = pool.command();
                    command.args(["start", "--attach", "--interactive", &name]);
                    if !*failed.borrow() && !broker.closed.load(Ordering::SeqCst) {
                        ran = tokio::select! {
                            biased;
                            _ = cancelled.changed() => Ran::TimedOut,
                            _ = failed.changed() => Ran::Crashed("isolated broker channel failed".into()),
                            _ = child.as_mut().expect("broker process").wait() => Ran::Crashed("isolated broker exited".into()),
                            _ = diagnostic.as_mut().expect("broker diagnostic drain") => Ran::Crashed("isolated broker diagnostics ended".into()),
                            result = crate::runner::exchange(command, &input, input.deadline_unix_ms, output, Some(&frames), &broker) => result,
                        };
                    }
                }
                broker.closed.store(true, Ordering::SeqCst);
                if let Some(connection) = connection {
                    // Drop the worker future before waiting for the channel
                    // mutex. Engine removal remains mandatory after timeout.
                    let _ =
                        tokio::time::timeout(Duration::from_millis(250), connection.finish()).await;
                }
                if pool.cleanup(&record).await.is_err() {
                    pool.poisoned.store(true, Ordering::SeqCst);
                    ran = Ran::Crashed("sandbox pair teardown unconfirmed; pool fenced".into());
                } else {
                    pool.slots.lock().unwrap_or_else(|e| e.into_inner())[slot] = false;
                }
                if let Some(mut process) = child {
                    let _ = process.kill().await;
                    let _ = tokio::time::timeout(Duration::from_secs(1), process.wait()).await;
                }
                if let Some(diagnostic) = diagnostic {
                    diagnostic.abort();
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
fn validate_service_properties(bytes: &[u8], parent: &str) -> Result<(), String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "invalid service properties")?;
    let mut properties = std::collections::BTreeMap::new();
    for line in text.lines() {
        let (key, value) = line.split_once('=').ok_or("invalid service property")?;
        if ![
            "ControlGroup",
            "Delegate",
            "KillMode",
            "KillSignal",
            "SendSIGKILL",
        ]
        .contains(&key)
            || properties.insert(key, value).is_some()
        {
            return Err("ambiguous service properties".into());
        }
    }
    if properties.get("ControlGroup") != Some(&parent)
        || properties.get("Delegate") != Some(&"yes")
        || properties.get("KillMode") != Some(&"control-group")
        || properties.get("KillSignal") != Some(&"9")
        || properties.get("SendSIGKILL") != Some(&"yes")
    {
        return Err(
            "OCI pool requires its exact delegated service with immediate control-group SIGKILL"
                .into(),
        );
    }
    Ok(())
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
// Probes carry no workload arguments, credentials or permits. Bound startup
// diagnostics without changing the frame deadline or accepting a failed probe.
async fn failed_probe(child: &mut tokio::process::Child, reason: &str) -> String {
    use tokio::io::AsyncReadExt;
    let status = child.try_wait().ok().flatten();
    let mut bytes = Vec::new();
    if let Some(stderr) = child.stderr.take() {
        let _ = tokio::time::timeout(
            Duration::from_millis(250),
            stderr.take(4096).read_to_end(&mut bytes),
        )
        .await;
    }
    let detail: String = String::from_utf8_lossy(&bytes)
        .chars()
        .filter(|c| !c.is_control() || *c == '\n' || *c == '\t')
        .take(1024)
        .collect();
    format!(
        "{reason} (engine exit {:?}): {detail}",
        status.and_then(|s| s.code())
    )
}

const PROBE: &str = r#"import os,json,socket
def rd(p):
 return open(p).read().strip()
status=rd('/proc/self/status')
denied=False
try: socket.socket(socket.AF_INET,socket.SOCK_STREAM)
except PermissionError: denied=True
print(json.dumps(dict(uid=os.getuid(),gid=os.getgid(),pid=os.getpid(),uidMap=rd("/proc/self/uid_map"),gidMap=rd("/proc/self/gid_map"),netns=os.readlink("/proc/self/ns/net"),userns=os.readlink("/proc/self/ns/user"),status=status,networkDenied=denied,memory=rd('/sys/fs/cgroup/memory.max'),swap=rd('/sys/fs/cgroup/memory.swap.max'),pids=rd('/sys/fs/cgroup/pids.max'),cpu=rd('/sys/fs/cgroup/cpu.max'),io=rd('/sys/fs/cgroup/io.max'))),flush=True)
input()
"#;
fn probe_read(path: &Path) -> Result<String, String> {
    std::fs::read_to_string(path)
        .map(|s| s.trim().to_string())
        .map_err(|error| {
            format!(
                "isolation probe file {} unavailable: {error}",
                path.display()
            )
        })
}
// Supervisor identity does not require resource files at the host cgroup root.
fn isolation_identity(proc_self: &Path) -> Result<Value, String> {
    let status = probe_read(&proc_self.join("status"))?;
    let number = |key: &str| -> Result<u64, String> {
        status
            .lines()
            .find_map(|line| line.strip_prefix(key))
            .and_then(|v| v.split_whitespace().next())
            .and_then(|v| v.parse().ok())
            .ok_or("isolation probe identity unavailable".into())
    };
    let namespace = |name: &str| {
        std::fs::read_link(proc_self.join("ns").join(name))
            .map(|p| p.to_string_lossy().into_owned())
            .map_err(|_| "isolation namespace unavailable".to_string())
    };
    Ok(json!({
        "uid": number("Uid:")?, "gid": number("Gid:")?, "pid": std::process::id(),
        "uidMap": probe_read(&proc_self.join("uid_map"))?,
        "gidMap": probe_read(&proc_self.join("gid_map"))?,
        "netns": namespace("net")?, "userns": namespace("user")?, "status": status
    }))
}
fn isolation_limits(cgroup: &Path) -> Result<Value, String> {
    Ok(json!({
        "memory": probe_read(&cgroup.join("memory.max"))?,
        "swap": probe_read(&cgroup.join("memory.swap.max"))?,
        "pids": probe_read(&cgroup.join("pids.max"))?,
        "cpu": probe_read(&cgroup.join("cpu.max"))?,
        "io": probe_read(&cgroup.join("io.max"))?
    }))
}
/// Trusted image qualification only; no policy, credential or Control endpoint.
pub fn isolation_probe() -> Result<Value, String> {
    let mut probe = isolation_identity(Path::new("/proc/self"))?;
    // Workers and brokers have private mounts rooted at their own cgroup.
    // Every resource witness remains mandatory for qualification.
    let limits = isolation_limits(Path::new("/sys/fs/cgroup"))?;
    probe
        .as_object_mut()
        .expect("identity object")
        .extend(limits.as_object().expect("limit object").clone());
    // Check the deployed filter without opening a network connection.
    probe["networkDenied"] = json!(
        std::net::TcpListener::bind("127.0.0.1:0").is_err_and(|e| e.raw_os_error() == Some(1))
    );
    Ok(probe)
}
fn validate_pair(worker: &Value, broker: &Value, supervisor: &Value) -> Result<(), String> {
    for key in ["uidMap", "gidMap", "netns", "userns"] {
        let w = worker[key]
            .as_str()
            .filter(|s| !s.is_empty())
            .ok_or("worker identity missing")?;
        let b = broker[key]
            .as_str()
            .filter(|s| !s.is_empty())
            .ok_or("broker identity missing")?;
        let s = supervisor[key]
            .as_str()
            .filter(|s| !s.is_empty())
            .ok_or("supervisor identity missing")?;
        if w == b || w == s || b == s {
            return Err(format!(
                "broker/worker/supervisor {key} isolation boundary missing"
            ));
        }
    }
    if broker["gid"] != 100 || worker["gid"] != 100 {
        return Err("unprivileged sandbox group required".into());
    }
    Ok(())
}
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
            broker_image: format!("localhost/toolgate-broker@sha256:{}", "b".repeat(64)),
            io_device: "8:0".into(),
            io_bytes_per_second: 64 * 1024 * 1024,
            scratch_bytes: 8 * 1024 * 1024,
        }
    }
    #[test]
    fn weak_foreign_missing_or_ambiguous_service_properties_refuse_readiness() {
        let valid = "ControlGroup=/system.slice/toolgate.service\\nDelegate=yes\\nKillMode=control-group\\nKillSignal=9\\nSendSIGKILL=yes\\n".replace("\\n","\n");
        assert!(
            validate_service_properties(valid.as_bytes(), "/system.slice/toolgate.service").is_ok()
        );
        for altered in [
            valid.replace("KillMode=control-group", "KillMode=process"),
            valid.replace("KillSignal=9", "KillSignal=15"),
            valid.replace("SendSIGKILL=yes", "SendSIGKILL=no"),
            valid.replace("Delegate=yes", "Delegate=no"),
            valid.replace(
                "ControlGroup=/system.slice/toolgate.service",
                "ControlGroup=/system.slice/foreign.service",
            ),
            valid.replace("KillSignal=9\n", ""),
            format!("{valid}KillSignal=9\n"),
        ] {
            assert!(validate_service_properties(
                altered.as_bytes(),
                "/system.slice/toolgate.service"
            )
            .is_err());
        }
    }
    #[test]
    fn mutable_images_and_invalid_control_paths_are_refused() {
        let mut c = config();
        assert!(c.validate().is_ok());
        c.python_image = "python:latest".into();
        assert!(c.validate().is_err());
        c = config();
        c.broker_image = "localhost/broker:latest".into();
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
        let broker = pool.broker_args("owned-broker", 0, 1200);
        let other_broker = pool.broker_args("other-broker", 1, 1200);
        assert!(broker.iter().any(|v| v == "--uidmap=0:2049:1024"));
        assert!(other_broker.iter().any(|v| v == "--uidmap=0:3073:1024"));
        assert!(broker.iter().any(|v| v == "--network=none"));
        assert!(broker
            .iter()
            .any(|v| v == "--entrypoint=/usr/local/bin/olo-toolgate-invocation-broker"));
        assert_eq!(broker.last().unwrap(), "--invocation-broker");
        assert!(!broker
            .iter()
            .any(|v| v.starts_with("--volume") || v.starts_with("--mount")));

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
    #[test]
    fn paired_probe_requires_three_distinct_identity_and_network_boundaries() {
        let worker = json!({"uidMap":"worker-uid","gidMap":"worker-gid","netns":"worker-net","userns":"worker-user","gid":100});
        let broker = json!({"uidMap":"broker-uid","gidMap":"broker-gid","netns":"broker-net","userns":"broker-user","gid":100});
        let supervisor = json!({"uidMap":"host-uid","gidMap":"host-gid","netns":"host-net","userns":"host-user"});
        assert!(validate_pair(&worker, &broker, &supervisor).is_ok());
        for key in ["uidMap", "gidMap", "netns", "userns"] {
            let mut same_worker = broker.clone();
            same_worker[key] = worker[key].clone();
            assert!(validate_pair(&worker, &same_worker, &supervisor).is_err());
            let mut same_supervisor = broker.clone();
            same_supervisor[key] = supervisor[key].clone();
            assert!(validate_pair(&worker, &same_supervisor, &supervisor).is_err());
            let mut missing = broker.clone();
            missing.as_object_mut().unwrap().remove(key);
            assert!(validate_pair(&worker, &missing, &supervisor).is_err());
        }
    }
    #[tokio::test]
    async fn qualification_removes_containers_before_terminating_attached_probes() {
        use std::os::unix::fs::PermissionsExt;
        let root = std::env::temp_dir().join(format!("tg-probe-order-{}", crate::random_hex(16)));
        std::fs::create_dir_all(&root).unwrap();
        let mut probe = tokio::process::Command::new("/usr/bin/python3")
            .args(["-c", "import sys; sys.stdin.read()"])
            .stdin(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let pid = probe.id().unwrap();
        let engine = root.join("engine");
        std::fs::write(&engine, format!(
            "#!/usr/bin/python3\nimport os,sys,pathlib\nmarker=pathlib.Path(__file__).parent/('removed-'+sys.argv[-1])\nif 'rm' in sys.argv:\n os.kill({pid},0)\n marker.touch()\n sys.exit(0)\nif 'exists' in sys.argv: sys.exit(1 if marker.exists() else 2)\nsys.exit(2)\n"
        )).unwrap();
        std::fs::set_permissions(&engine, std::fs::Permissions::from_mode(0o700)).unwrap();
        let mut configuration = config();
        configuration.podman_path = engine;
        let pool = Sandbox::open(configuration, &root, "probe-order", 1).unwrap();
        let record = Record {
            name: "probe-order-worker".into(),
            slot: 0,
            invocation_id: None,
            broker_name: Some("probe-order-broker".into()),
        };
        pool.journal(&record).unwrap();
        assert!(probe.try_wait().unwrap().is_none());
        pool.cleanup_probes(&record, vec![probe]).await.unwrap();
        assert!(!Path::new(&format!("/proc/{pid}")).exists());
        assert!(!pool.directory.join("probe-order-worker.json").exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn supervisor_identity_requires_proc_witnesses_without_cgroup_limits() {
        let root = std::env::temp_dir().join(format!("tg-identity-{}", crate::random_hex(16)));
        std::fs::create_dir_all(root.join("ns")).unwrap();
        std::fs::write(root.join("status"), "Uid:\t1000\nGid:\t1000\n").unwrap();
        for name in ["uid_map", "gid_map"] {
            std::fs::write(root.join(name), "0 0 4294967295").unwrap();
        }
        for name in ["net", "user"] {
            std::os::unix::fs::symlink(format!("{name}:[123]"), root.join("ns").join(name))
                .unwrap();
        }
        let identity = isolation_identity(&root).unwrap();
        assert_eq!(identity["uid"], 1000);
        assert_eq!(identity["netns"], "net:[123]");
        for key in ["memory", "swap", "pids", "cpu", "io", "networkDenied"] {
            assert!(identity.get(key).is_none());
        }
        std::fs::remove_file(root.join("uid_map")).unwrap();
        assert!(isolation_identity(&root).unwrap_err().contains("uid_map"));
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn contained_probe_requires_every_resource_file_without_defaults() {
        let root = std::env::temp_dir().join(format!("tg-limits-{}", crate::random_hex(16)));
        std::fs::create_dir_all(&root).unwrap();
        let files = [
            ("memory.max", "134217728"),
            ("memory.swap.max", "0"),
            ("pids.max", "32"),
            ("cpu.max", "100000 100000"),
            ("io.max", "8:0 rbps=67108864 wbps=67108864"),
        ];
        for (name, value) in files {
            std::fs::write(root.join(name), value).unwrap();
        }
        let limits = isolation_limits(&root).unwrap();
        assert_eq!(limits["memory"], "134217728");
        assert_eq!(limits["io"], files[4].1);
        for (name, value) in files {
            std::fs::remove_file(root.join(name)).unwrap();
            assert!(isolation_limits(&root).unwrap_err().contains(name));
            std::fs::write(root.join(name), value).unwrap();
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn failed_probe_retains_bounded_startup_error_and_engine_status() {
        let mut child = tokio::process::Command::new("/bin/sh")
            .args(["-c", "printf 'fixture startup failure' >&2; exit 17"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        child.wait().await.unwrap();
        let detail = failed_probe(&mut child, "probe frame missing or oversized").await;
        assert!(detail.contains("engine exit Some(17)"));
        assert!(detail.contains("fixture startup failure"));
        let mut child = tokio::process::Command::new("/bin/sh")
            .args(["-c", "printf '%02048d' 1 >&2; exit 23"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        child.wait().await.unwrap();
        let detail = failed_probe(&mut child, "missing").await;
        assert!(detail.contains("engine exit Some(23)"));
        assert!(
            detail.len() <= 1100,
            "startup diagnostic must remain bounded"
        );
    }

    #[tokio::test]
    #[ignore = "requires the delegated OCI conformance service"]
    async fn restart_reconciles_an_owned_running_container_before_reopening_the_pool() {
        let config = Config {
            python_image: std::env::var("TOOLGATE_SANDBOX_IMAGE").expect("pinned image required"),
            broker_image: std::env::var("TOOLGATE_SANDBOX_BROKER_IMAGE")
                .expect("pinned broker image required"),
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
                broker_name: Some(format!("{name}-broker")),
            })
            .unwrap();
        let mut args = first.create_args(&name, 0, 128 * 1024 * 1024, 60_000);
        args.extend(strings(&["-I", "-c", "import time; time.sleep(60)"]));
        first.cli(&args).await.unwrap();
        first.cli(&strings(&["start", &name])).await.unwrap();
        let broker_name = format!("{name}-broker");
        first
            .cli(&first.broker_args(&broker_name, 0, 60_000))
            .await
            .unwrap();
        // Created-but-not-started broker is still an owned lifecycle artifact.
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
        let mut exists = restarted.command();
        assert_eq!(
            exists
                .args(["container", "exists", &broker_name])
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
