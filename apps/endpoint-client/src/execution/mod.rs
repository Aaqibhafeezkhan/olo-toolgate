// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Managed runtime provisioning and organization-reviewed, online-authorized execution of signed SDK packages over
//! tool protocol v2, the one protocol every executor speaks (ADR 026, ADR 027).
pub mod adapters;
pub mod engine;
use crate::{contracts::Contracts, transport::Call, Failure, Result};
use olo_toolgate_contracts::*;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::PathBuf,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Mutex,
    },
};

/// The adapter must return only after validating/consuming a grant for this exact request.
/// A device without a local Gateway credential runs only the calls Control relays to it, each authorized by Control
/// over the device's own mTLS identity (`remote.rs`). Local command-line or IPC calls are refused.
pub struct RelayOnly;
impl AuthorizationPort for RelayOnly {
    fn authorize(&self, _request: AuthorizationRequest) -> Call<'_, ()> {
        Box::pin(async { Err(Failure::Unauthorized) })
    }
    fn authorize_bound(&self, _request: AuthorizationRequest) -> Call<'_, Option<u64>> {
        Box::pin(async { Err(Failure::Unauthorized) })
    }
}

pub trait AuthorizationPort: Send + Sync {
    fn authorize(&self, request: AuthorizationRequest) -> Call<'_, ()>;
    /// Managed execution needs the consumed permit's deadline. An adapter that
    /// cannot provide bounded authorization is unsupported, never implicitly ALLOW.
    fn authorize_bound(&self, _request: AuthorizationRequest) -> Call<'_, Option<u64>> {
        Box::pin(async { Err(Failure::Unsupported) })
    }
    /// The trace context of the call just authorized (ADR 031). Only a relayed call carries one.
    fn trace(&self) -> crate::trace::RunTrace {
        crate::trace::RunTrace::default()
    }
    fn complete(
        &self,
        _request: AuthorizationRequest,
        _output: BTreeMap<String, Value>,
    ) -> Call<'_, ()> {
        Box::pin(async { Ok(()) })
    }
}

/// Brokered device-file operations one invocation may make.
const MAX_FILE_CALLS: usize = 64;

/// A self-test's files area: empty, private to one probe and deleted when dropped.
struct ScratchArea {
    root: std::path::PathBuf,
    area: Option<Arc<Mutex<crate::files::Area>>>,
}
impl ScratchArea {
    fn open(device: &crate::files::Settings, root: std::path::PathBuf) -> Result<Self> {
        let settings = crate::files::Settings {
            root: root.clone(),
            ..device.clone()
        };
        let area = crate::files::Area::open(settings);
        let area = match area {
            Ok(area) => area,
            Err(e) => {
                let _ = std::fs::remove_dir_all(&root);
                return Err(e);
            }
        };
        Ok(Self {
            root,
            area: Some(Arc::new(Mutex::new(area))),
        })
    }
}
impl Drop for ScratchArea {
    fn drop(&mut self) {
        // The area's directory handle closes first; Windows cannot remove an open directory.
        drop(self.area.take());
        if let Err(error) = std::fs::remove_dir_all(&self.root) {
            tracing::warn!(event = "self_test_files", result = "not_removed", error = %error);
        }
    }
}

