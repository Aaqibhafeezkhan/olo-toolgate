// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Runs one invocation's process over tool protocol v2, the one protocol every executor speaks (ADR 027): one
//! `ToolInput` line and then only `BROKER_RESPONSE` lines on stdin; response and `BROKER_REQUEST` frames ending with one
//! `ResultFrame` on stdout. The process gets a clean environment and its own directory, and nothing else of the
//! host's; it is killed at the invocation deadline.
//!
//! Launchers are per runtime. Adding a language means a launcher here and an entry in
//! `store::RUNTIMES`; the protocol, the broker and admission stay the same.

use olo_toolgate_contracts::v2::{ResultFrame, ToolInput};
use std::path::Path;
use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};

const MAX_STDERR: usize = 64 * 1024;

/// How the process ended.
#[derive(Debug)]
pub enum Ran {
    Frame(ResultFrame),
    /// It exited or was killed without a usable result frame.
    Crashed(String),
    TimedOut,
}

/// What a launcher needs to start the tool.
pub struct Launch<'a> {
    pub runtime_id: &'a str,
    /// The tool's self-contained executable: for the JVM, a fat jar.
    pub executable: &'a Path,
    pub entry_point: &'a str,
    pub sandbox_dir: &'a Path,
    pub java_path: &'a Path,
    pub python_path: &'a Path,
    pub memory_bytes: u64,
    pub max_output_bytes: usize,
}

fn command(launch: &Launch<'_>) -> Result<tokio::process::Command, String> {
    match launch.runtime_id {
        "python" => {
            if launch.entry_point != "toolgate.runtime"
                || launch.executable.extension().and_then(|e| e.to_str()) != Some("pyz")
            {
                return Err("A Python tool must use the SDK runtime and one pyz artifact".into());
            }
            let mut c = tokio::process::Command::new(launch.python_path);
            c.arg("-I").arg(launch.executable);
            Ok(c)
        }
        "jvm" => {
            let mut c = tokio::process::Command::new(launch.java_path);
            c.arg(format!(
                "-Xmx{}m",
                (launch.memory_bytes / (1024 * 1024)).max(16)
            ))
            .arg("-XX:+ExitOnOutOfMemoryError")
            .arg("-XX:-UsePerfData")
            .arg(format!(
                "-Djava.io.tmpdir={}",
                launch.sandbox_dir.join("tmp").display()
            ))
            .arg("-cp")
            .arg(launch.executable)
            .arg(launch.entry_point);
            Ok(c)
        }
        other => Err(format!("no launcher for runtime {other}")),
    }
}

/// Brokered operations one invocation may make.
pub const MAX_BROKER_CALLS: usize = 1024;

/// Starts the process, hands it `input`, answers its broker requests on stdin, and waits for its result until
/// `deadline_unix_ms`. Other complete stdout lines are offered to `frames` as they arrive, so response frames reach
/// Control while the tool runs.
pub async fn run(
    launch: &Launch<'_>,
    input: &ToolInput,
    deadline_unix_ms: u64,
    frames: Option<&crate::events::Sink>,
    broker: &crate::broker::Invocation,
) -> Ran {
    let mut command = match command(launch) {
        Ok(c) => c,
        Err(e) => return Ran::Crashed(e),
    };
    if let Err(e) = std::fs::create_dir_all(launch.sandbox_dir.join("tmp")) {
        return Ran::Crashed(format!("cannot prepare the sandbox: {e}"));
    }
    command
        .env_clear()
        .env("HOME", launch.sandbox_dir)
        .env("LANG", "C.UTF-8")
        .env("TOOLGATE_PROTOCOL", "2")
        .env("TOOLGATE_EXECUTION_TARGET", "SERVER")
        .current_dir(launch.sandbox_dir);
    exchange(
        command,
        input,
        deadline_unix_ms,
        launch.max_output_bytes,
        frames,
        broker,
    )
    .await
}

