// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Managed interpreter provisioning and organization-reviewed, online-authorized execution.
pub mod adapters;
pub mod engine;
pub mod source;
use crate::{builtins::AuthorizationPort, contracts::Contracts, Failure, Result};
use olo_toolgate_contracts::*;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::PathBuf,
    sync::Arc,
};

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
            if !tools.insert(tool.tool_id.as_str())
                || !runtimes.contains(tool.runtime_id.as_str())
                || tool.tool_id.starts_with("hotfolder.")
                || [
                    "calculator.evaluate",
                    "text.transform",
                    "json.validate",
                    "hash.sha256",
                    "system.info",
                    "web.search",
                ]
                .contains(&tool.tool_id.as_str())
            {
                return Err(Failure::Validation);
            }
            let kind = &self
                .runtimes
                .iter()
                .find(|r| r.id == tool.runtime_id)
                .ok_or(Failure::Validation)?
                .kind;
            if !matches!(kind, LocalRuntimeKind::Batch | LocalRuntimeKind::Wasm) {
                source::validate(kind, tool)?;
                adapters::invocation_arguments(kind, tool)?;
            }
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
    contracts: Contracts,
    states: BTreeMap<String, LocalRuntimeState>,
    successes: u64,
    failures: u64,
    recovered: bool,
}
impl Manager {
    pub(crate) fn settings(&self) -> Settings {
        self.settings.clone()
    }
    pub fn catalog(&self) -> Vec<BuiltinToolInfo> {
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
            .map(|r| {
                (
                    r.id.clone(),
                    if matches!(r.kind, LocalRuntimeKind::Batch | LocalRuntimeKind::Wasm) {
                        LocalRuntimeState::Unsupported
                    } else {
                        LocalRuntimeState::Missing
                    },
                )
            })
            .collect();
        Ok(Self {
            settings,
            engine,
            authorization,
            contracts: Contracts::new()?,
            states,
            successes: 0,
            failures: 0,
            recovered: false,
        })
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
            "DOTNET_EnableDiagnostics=0",
            "DOTNET_PROCESSOR_COUNT=2",
            "DOTNET_CLI_TELEMETRY_OPTOUT=1",
        ]);
        command.extend(arguments);
        let data = self
            .engine
            .probe_version(&runtime.image, &command, &limits)
            .await?;
        let bytes = if runtime.kind == LocalRuntimeKind::JavaJar {
            &data.stderr
        } else {
            &data.stdout
        };
        adapters::verify_version(bytes, &runtime.version)?;
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
        let input = self.contracts.encode("LocalToolInput", &invocation)?;
        if input.len() as u64 > tool.limits.max_input_bytes
            || !closed_schema(&tool.input_schema)?
                .is_valid(&serde_json::to_value(&test.arguments).map_err(|_| Failure::Validation)?)
        {
            return Err(Failure::Validation);
        }
        let (input, limits) = source::input(&tool, &input)?;
        let command = adapters::invocation_arguments(&runtime.kind, &tool)?;
        let output = self
            .engine
            .run(&runtime.image, &command, &input, &limits)
            .await?;
        if !output.stderr.is_empty() {
            return Err(Failure::Validation);
        }
        let output: LocalToolOutput = self.contracts.decode("LocalToolOutput", &output.stdout)?;
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
        let input = self.contracts.encode("LocalToolInput", &invocation)?;
        let tool = self
            .settings
            .tools
            .iter()
            .find(|t| t.tool_id == invocation.tool_id)
            .cloned()
            .ok_or(Failure::Unauthorized)?;
        if input.len() as u64 > tool.limits.max_input_bytes
            || !closed_schema(&tool.input_schema)?.is_valid(
                &serde_json::to_value(&invocation.arguments).map_err(|_| Failure::Validation)?,
            )
        {
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
        if crate::now().saturating_add(20000) >= valid_until {
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
        let startup_ms = engine::CONTAINER_CREATE_TIMEOUT.as_millis() as u64 + 500;
        if crate::now().saturating_add(tool.limits.timeout_ms + startup_ms) >= deadline {
            return Err(Failure::Expired);
        }
        let command = adapters::invocation_arguments(&runtime.kind, &tool)?;
        let (input, limits) = source::input(&tool, &input)?;
        let data = tokio::time::timeout(
            std::time::Duration::from_millis(deadline.saturating_sub(crate::now())),
            self.engine.run(&runtime.image, &command, &input, &limits),
        )
        .await
        .map_err(|_| Failure::Expired)??;
        // Any diagnostic stderr rejects execution; never reflect arbitrary stderr.
        if !data.stderr.is_empty() {
            return Err(Failure::Validation);
        }
        let output: LocalToolOutput = self.contracts.decode("LocalToolOutput", &data.stdout)?;
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