/// The device side of the broker in tool protocol v2 (ADR 027). Devices answer only the generic files primitives, and
/// only as the signed registration grants: READ for list, read and info; WRITE for the rest. A path or destination must
/// be a string argument of this invocation, so the authorized arguments bound every file the tool touches. Route calls
/// and secrets belong to Tool Hosts and are refused here.
fn device_broker(
    area: Option<&Mutex<crate::files::Area>>,
    access: Option<&LocalFileAccess>,
    arguments: &BTreeMap<String, Value>,
    calls: &AtomicUsize,
    line: &[u8],
) -> Option<Vec<u8>> {
    let frame: Value = serde_json::from_slice(line).ok()?;
    if frame.get("kind").and_then(Value::as_str) != Some("BROKER_REQUEST") {
        return None;
    }
    let id = frame.get("requestId").cloned().unwrap_or(Value::Null);
    let request = frame.get("request").cloned().unwrap_or(json!({}));
    let operation = frame
        .get("operation")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let answer = (|| -> std::result::Result<Value, &'static str> {
        if calls.fetch_add(1, Ordering::SeqCst) >= MAX_FILE_CALLS {
            return Err("too many file operations for one invocation");
        }
        if !operation.starts_with("FILES_") {
            return Err("this operation is not available on a device");
        }
        let write = !matches!(operation, "FILES_LIST" | "FILES_READ" | "FILES_INFO");
        match access {
            Some(LocalFileAccess::Write) => {}
            Some(LocalFileAccess::Read) if !write => {}
            _ => return Err("the signed registration does not grant this file access"),
        }
        let area = area.ok_or("this device has no files area")?;
        let authorized = |name: &str| -> std::result::Result<String, &'static str> {
            let value = request
                .get(name)
                .and_then(Value::as_str)
                .ok_or("a path is required")?;
            if !arguments.values().any(|a| a.as_str() == Some(value)) {
                return Err("the path is not one this invocation was authorized with");
            }
            Ok(value.to_owned())
        };
        let refused = |_: Failure| "the device refused the file operation";
        let area = area.lock().map_err(|_| "the files area is unavailable")?;
        Ok(match operation {
            "FILES_LIST" => json!({"paths": area.list().map_err(refused)?}),
            "FILES_READ" => {
                let bytes = area.read(&authorized("path")?).map_err(refused)?;
                json!({"text": String::from_utf8(bytes).map_err(|_| "the file is not UTF-8 text")?})
            }
            "FILES_INFO" => area.info(&authorized("path")?).map_err(refused)?,
            "FILES_WRITE" | "FILES_APPEND" => {
                let text = request
                    .get("text")
                    .and_then(Value::as_str)
                    .ok_or("text is required")?;
                area.write(
                    &authorized("path")?,
                    text.as_bytes(),
                    operation == "FILES_APPEND",
                )
                .map_err(refused)?;
                json!({})
            }
            "FILES_MKDIR" => {
                area.mkdir(&authorized("path")?).map_err(refused)?;
                json!({})
            }
            "FILES_RENAME" => {
                area.rename(&authorized("path")?, &authorized("destination")?)
                    .map_err(refused)?;
                json!({})
            }
            _ => return Err("unknown file operation"),
        })
    })();
    let response = match answer {
        Ok(result) => json!({"kind": "BROKER_RESPONSE", "requestId": id, "result": result}),
        Err(message) => json!({"kind": "BROKER_RESPONSE", "requestId": id,
            "error": {"code": "BROKER_REFUSED", "message": message, "retryable": false}}),
    };
    serde_json::to_vec(&response).ok()
}

/// Bytes a v2 ToolInput envelope may add to the arguments the registration bounds.
const SDK_ENVELOPE_BYTES: u64 = 4096;
/// A relayed call is started only with this much of its deadline left. Like a Tool Host run, the run itself is bounded
/// by the call's deadline (the tool sees it as deadlineUnixMs) and by the tool's own timeout, not by a fixed reserve
/// that the platform's default 20 s request window could never satisfy.
const MIN_RUN_MS: u64 = 2000;