/// The same bounded stdio protocol for a native child or an attached OCI worker.
pub(crate) async fn exchange(
    mut command: tokio::process::Command,
    input: &ToolInput,
    deadline_unix_ms: u64,
    max_output_bytes: usize,
    frames: Option<&crate::events::Sink>,
    broker: &crate::broker::Invocation,
) -> Ran {
    command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(e) => return Ran::Crashed(format!("cannot start the {} runtime: {e}", "worker")),
    };
    let mut line = match serde_json::to_vec(input) {
        Ok(line) => line,
        Err(e) => return Ran::Crashed(e.to_string()),
    };
    line.push(b'\n');
    let mut stdin = child.stdin.take().expect("piped");
    let stdout = child.stdout.take().expect("piped");
    let stderr = child.stderr.take().expect("piped");
    // The terminal frame carries maxOutputBytes plus its envelope. Reserve a bounded
    // additional budget for SDK response frames; exceeding it fails the run explicitly.
    let out_cap = max_output_bytes + 576 * 1024;
    let strict = broker.isolated_worker_protocol();
    let work = async {
        let _ = stdin.write_all(&line).await;
        let _ = stdin.flush().await;
        let (out, err) = {
            let read_out = converse(
                stdout,
                &mut stdin,
                out_cap,
                max_output_bytes,
                frames,
                broker,
            );
            let read_err = bounded(stderr, MAX_STDERR);
            tokio::pin!(read_out, read_err);
            tokio::select! {
                out=&mut read_out=>{
                    if out.fault.is_some() {
                        let _=child.kill().await;
                        (out,(Vec::new(),false))
                    } else { (out,read_err.await) }
                },
                err=&mut read_err=>{
                    if err.1 {
                        let _=child.kill().await;
                        // Drop the outstanding stdout/broker future immediately;
                        // waiting for it could keep target authority and a socket
                        // alive until the full invocation deadline.
                        (Transcript{bytes:Vec::new(),fault:Some("worker exceeded its diagnostic output budget")},err)
                    } else { (read_out.await,err) }
                },
            }
        };
        drop(stdin);
        let status = child.wait().await;
        (out, err, status)
    };
    let left = Duration::from_millis(deadline_unix_ms.saturating_sub(crate::now_ms()));
    let (out, err, status) = match tokio::time::timeout(left, work).await {
        Ok(done) => done,
        // Dropping the future drops the child, which kills it.
        Err(_) => return Ran::TimedOut,
    };
    if !err.0.is_empty() {
        tracing::debug!(
            bytes = err.0.len(),
            truncated = err.1,
            "tool diagnostics received"
        );
    }
    if let Some(fault) = out.fault {
        return Ran::Crashed(fault.into());
    }
    if strict && !status.as_ref().is_ok_and(|s| s.success()) {
        return Ran::Crashed("worker exited unsuccessfully".into());
    }
    if err.1 {
        return Ran::Crashed("the tool exceeded its diagnostic output budget".into());
    }
    let text = String::from_utf8_lossy(&out.bytes);
    let last = text
        .lines()
        .rev()
        .find(|l| !l.trim().is_empty())
        .unwrap_or_default();
    match serde_json::from_str::<ResultFrame>(last) {
        Ok(frame) => Ran::Frame(frame),
        Err(e) => Ran::Crashed(match status {
            Ok(s) if !s.success() => format!("the tool exited with {s} and no result frame"),
            _ => format!("the tool's last line is not a result frame: {e}"),
        }),
    }
}

/// Reads stdout line by line, at most `cap` bytes: answers each `BROKER_REQUEST` on stdin and offers every other line
/// to `lines`. Returns everything read, and whether the cap was exceeded.
struct Transcript {
    bytes: Vec<u8>,
    fault: Option<&'static str>,
}
async fn converse<R: AsyncRead + Unpin, W: tokio::io::AsyncWrite + Unpin>(
    mut reader: R,
    stdin: &mut W,
    cap: usize,
    output_limit: usize,
    lines: Option<&crate::events::Sink>,
    broker: &crate::broker::Invocation,
) -> Transcript {
    let mut protocol = broker
        .isolated_worker_protocol()
        .then(|| crate::worker_protocol::Protocol::new(output_limit));
    let mut out = Vec::new();
    let mut scanned = 0;
    let mut calls = 0;
    let mut chunk = [0u8; 16 * 1024];
    loop {
        match reader.read(&mut chunk).await {
            Ok(0) => {
                let fault = protocol
                    .as_mut()
                    .and_then(|p| p.finish(&out[scanned..]).err());
                return Transcript { bytes: out, fault };
            }
            Err(_) => {
                return Transcript {
                    bytes: out,
                    fault: Some("worker stdout read failed"),
                }
            }
            Ok(n) => {
                if out.len() + n > cap {
                    return Transcript {
                        bytes: out,
                        fault: Some("worker aggregate output limit exceeded"),
                    };
                }
                out.extend_from_slice(&chunk[..n]);
                while let Some(end) = out[scanned..].iter().position(|b| *b == b'\n') {
                    let raw = &out[scanned..scanned + end];
                    let frame = if let Some(protocol) = &mut protocol {
                        match protocol.accept(raw) {
                            Ok(frame) => frame,
                            Err(fault) => {
                                return Transcript {
                                    bytes: out,
                                    fault: Some(fault),
                                }
                            }
                        }
                    } else {
                        serde_json::from_slice(raw).ok()
                    };
                    let line = String::from_utf8_lossy(raw).into_owned();
                    scanned += end + 1;
                    let brokered = frame.as_ref().is_some_and(|f| {
                        f.get("kind").and_then(|k| k.as_str()) == Some("BROKER_REQUEST")
                    });
                    if brokered {
                        calls += 1;
                        let frame = frame.unwrap_or_default();
                        let response = if calls > MAX_BROKER_CALLS {
                            serde_json::json!({"kind": "BROKER_RESPONSE", "requestId": frame.get("requestId").cloned().unwrap_or_default(),
                                "error": {"code": "BROKER_REFUSED", "message": "too many brokered operations", "retryable": false}})
                        } else {
                            crate::broker::answer(broker, &frame).await
                        };
                        let mut bytes = serde_json::to_vec(&response).unwrap_or_default();
                        bytes.push(b'\n');
                        let _ = stdin.write_all(&bytes).await;
                        let _ = stdin.flush().await;
                    } else if let Some(sink) = lines {
                        sink.offer(&line);
                    }
                }
            }
        }
    }
}

/// Reads at most `cap` bytes, and whether there was more.
async fn bounded<R: AsyncRead + Unpin>(mut reader: R, cap: usize) -> (Vec<u8>, bool) {
    let mut out = Vec::new();
    let mut chunk = [0u8; 16 * 1024];
    loop {
        match reader.read(&mut chunk).await {
            Ok(0) | Err(_) => return (out, false),
            Ok(n) => {
                if out.len() + n > cap {
                    return (out, true);
                }
                out.extend_from_slice(&chunk[..n]);
            }
        }
    }
}
