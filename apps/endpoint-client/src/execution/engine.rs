// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Trusted local engine boundary: no host shell, mounts, proxy, network or secrets.
use crate::{Failure, Result};
use olo_toolgate_contracts::LocalRuntimeLimits;
use std::{
    path::PathBuf,
    process::Stdio,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};

// Docker info/inspect can include cold CLI plugin discovery on native runners.
// Keep metadata bounded separately from the tool's execution and output limits.
const METADATA_TIMEOUT: Duration = Duration::from_secs(10);
pub(super) const CONTAINER_CREATE_TIMEOUT: Duration = Duration::from_secs(10);
// The first version probe can need a cold image snapshot on a shared runner.
// Preparation grants no tool execution authority; invocations retain their
// existing startup budget and authorization deadline reservation.
const PROBE_CREATE_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone)]
pub struct DockerEngine {
    pub(crate) binary: PathBuf,
    pub(crate) endpoint: String,
    pub(crate) directory: PathBuf,
    profile: PathBuf,
    owner: String,
    pub(crate) dirty: Arc<AtomicBool>,
}
pub struct Captured {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
}
/// Answers one stdout line of tool protocol v2: the `BROKER_RESPONSE` line for a `BROKER_REQUEST`, or nothing.
pub type Broker<'a> = &'a (dyn Fn(&[u8]) -> Option<Vec<u8>> + Send + Sync);
/// No broker: every request goes unanswered, as for a version probe.
pub fn no_broker(_line: &[u8]) -> Option<Vec<u8>> {
    None
}

/// OS/engine boundary. Future Windows/VM/WASM implementations must enforce
/// the same limits before claiming a supported capability.
pub trait SandboxPort: Send + Sync {
    fn check(&self) -> crate::transport::Call<'_, ()>;
    fn recover(&self) -> crate::transport::Call<'_, ()>;
    fn present<'a>(&'a self, image: &'a str) -> crate::transport::Call<'a, bool>;
    fn pull<'a>(&'a self, image: &'a str, seconds: u64) -> crate::transport::Call<'a, ()>;
    fn dirty(&self) -> bool;
    /// Runs the command over tool protocol v2: writes `input`, answers broker requests through `broker` while
    /// stdout streams, and returns everything the process wrote.
    fn run<'a>(
        &'a self,
        image: &'a str,
        command: &'a [String],
        input: &'a [u8],
        limits: &'a LocalRuntimeLimits,
        broker: Broker<'a>,
    ) -> crate::transport::Call<'a, Captured>;
    fn probe_version<'a>(
        &'a self,
        image: &'a str,
        command: &'a [String],
        limits: &'a LocalRuntimeLimits,
    ) -> crate::transport::Call<'a, Captured> {
        self.run(image, command, b"", limits, &no_broker)
    }
}
impl SandboxPort for DockerEngine {
    fn probe_version<'a>(
        &'a self,
        image: &'a str,
        command: &'a [String],
        limits: &'a LocalRuntimeLimits,
    ) -> crate::transport::Call<'a, Captured> {
        Box::pin(self.run_with_create_timeout(
            image,
            command,
            b"",
            limits,
            PROBE_CREATE_TIMEOUT,
            &no_broker,
        ))
    }
    fn check(&self) -> crate::transport::Call<'_, ()> {
        Box::pin(DockerEngine::check(self))
    }
    fn recover(&self) -> crate::transport::Call<'_, ()> {
        Box::pin(DockerEngine::recover(self))
    }
    fn present<'a>(&'a self, image: &'a str) -> crate::transport::Call<'a, bool> {
        Box::pin(DockerEngine::present(self, image))
    }
    fn pull<'a>(&'a self, image: &'a str, seconds: u64) -> crate::transport::Call<'a, ()> {
        Box::pin(DockerEngine::pull(self, image, seconds))
    }
    fn dirty(&self) -> bool {
        self.dirty.load(Ordering::SeqCst)
    }
    fn run<'a>(
        &'a self,
        image: &'a str,
        command: &'a [String],
        input: &'a [u8],
        limits: &'a LocalRuntimeLimits,
        broker: Broker<'a>,
    ) -> crate::transport::Call<'a, Captured> {
        Box::pin(DockerEngine::run(
            self, image, command, input, limits, broker,
        ))
    }
}

/// A local cancellation leaves a cleanup guard, never an untracked daemon job.
struct ContainerGuard {
    engine: Arc<DockerEngine>,
    name: String,
    armed: bool,
}
impl Drop for ContainerGuard {
    fn drop(&mut self) {
        if self.armed {
            self.engine.dirty.store(true, Ordering::SeqCst);
            let engine = self.engine.clone();
            let name = self.name.clone();
            if let Ok(handle) = tokio::runtime::Handle::try_current() {
                handle.spawn(async move {
                    let _ = engine.remove(&name).await;
                });
            }
        }
    }
}