/// One v2 `ToolInput` line for a Java SDK package (ADR 026), bound to this invocation and its deadline. The device has no
/// broker, globals, claims or secrets to hand the tool; an export needing them is never released to devices.
fn sdk_input(
    tool: &LocalToolRegistration,
    request_id: &str,
    arguments: &BTreeMap<String, serde_json::Value>,
    deadline_unix_ms: u64,
    trace: crate::trace::RunTrace,
) -> Result<(Vec<u8>, LocalRuntimeLimits)> {
    let input = olo_toolgate_contracts::v2::ToolInput {
        protocol_version: 2,
        invocation_id: request_id.to_owned(),
        task_id: None,
        tool_id: tool.tool_id.clone(),
        entry_point: if tool.entry_point.ends_with(".pyz") {
            "toolgate.runtime"
        } else {
            adapters::JAVA_SDK_MAIN
        }
        .into(),
        arguments: arguments.clone(),
        globals: BTreeMap::new(),
        claims: BTreeMap::new(),
        secret_handles: Vec::new(),
        deadline_unix_ms,
        idempotency_key: None,
        catalog_revision: "device".into(),
        traceparent: trace.traceparent,
        fencing_token: None,
        execution_context: trace.context,
    };
    let mut bytes = serde_json::to_vec(&input).map_err(|_| Failure::Validation)?;
    if bytes.len() as u64
        > tool
            .limits
            .max_input_bytes
            .saturating_add(SDK_ENVELOPE_BYTES)
    {
        return Err(Failure::Validation);
    }
    bytes.push(b'\n');
    let mut limits = tool.limits.clone();
    limits.max_input_bytes = bytes.len() as u64;
    Ok((bytes, limits))
}

/// At most this many response frames per run reach Control from a device; the rest are counted as dropped.
const MAX_RUN_FRAMES: usize = 64;

/// The LOG and PROGRESS frames of one run, in order and numbered from 1, for Control's run-event stream (ADR 027).
/// Broker requests are not responses, and an INPUT_REQUEST fails the run on a device anyway.
pub(crate) fn run_frames(stdout: &[u8]) -> (Vec<olo_toolgate_contracts::v2::RunEventFrame>, u64) {
    use olo_toolgate_contracts::v2::{LogFrame, ProgressFrame, RunEventFrame, RunEventKind};
    let (mut frames, mut dropped) = (Vec::new(), 0u64);
    let now = crate::now();
    for line in String::from_utf8_lossy(stdout).lines() {
        let Ok(value) = serde_json::from_str::<serde_json::Value>(line.trim()) else {
            continue;
        };
        let frame = match value.get("kind").and_then(|k| k.as_str()) {
            Some("LOG") => serde_json::from_value::<LogFrame>(value)
                .ok()
                .map(|f| (RunEventKind::Log, Some(f.level), Some(f.message), None)),
            Some("PROGRESS") => serde_json::from_value::<ProgressFrame>(value)
                .ok()
                .map(|f| (RunEventKind::Progress, None, f.message, Some(f.fraction))),
            _ => continue,
        };
        let Some((kind, level, message, fraction)) = frame else {
            dropped += 1;
            continue;
        };
        if frames.len() == MAX_RUN_FRAMES {
            dropped += 1;
            continue;
        }
        frames.push(RunEventFrame {
            sequence: frames.len() as u64 + 1,
            emitted_at_unix_ms: now,
            kind,
            level,
            message: message
                .filter(|m| !m.is_empty())
                .map(|m| m.chars().take(8192).collect()),
            fraction,
            request_id: None,
            request: None,
        });
    }
    (frames, dropped)
}

