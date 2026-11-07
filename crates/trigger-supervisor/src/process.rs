// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The trigger process (plan 6.1): one process per instance, started in the same image and sandbox tier as a
//! tool with `TOOLGATE_PROTOCOL=2`, `TOOLGATE_EXECUTION_TARGET` and `TOOLGATE_EXECUTION_MODE=TRIGGER`. The
//! executor builds the sandboxed [`Command`]; this module owns the stdio framing and the stop sequence.

use crate::frames::{
    encode_inbound, parse_outbound, FrameError, Inbound, Outbound, MAX_FRAME_BYTES,
};
use olo_toolgate_contracts::v2::{TriggerExecutor, TriggerStartFrame};
use std::io;
use std::process::{ExitStatus, Stdio};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};

#[derive(Debug)]
pub enum ReadError {
    Io(io::Error),
    Frame(FrameError),
}

impl std::fmt::Display for ReadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(f, "trigger stdout: {error}"),
            Self::Frame(error) => error.fmt(f),
        }
    }
}

impl std::error::Error for ReadError {}

pub struct TriggerProcess {
    child: Child,
    stdin: Option<ChildStdin>,
    stdout: BufReader<ChildStdout>,
    line: Vec<u8>,
}

impl TriggerProcess {
    /// Spawns the process and writes `TRIGGER_START` as its first stdin line. Stderr is left to the caller's
    /// configuration of `command` (the executor routes it to its redacted logs).
    pub async fn spawn(
        mut command: Command,
        target: TriggerExecutor,
        start: TriggerStartFrame,
    ) -> io::Result<Self> {
        let target = match target {
            TriggerExecutor::Server => "SERVER",
            TriggerExecutor::Client => "CLIENT",
        };
        command
            .env("TOOLGATE_PROTOCOL", "2")
            .env("TOOLGATE_EXECUTION_TARGET", target)
            .env("TOOLGATE_EXECUTION_MODE", "TRIGGER")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .kill_on_drop(true);
        let mut child = command.spawn()?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| io::Error::other("trigger stdin is not piped"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| io::Error::other("trigger stdout is not piped"))?;
        let mut process = Self {
            child,
            stdin: Some(stdin),
            stdout: BufReader::new(stdout),
            line: Vec::new(),
        };
        process.send(&Inbound::Start(start)).await?;
        Ok(process)
    }

    pub fn id(&self) -> Option<u32> {
        self.child.id()
    }

    /// Writes one stdin frame.
    pub async fn send(&mut self, frame: &Inbound) -> io::Result<()> {
        let stdin = self
            .stdin
            .as_mut()
            .ok_or_else(|| io::Error::new(io::ErrorKind::BrokenPipe, "stdin closed"))?;
        stdin.write_all(encode_inbound(frame).as_bytes()).await?;
        stdin.flush().await
    }

    /// Reads the next stdout frame; `None` at end of stream. A line longer than [`MAX_FRAME_BYTES`] is an
    /// error without buffering it whole.
    pub async fn next_frame(&mut self) -> Result<Option<Outbound>, ReadError> {
        self.line.clear();
        loop {
            let available = self.stdout.fill_buf().await.map_err(ReadError::Io)?;
            if available.is_empty() {
                return if self.line.is_empty() {
                    Ok(None)
                } else {
                    Err(ReadError::Io(io::ErrorKind::UnexpectedEof.into()))
                };
            }
            let (chunk, done) = match available.iter().position(|b| *b == b'\n') {
                Some(end) => (&available[..end], Some(end + 1)),
                None => (available, None),
            };
            if self.line.len() + chunk.len() > MAX_FRAME_BYTES {
                return Err(ReadError::Frame(FrameError::TooLong));
            }
            self.line.extend_from_slice(chunk);
            let consumed = done.unwrap_or(available.len());
            self.stdout.consume(consumed);
            if done.is_some() {
                let line = self.line.strip_suffix(b"\r").unwrap_or(&self.line);
                return parse_outbound(line).map(Some).map_err(ReadError::Frame);
            }
        }
    }

    /// Closes stdin (after `STOP`, so the SDK sees end of input once it has read the frame).
    pub fn close_stdin(&mut self) {
        self.stdin = None;
    }

    /// SIGTERM on Unix. Windows has no graceful signal for a console-less child, so this is a kill there.
    pub fn terminate(&mut self) -> io::Result<()> {
        #[cfg(unix)]
        if let Some(pid) = self.child.id() {
            // SAFETY: kill(2) with a pid this process spawned and has not yet reaped.
            let result = unsafe { libc::kill(pid as libc::pid_t, libc::SIGTERM) };
            return if result == 0 {
                Ok(())
            } else {
                Err(io::Error::last_os_error())
            };
        }
        self.child.start_kill()
    }

    pub fn kill(&mut self) -> io::Result<()> {
        self.child.start_kill()
    }

    pub async fn wait(&mut self) -> io::Result<ExitStatus> {
        self.child.wait().await
    }
}