impl DockerEngine {
    pub fn new(binary: PathBuf, endpoint: String, directory: PathBuf) -> Result<Arc<Self>> {
        if !binary.is_absolute() || !directory.is_absolute() {
            return Err(Failure::Validation);
        }
        crate::storage::check_parents(&binary)?;
        crate::storage::check_owned(&binary, false)?;
        if !std::fs::metadata(&binary)
            .map_err(|_| Failure::Unavailable)?
            .is_file()
        {
            return Err(Failure::Validation);
        }
        #[cfg(unix)]
        if !endpoint.starts_with("unix:///") || endpoint.contains(['\n', '\r', '\0', '?', '#']) {
            return Err(Failure::Validation);
        }
        #[cfg(windows)]
        if ![
            "npipe:////./pipe/docker_engine",
            "npipe:////./pipe/dockerDesktopLinuxEngine",
        ]
        .contains(&endpoint.as_str())
        {
            return Err(Failure::Validation);
        }
        let _ = crate::storage::ProtectedStore::open(directory.clone())?;
        // Files are immutable to ordinary IPC callers; no registry login/config is inherited.
        let profile = directory.join("sandbox.json");
        private_file(
            &profile,
            include_bytes!("../../packaging/runtime-seccomp.json"),
        )?;
        let configuration = directory.join("config.json");
        if configuration.exists()
            && crate::storage::read_owned(&configuration, 65536, true)? != b"{\"auths\":{}}\n"
        {
            return Err(Failure::Unauthorized);
        }
        private_file(&configuration, b"{\"auths\":{}}\n")?;
        let owner = crate::digest(format!("{}:{}", endpoint, directory.display()).as_bytes());
        Ok(Arc::new(Self {
            binary,
            endpoint,
            directory,
            profile,
            owner,
            dirty: Arc::new(AtomicBool::new(false)),
        }))
    }
    fn command(&self, args: &[String]) -> tokio::process::Command {
        let mut command = tokio::process::Command::new(&self.binary);
        command
            .arg("--host")
            .arg(&self.endpoint)
            .arg("--config")
            .arg(&self.directory)
            .args(args)
            .env_clear()
            .env("GOMAXPROCS", "2")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        {
            command.env("SystemRoot", r"C:\Windows");
            command.creation_flags(0x08000000);
        }
        command
    }
    pub(crate) async fn capture(
        &self,
        args: &[String],
        input: &[u8],
        deadline: Duration,
        max: usize,
    ) -> Result<Captured> {
        let mut child = self
            .command(args)
            .spawn()
            .map_err(|_| Failure::Unavailable)?;
        let mut stdin = child.stdin.take().ok_or(Failure::Unavailable)?;
        let stdout = child.stdout.take().ok_or(Failure::Unavailable)?;
        let stderr = child.stderr.take().ok_or(Failure::Unavailable)?;
        let result = tokio::time::timeout(deadline, async {
            let writer = async move {
                stdin
                    .write_all(input)
                    .await
                    .map_err(|_| Failure::Unavailable)?;
                stdin.shutdown().await.map_err(|_| Failure::Unavailable)?;
                drop(stdin);
                Ok(())
            };
            let ((), stdout, stderr, status) =
                tokio::try_join!(writer, bounded(stdout, max), bounded(stderr, 8192), async {
                    child.wait().await.map_err(|_| Failure::Unavailable)
                })?;
            if !status.success() {
                return Err(Failure::Unavailable);
            }
            Ok(Captured { stdout, stderr })
        })
        .await
        .unwrap_or(Err(Failure::Expired));
        if let Err(failure) = result {
            let _ = child.kill().await;
            // The command's name and failure class are safe; arguments and arbitrary
            // engine/tool output can contain private data and stay redacted.
            tracing::warn!(event="runtime_engine_command",operation=args.first().map(String::as_str).unwrap_or("unknown"),error=?failure);
        }
        result
    }
    /// Like `capture`, but stdin stays open after `input`: each complete stdout line goes to `broker`, and its answer
    /// is written back on stdin while the tool runs. Stdin closes when stdout ends.
    async fn converse(
        &self,
        args: &[String],
        input: &[u8],
        deadline: Duration,
        max: usize,
        broker: Broker<'_>,
    ) -> Result<Captured> {
        let mut child = self
            .command(args)
            .spawn()
            .map_err(|_| Failure::Unavailable)?;
        let mut stdin = child.stdin.take().ok_or(Failure::Unavailable)?;
        let mut stdout = child.stdout.take().ok_or(Failure::Unavailable)?;
        let stderr = child.stderr.take().ok_or(Failure::Unavailable)?;
        let result = tokio::time::timeout(deadline, async {
            let talk = async {
                stdin
                    .write_all(input)
                    .await
                    .map_err(|_| Failure::Unavailable)?;
                stdin.flush().await.map_err(|_| Failure::Unavailable)?;
                let mut output = Vec::new();
                let mut scanned = 0;
                let mut buffer = [0; 4096];
                loop {
                    let size = stdout
                        .read(&mut buffer)
                        .await
                        .map_err(|_| Failure::Unavailable)?;
                    if size == 0 {
                        break;
                    }
                    if output.len().saturating_add(size) > max {
                        return Err(Failure::Validation);
                    }
                    output.extend_from_slice(&buffer[..size]);
                    while let Some(end) = output[scanned..].iter().position(|b| *b == b'\n') {
                        let line = output[scanned..scanned + end].to_vec();
                        scanned += end + 1;
                        if let Some(mut answer) = broker(&line) {
                            answer.push(b'\n');
                            // A tool that already exited cannot read its answer; its result decides.
                            let _ = stdin.write_all(&answer).await;
                            let _ = stdin.flush().await;
                        }
                    }
                }
                let _ = stdin.shutdown().await;
                Ok(output)
            };
            let (stdout, stderr, status) = tokio::try_join!(talk, bounded(stderr, 8192), async {
                child.wait().await.map_err(|_| Failure::Unavailable)
            })?;
            if !status.success() {
                return Err(Failure::Unavailable);
            }
            Ok(Captured { stdout, stderr })
        })
        .await
        .unwrap_or(Err(Failure::Expired));
        if let Err(failure) = result {
            let _ = child.kill().await;
            tracing::warn!(event="runtime_engine_command",operation=args.first().map(String::as_str).unwrap_or("unknown"),error=?failure);
        }
        result
    }
    /// Engine resource/security support is required, not an optional best effort.
    pub async fn check(&self) -> Result<()> {
        let data = self
            .capture(
                &strings(&["info", "--format", "{{json .}}"]),
                b"",
                METADATA_TIMEOUT,
                65536,
            )
            .await?;
        let info = crate::json::strict_json(&data.stdout).map_err(|_| Failure::Validation)?;
        if info["OSType"] != "linux"
            || info["MemoryLimit"] != true
            || info["SwapLimit"] != true
            || info["PidsLimit"] != true
            || info["CpuCfsQuota"] != true
            || !info["SecurityOptions"].as_array().is_some_and(|s| {
                s.iter()
                    .any(|v| v.as_str().is_some_and(|x| x.starts_with("name=seccomp")))
            })
        {
            return Err(Failure::Unsupported);
        }
        Ok(())
    }
    pub async fn present(&self, image: &str) -> Result<bool> {
        match self
            .capture(
                &strings(&["image", "inspect", image, "--format", "{{json .}}"]),
                b"",
                METADATA_TIMEOUT,
                65536,
            )
            .await
        {
            Ok(data) => {
                let info =
                    crate::json::strict_json(&data.stdout).map_err(|_| Failure::Validation)?;
                let pinned = info["Id"].as_str() == Some(image)
                    || info["RepoDigests"].as_array().is_some_and(|v| {
                        v.iter().any(|x| {
                            x.as_str()
                                .is_some_and(|s| image.rsplit('@').next() == s.rsplit('@').next())
                        })
                    });
                if !pinned
                    || info["Os"] != "linux"
                    || info["Config"]["Volumes"]
                        .as_object()
                        .is_some_and(|v| !v.is_empty())
                {
                    return Err(Failure::Unauthorized);
                }
                Ok(true)
            }
            Err(Failure::Unavailable) => Ok(false),
            Err(e) => Err(e),
        }
    }
    pub async fn pull(&self, image: &str, seconds: u64) -> Result<()> {
        if image.starts_with("sha256:") {
            return Err(Failure::Unavailable);
        }
        // Progress is bounded and redacted just like invocation output.
        self.capture(
            &strings(&["pull", "--quiet", image]),
            b"",
            Duration::from_secs(seconds),
            65536,
        )
        .await?;
        if !self.present(image).await? {
            return Err(Failure::Unauthorized);
        }
        Ok(())
    }
    /// Reap only this protected configuration's containers after crash/cancellation.
    pub async fn recover(&self) -> Result<()> {
        let result = self
            .capture(
                &strings(&[
                    "ps",
                    "--all",
                    "--quiet",
                    "--filter",
                    &format!("label=io.ololabs.toolgate.owner={}", self.owner),
                ]),
                b"",
                Duration::from_secs(3),
                4096,
            )
            .await?;
        let ids = std::str::from_utf8(&result.stdout).map_err(|_| Failure::Validation)?;
        for id in ids.lines() {
            if id.len() != 12 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
                return Err(Failure::Validation);
            }
            self.remove(id).await?;
        }
        self.dirty.store(false, Ordering::SeqCst);
        Ok(())
    }
    async fn remove(&self, name: &str) -> Result<()> {
        self.capture(
            &strings(&["rm", "--force", name]),
            b"",
            Duration::from_secs(3),
            4096,
        )
        .await
        .map(|_| ())
    }
    /// Each launch uses a fresh bounded disposable filesystem, non-root identity
    /// and a process filter. No arbitrary Docker flags cross this boundary.
    pub async fn run(
        &self,
        image: &str,
        command: &[String],
        input: &[u8],
        limits: &LocalRuntimeLimits,
        broker: Broker<'_>,
    ) -> Result<Captured> {
        self.run_with_create_timeout(
            image,
            command,
            input,
            limits,
            CONTAINER_CREATE_TIMEOUT,
            broker,
        )
        .await
    }
    async fn run_with_create_timeout(
        &self,
        image: &str,
        command: &[String],
        input: &[u8],
        limits: &LocalRuntimeLimits,
        create_timeout: Duration,
        broker: Broker<'_>,
    ) -> Result<Captured> {
        if self.dirty.load(Ordering::SeqCst) {
            return Err(Failure::Unavailable);
        }
        let mut nonce = [0u8; 16];
        ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut nonce)
            .map_err(|_| Failure::Unavailable)?;
        let name = format!("toolgate-run-{}", &crate::digest(&nonce)[..24]);
        // Arm before create: a timed-out CLI might still have created its container.
        let mut guard = ContainerGuard {
            engine: Arc::new(self.clone()),
            name: name.clone(),
            armed: true,
        };
        let mut args = strings(&[
            "create",
            "--name",
            &name,
            "--label",
            &format!("io.ololabs.toolgate.owner={}", self.owner),
            "--pull",
            "never",
            "--read-only",
            "--network",
            "none",
            "--user",
            "65532:65532",
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges=true",
            "--security-opt",
            &format!("seccomp={}", self.profile.display()),
            "--memory",
            &format!("{}m", limits.memory_mi_b),
            "--memory-swap",
            &format!("{}m", limits.memory_mi_b),
            "--pids-limit",
            "64",
            "--cpus",
            "1",
            "--ulimit",
            "nofile=256:256",
            "--stop-timeout",
            "1",
            "--log-driver",
            "none",
            "--workdir",
            "/work",
            "--tmpfs",
            "/work:rw,noexec,nosuid,nodev,size=8388608,mode=0700,uid=65532,gid=65532",
            "--tmpfs",
            "/tmp:rw,noexec,nosuid,nodev,size=8388608,mode=0700,uid=65532,gid=65532",
            "--env",
            "LD_PRELOAD=",
            "--env",
            "LD_LIBRARY_PATH=",
            "--interactive",
            "--entrypoint",
            &command[0],
            image,
        ]);
        args.extend_from_slice(&command[1..]);
        let result = async {
            self.capture(&args, b"", create_timeout, 4096).await?;
            self.converse(
                &strings(&["start", "--attach", "--interactive", &name]),
                input,
                Duration::from_millis(limits.timeout_ms),
                limits.max_output_bytes as usize,
                broker,
            )
            .await
        }
        .await;
        if self.remove(&name).await.is_err() {
            self.dirty.store(true, Ordering::SeqCst);
            return Err(Failure::Unavailable);
        }
        guard.armed = false;
        result
    }
}
fn private_file(path: &std::path::Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write;
    if path.exists() {
        crate::storage::check_owned(path, true)?;
    }
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600).custom_flags(libc::O_NOFOLLOW);
    }
    let mut file = options.open(path).map_err(|_| Failure::Unavailable)?;
    file.write_all(bytes).map_err(|_| Failure::Unavailable)?;
    file.sync_all().map_err(|_| Failure::Unavailable)?;
    #[cfg(windows)]
    crate::platform::windows::protect_acl(path)?;
    Ok(())
}
async fn bounded<R: AsyncRead + Unpin>(mut stream: R, max: usize) -> Result<Vec<u8>> {
    let mut output = Vec::new();
    let mut buffer = [0; 4096];
    loop {
        let size = stream
            .read(&mut buffer)
            .await
            .map_err(|_| Failure::Unavailable)?;
        if size == 0 {
            return Ok(output);
        }
        if output.len().saturating_add(size) > max {
            return Err(Failure::Validation);
        }
        output.extend_from_slice(&buffer[..size]);
    }
}
pub fn strings(values: &[&str]) -> Vec<String> {
    values.iter().map(|v| (*v).to_owned()).collect()
}