/// Response frames on stdout, ending in exactly one RESULT. Diagnostics on stderr are discarded, never reflected. A tool
/// error, an input request (devices take no interactive input) or anything that is not a protocol frame fails closed;
/// broker requests were answered while the tool ran.
fn sdk_output(stdout: &[u8], request_id: &str) -> Result<LocalToolOutput> {
    let text = std::str::from_utf8(stdout).map_err(|_| Failure::Validation)?;
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    let (last, frames) = lines.split_last().ok_or(Failure::Validation)?;
    for line in frames {
        let frame: serde_json::Value =
            serde_json::from_str(line).map_err(|_| Failure::Validation)?;
        match frame.get("kind").and_then(|k| k.as_str()) {
            Some("LOG") | Some("PROGRESS") | Some("BROKER_REQUEST") => {}
            Some("INPUT_REQUEST") => return Err(Failure::Unsupported),
            _ => return Err(Failure::Validation),
        }
    }
    let result: olo_toolgate_contracts::v2::ResultFrame =
        serde_json::from_str(last).map_err(|_| Failure::Validation)?;
    if result.kind != "RESULT"
        || result.is_error
        || result.result_type != olo_toolgate_contracts::v2::ResultType::Complete
    {
        return Err(Failure::Validation);
    }
    Ok(LocalToolOutput {
        protocol_version: 1,
        request_id: request_id.to_owned(),
        output: result.structured_content.ok_or(Failure::Validation)?,
    })
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub engine_path: PathBuf,
    pub engine_endpoint: String,
    pub state_directory: PathBuf,
    pub allow_first_use_pull: bool,
    pub pull_timeout_seconds: u64,
    pub runtimes: Vec<ManagedRuntime>,
    pub tools: Vec<LocalToolRegistration>,
    /// The device files area the broker serves to registrations that grant file access.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub files: Option<crate::files::Settings>,
}
impl Settings {
    /// Configuration is local administrator deployment trust, never an IPC upload.
    pub fn validate(&self) -> Result<()> {
        if (self.runtimes.is_empty() && !self.tools.is_empty())
            || self.runtimes.len() > 16
            || self.tools.len() > 32
            || !self.state_directory.is_absolute()
            || !self.engine_path.is_absolute()
            || !(5..=180).contains(&self.pull_timeout_seconds)
        {
            return Err(Failure::Validation);
        }
        if let Some(files) = &self.files {
            files.validate()?;
        }
        let contracts = Contracts::new()?;
        let mut runtimes = BTreeSet::new();
        let mut tools = BTreeSet::new();
        for runtime in &self.runtimes {
            contracts.encode("ManagedRuntime", runtime)?;
            if !runtimes.insert(runtime.id.as_str()) {
                return Err(Failure::Validation);
            }
        }
        for tool in &self.tools {
            contracts.encode("LocalToolRegistration", tool)?;
            crate::authorization_profile::managed_info(
                tool,
                self.runtimes
                    .iter()
                    .find(|r| r.id == tool.runtime_id)
                    .ok_or(Failure::Validation)?,
            )?;
            if !tools.insert(tool.tool_id.as_str()) || !runtimes.contains(tool.runtime_id.as_str())
            {
                return Err(Failure::Validation);
            }
            let kind = &self
                .runtimes
                .iter()
                .find(|r| r.id == tool.runtime_id)
                .ok_or(Failure::Validation)?
                .kind;
            adapters::invocation_arguments(kind, tool)?;
            closed_schema(&tool.input_schema)?;
            closed_schema(&tool.output_schema)?;
        }
        Ok(())
    }
}

/// Schemas cannot perform network reference fetches or create unbounded validator work.
fn closed_schema(schema: &BTreeMap<String, serde_json::Value>) -> Result<jsonschema::Validator> {
    let value = serde_json::to_value(schema).map_err(|_| Failure::Validation)?;
    let raw = serde_json::to_vec(&value).map_err(|_| Failure::Validation)?;
    fn safe(value: &serde_json::Value, depth: usize) -> bool {
        if depth > 16 {
            return false;
        }
        match value {
            serde_json::Value::Object(map) => {
                !map.keys().any(|k| {
                    ["$ref", "$dynamicRef", "$id", "pattern", "patternProperties"]
                        .contains(&k.as_str())
                }) && map.values().all(|v| safe(v, depth + 1))
            }
            serde_json::Value::Array(values) => {
                values.len() <= 64 && values.iter().all(|v| safe(v, depth + 1))
            }
            _ => true,
        }
    }
    if raw.len() > 8192
        || value["type"] != "object"
        || value["additionalProperties"] != false
        || !safe(&value, 0)
    {
        return Err(Failure::Validation);
    }
    jsonschema::validator_for(&value).map_err(|_| Failure::Validation)
}

pub struct Manager {
    settings: Settings,
    engine: Arc<dyn engine::SandboxPort>,
    authorization: Arc<dyn AuthorizationPort>,
    /// The last run's response frames and how many were dropped, until taken.
    frames: (Vec<olo_toolgate_contracts::v2::RunEventFrame>, u64),
    states: BTreeMap<String, LocalRuntimeState>,
    successes: u64,
    failures: u64,
    recovered: bool,
    files: Option<Arc<Mutex<crate::files::Area>>>,
}
impl Manager {
    pub(crate) fn settings(&self) -> Settings {
        self.settings.clone()
    }
    pub fn catalog(&self) -> Vec<LocalToolInfo> {
        self.settings
            .tools
            .iter()
            .filter(|tool| {
                self.states.get(&tool.runtime_id) == Some(&LocalRuntimeState::Ready)
                    && !self.engine.dirty()
            })
            .filter_map(|tool| {
                self.settings
                    .runtimes
                    .iter()
                    .find(|r| r.id == tool.runtime_id)
                    .and_then(|r| crate::authorization_profile::managed_info(tool, r).ok())
            })
            .collect()
    }
    pub fn new(settings: Settings, authorization: Arc<dyn AuthorizationPort>) -> Result<Self> {
        settings.validate()?;
        let engine = engine::DockerEngine::new(
            settings.engine_path.clone(),
            settings.engine_endpoint.clone(),
            settings.state_directory.clone(),
        )?;
        let states = settings
            .runtimes
            .iter()
            .map(|r| (r.id.clone(), LocalRuntimeState::Missing))
            .collect();
        let files = match &settings.files {
            Some(files) => Some(Arc::new(Mutex::new(crate::files::Area::open(
                files.clone(),
            )?))),
            None => None,
        };
        Ok(Self {
            settings,
            engine,
            authorization,
            frames: (Vec::new(), 0),
            states,
            successes: 0,
            failures: 0,
            recovered: false,
            files,
        })
    }
    /// The response frames of the last run (in order, numbered from 1) and how many were dropped.
    pub fn take_frames(&mut self) -> (Vec<olo_toolgate_contracts::v2::RunEventFrame>, u64) {
        std::mem::take(&mut self.frames)
    }
    pub fn health(&self) -> LocalRuntimeHealth {
        LocalRuntimeHealth {
            ready: self.recovered
                && !self.engine.dirty()
                && self.states.values().all(|v| *v == LocalRuntimeState::Ready),
            successful_executions: self.successes,
            failed_executions: self.failures,
            runtimes: self
                .settings
                .runtimes
                .iter()
                .map(|r| LocalRuntimeStatus {
                    runtime_id: r.id.clone(),
                    kind: r.kind.clone(),
                    state: self.states[&r.id].clone(),
                    version: r.version.clone(),
                })
                .collect(),
        }
    }
    /// Called after IPC tasks are drained/aborted so no launch races shutdown.
    pub async fn shutdown(&self) -> Result<()> {
        self.engine.recover().await
    }
    async fn prepare_runtime(&mut self, id: &str) -> Result<()> {
        let runtime = self
            .settings
            .runtimes
            .iter()
            .find(|r| r.id == id)
            .cloned()
            .ok_or(Failure::Validation)?;
        let arguments = adapters::version_arguments(&runtime.kind)?;
        self.engine.check().await?;
        if !self.recovered || self.engine.dirty() {
            self.engine.recover().await?;
            self.recovered = true;
        }
        if !self.engine.present(&runtime.image).await? {
            if !self.settings.allow_first_use_pull {
                return Err(Failure::Unavailable);
            }
            tracing::info!(event="runtime_prepare",runtime_id=%runtime.id,result="pulling");
            self.engine
                .pull(&runtime.image, self.settings.pull_timeout_seconds)
                .await?;
        }
        let limits = LocalRuntimeLimits {
            timeout_ms: 10000,
            memory_mi_b: 512,
            max_input_bytes: 4096,
            max_output_bytes: 4096,
        };
        let mut command = engine::strings(&[
            "/usr/bin/env",
            "-i",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "HOME=/work",
            "TMPDIR=/tmp",
        ]);
        command.extend(arguments);
        let data = self
            .engine
            .probe_version(&runtime.image, &command, &limits)
            .await?;
        // Python reports its version on stdout; the JVM reports it on stderr.
        let version_output = match runtime.kind {
            LocalRuntimeKind::PythonSdk => &data.stdout,
            LocalRuntimeKind::JavaSdk => &data.stderr,
        };
        adapters::verify_version(version_output, &runtime.version)?;
        self.states.insert(id.to_owned(), LocalRuntimeState::Ready);
        tracing::info!(event="runtime_prepare",runtime_id=%runtime.id,result="ready");
        Ok(())
    }
    /// Explicit installation-time preparation uses exactly the first-use code path.
    pub async fn prepare(&mut self) -> Result<LocalRuntimeHealth> {
        for id in self
            .settings
            .runtimes
            .iter()
            .map(|r| r.id.clone())
            .collect::<Vec<_>>()
        {
            if let Err(e) = self.prepare_runtime(&id).await {
                self.failed_state(&id, e);
            }
        }
        Ok(self.health())
    }
    fn failed_state(&mut self, id: &str, failure: Failure) {
        self.states.insert(
            id.into(),
            if failure == Failure::Unsupported {
                LocalRuntimeState::Unsupported
            } else {
                LocalRuntimeState::Failed
            },
        );
        tracing::warn!(event="runtime_prepare",runtime_id=%id,result="rejected",error=?failure);
    }
    /// Only the signed deployment reconciler calls this confined probe; no IPC endpoint bypasses authorization.
    pub(crate) async fn probe(&mut self, test: &FleetSelfTest) -> Result<()> {
        let tool = self
            .settings
            .tools
            .iter()
            .find(|t| t.tool_id == test.tool_id)
            .cloned()
            .ok_or(Failure::Validation)?;
        let runtime = self
            .settings
            .runtimes
            .iter()
            .find(|r| r.id == tool.runtime_id)
            .cloned()
            .ok_or(Failure::Validation)?;
        self.prepare_runtime(&runtime.id).await?;
        let invocation = LocalToolInput {
            protocol_version: 1,
            request_id: crate::identity::nonce()?,
            tool_id: test.tool_id.clone(),
            arguments: test.arguments.clone(),
        };
        if !closed_schema(&tool.input_schema)?
            .is_valid(&serde_json::to_value(&test.arguments).map_err(|_| Failure::Validation)?)
        {
            return Err(Failure::Validation);
        }
        let (input, limits) = sdk_input(
            &tool,
            &invocation.request_id,
            &invocation.arguments,
            crate::now().saturating_add(tool.limits.timeout_ms),
            crate::trace::RunTrace::default(),
        )?;
        let command = adapters::invocation_arguments(&runtime.kind, &tool)?;
        // A self-test never sees or changes the user's files: a tool with file access gets a new, empty area
        // with the device's limits, removed afterwards. Package examples are written to hold in an empty area.
        let scratch = match (&tool.file_access, &self.settings.files) {
            (Some(LocalFileAccess::Read | LocalFileAccess::Write), Some(files)) => {
                Some(ScratchArea::open(
                    files,
                    self.settings
                        .state_directory
                        .join(format!("self-test-{}", crate::identity::nonce()?)),
                )?)
            }
            _ => None,
        };
        let files = scratch.as_ref().and_then(|s| s.area.clone());
        let calls = AtomicUsize::new(0);
        let arguments = invocation.arguments.clone();
        let access = tool.file_access.clone();
        let broker = move |line: &[u8]| {
            device_broker(files.as_deref(), access.as_ref(), &arguments, &calls, line)
        };
        let output = self
            .engine
            .run(&runtime.image, &command, &input, &limits, &broker)
            .await;
        drop(broker);
        drop(scratch);
        let output = sdk_output(&output?.stdout, &invocation.request_id)?;
        if output.request_id != invocation.request_id
            || output.output != test.expected_output
            || !closed_schema(&tool.output_schema)?
                .is_valid(&serde_json::to_value(&output.output).map_err(|_| Failure::Validation)?)
        {
            return Err(Failure::Validation);
        }
        Ok(())
    }
    /// Preparation precedes authorization. Neither a ready image nor registration
    /// grants execution: the online port validates/consumes this exact operation.
    pub async fn invoke(
        &mut self,
        invocation: LocalToolInput,
        valid_until: u64,
    ) -> Result<LocalToolOutput> {
        let tool_id = invocation.tool_id.clone();
        let result = self.invoke_inner(invocation, valid_until).await;
        if result.is_ok() {
            self.successes += 1;
        } else {
            self.failures += 1;
        }
        tracing::info!(event="runtime_execution",tool_id=%tool_id,result=if result.is_ok() {"completed"} else {"rejected"});
        result
    }
    async fn invoke_inner(
        &mut self,
        invocation: LocalToolInput,
        valid_until: u64,
    ) -> Result<LocalToolOutput> {
        let tool = self
            .settings
            .tools
            .iter()
            .find(|t| t.tool_id == invocation.tool_id)
            .cloned()
            .ok_or(Failure::Unauthorized)?;
        if !closed_schema(&tool.input_schema)?.is_valid(
            &serde_json::to_value(&invocation.arguments).map_err(|_| Failure::Validation)?,
        ) {
            return Err(Failure::Validation);
        }
        let runtime = self
            .settings
            .runtimes
            .iter()
            .find(|r| r.id == tool.runtime_id)
            .cloned()
            .ok_or(Failure::Validation)?;
        if let Err(e) = self.prepare_runtime(&runtime.id).await {
            self.failed_state(&runtime.id, e);
            return Err(e);
        }
        // The reviewed profile pins image/source versions; extraction sees the exact original arguments.
        let arguments = invocation.arguments.clone();
        if crate::now().saturating_add(MIN_RUN_MS) >= valid_until {
            return Err(Failure::Expired);
        }
        let permit_deadline = self
            .authorization
            .authorize_bound(AuthorizationRequest {
                tool_id: tool.tool_id.clone(),
                action: tool.action.clone(),
                arguments,
            })
            .await?;
        let deadline = permit_deadline.unwrap_or(valid_until).min(valid_until);
        if crate::now().saturating_add(MIN_RUN_MS) >= deadline {
            return Err(Failure::Expired);
        }
        let command = adapters::invocation_arguments(&runtime.kind, &tool)?;
        let (input, limits) = sdk_input(
            &tool,
            &invocation.request_id,
            &invocation.arguments,
            deadline,
            self.authorization.trace(),
        )?;
        let files = self.files.clone();
        let calls = AtomicUsize::new(0);
        let arguments = invocation.arguments.clone();
        let access = tool.file_access.clone();
        let broker = move |line: &[u8]| {
            device_broker(files.as_deref(), access.as_ref(), &arguments, &calls, line)
        };
        let data = tokio::time::timeout(
            std::time::Duration::from_millis(deadline.saturating_sub(crate::now())),
            self.engine
                .run(&runtime.image, &command, &input, &limits, &broker),
        )
        .await
        .map_err(|_| Failure::Expired)??;
        self.frames = run_frames(&data.stdout);
        let output = sdk_output(&data.stdout, &invocation.request_id)?;
        if output.request_id != invocation.request_id
            || !closed_schema(&tool.output_schema)?
                .is_valid(&serde_json::to_value(&output.output).map_err(|_| Failure::Validation)?)
        {
            return Err(Failure::Validation);
        }
        self.authorization
            .complete(
                AuthorizationRequest {
                    tool_id: tool.tool_id.clone(),
                    action: tool.action.clone(),
                    arguments: invocation.arguments.clone(),
                },
                output.output.clone(),
            )
            .await?;
        Ok(output)
    }
}

#[cfg(test)]
mod sdk_tests {
    use super::*;

    #[test]
    fn sdk_frames_end_in_one_result_and_fail_closed() {
        let ok = b"{\"kind\":\"LOG\",\"level\":\"info\",\"message\":\"x\"}\n{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"structuredContent\":{\"message\":\"Hello World\"},\"isError\":false}\n";
        let output = sdk_output(ok, "inv-1").unwrap();
        assert_eq!(output.request_id, "inv-1");
        assert_eq!(output.output["message"], "Hello World");
        let error = b"{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"isError\":true,\"error\":{\"code\":\"X\",\"message\":\"y\",\"retryable\":false}}";
        assert_eq!(sdk_output(error, "inv-1").unwrap_err(), Failure::Validation);
        let input = b"{\"kind\":\"INPUT_REQUEST\",\"requestId\":\"r\",\"request\":{}}\n{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"structuredContent\":{},\"isError\":false}";
        assert_eq!(
            sdk_output(input, "inv-1").unwrap_err(),
            Failure::Unsupported
        );
        assert_eq!(
            sdk_output(b"not json\n", "inv-1").unwrap_err(),
            Failure::Validation
        );
        assert_eq!(sdk_output(b"", "inv-1").unwrap_err(), Failure::Validation);
    }
}

#[cfg(all(test, unix))]
mod self_test_files {
    use super::ScratchArea;

    #[test]
    fn a_self_test_gets_an_empty_area_that_is_removed_afterwards() {
        // The canonical temporary directory: on macOS /var is a symbolic link, which protected storage refuses.
        let base = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "toolgate-scratch-{}",
            crate::identity::nonce().unwrap()
        ));
        let _custody = crate::storage::ProtectedStore::open(base.clone()).unwrap();
        let device = crate::files::Settings {
            root: base.join("files"),
            max_file_bytes: 65536,
            max_entries: 64,
            extensions: vec!["txt".into()],
        };
        let root = base.join("self-test-1");
        let scratch = ScratchArea::open(&device, root.clone()).unwrap();
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0, "starts empty");
        let area = scratch.area.clone().unwrap();
        {
            let area = area.lock().unwrap();
            // Appending creates a missing file, then adds to it: the HotFolder append_text self-test.
            area.write("notes.txt", b"one", true).unwrap();
            area.write("notes.txt", b"two", true).unwrap();
            assert_eq!(area.read("notes.txt").unwrap(), b"onetwo");
        }
        drop(area);
        std::fs::write(root.join("selftest.txt"), "ok").unwrap();
        drop(scratch);
        assert!(!root.exists(), "removed with everything the probe wrote");
        assert!(
            !device.root.exists(),
            "the device's own area is never touched"
        );
        std::fs::remove_dir_all(&base).unwrap();
    }
}

#[cfg(test)]
mod run_events {
    use super::run_frames;

    #[test]
    fn devices_keep_log_and_progress_frames_in_order_within_bounds() {
        let mut stdout = String::new();
        stdout.push_str("{\"kind\":\"LOG\",\"level\":\"info\",\"message\":\"start\"}\n");
        stdout.push_str("{\"kind\":\"BROKER_REQUEST\",\"requestId\":\"b-1\",\"operation\":\"FILES_LIST\",\"request\":{}}\n");
        stdout.push_str("{\"kind\":\"PROGRESS\",\"fraction\":0.5}\n");
        stdout.push_str("not a frame\n");
        stdout.push_str(
            "{\"kind\":\"RESULT\",\"resultType\":\"complete\",\"content\":[],\"isError\":false}\n",
        );
        let (frames, dropped) = run_frames(stdout.as_bytes());
        assert_eq!(dropped, 0);
        assert_eq!(frames.len(), 2);
        assert_eq!((frames[0].sequence, frames[1].sequence), (1, 2));
        assert_eq!(frames[0].message.as_deref(), Some("start"));
        assert_eq!(frames[1].fraction, Some(0.5));
        let batch = olo_toolgate_contracts::v2::RunEventBatch {
            invocation_id: "inv-1".into(),
            fencing_token: "fence-1".into(),
            attempt: 1,
            events: frames,
            dropped_frames: None,
        };
        crate::contracts::Contracts::new()
            .unwrap()
            .encode("RunEventBatch", &batch)
            .unwrap();

        let many = "{\"kind\":\"PROGRESS\",\"fraction\":0.1}\n".repeat(70);
        let (frames, dropped) = run_frames(many.as_bytes());
        assert_eq!((frames.len(), dropped), (64, 6));
        assert_eq!(frames.last().unwrap().sequence, 64);
    }
}
