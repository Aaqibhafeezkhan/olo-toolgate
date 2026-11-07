// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Durable executor state for one instance: the outbox, the `LOCAL` and `ACCEPTED` checkpoints and the halt
//! condition. Each [`Store`] method is one persistence point of plan 6.6.1 and is atomic: after a crash
//! either all of it is durable or none of it is.
//!
//! There is no separate sequence counter (T1). The highest `outboxSeq` of each epoch is the highest record in
//! the outbox, or the epoch mark written by the truncation that removed it, so after recovery the next
//! `outboxSeq` is always the last durable one + 1 (S1).

use olo_toolgate_contracts::v2::{TriggerCheckpointLevel, TriggerCloudEvent, TriggerEventKind};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

/// One outbox record: a CloudEvent (or a loss record, `tgkind: rejected`) under `(leaseEpoch, outboxSeq)`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OutboxEntry {
    pub lease_epoch: u64,
    pub outbox_seq: u64,
    pub written_at_unix_ms: u64,
    pub event: TriggerCloudEvent,
}

impl OutboxEntry {
    pub fn is_loss_record(&self) -> bool {
        self.event.tgkind == TriggerEventKind::Rejected
    }
}

/// Why an epoch's records are no longer delivered (T9, T10).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Fence {
    /// Fenced for a non-compromise reason: the records were pruned (T10).
    Pruned,
    /// The producer is distrusted: records are kept, never sent and never trusted.
    Compromised,
}

/// Truncation state of one lease epoch.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EpochMark {
    pub lease_epoch: u64,
    /// Highest `outboxSeq` ever written in this epoch.
    pub high_outbox_seq: u64,
    /// Highest contiguous `outboxSeq` Control acknowledged; records up to it are truncated (T8).
    pub acked_outbox_seq: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fenced: Option<Fence>,
}

/// A persisted checkpoint is always `{leaseEpoch, afterOutboxSeq, state, level}`, never an `emitSeq` (6.2.1).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StoredCheckpoint {
    pub lease_epoch: u64,
    pub after_outbox_seq: u64,
    pub level: CheckpointLevel,
    /// The package's `checkpointSchemaVersion` that wrote `state` (T12).
    pub schema_version: u64,
    /// The author's opaque position (base64).
    pub state: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CheckpointLevel {
    Local,
    Accepted,
}

impl From<CheckpointLevel> for TriggerCheckpointLevel {
    fn from(level: CheckpointLevel) -> Self {
        match level {
            CheckpointLevel::Local => TriggerCheckpointLevel::Local,
            CheckpointLevel::Accepted => TriggerCheckpointLevel::Accepted,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HaltSide {
    /// The supervisor rejected the event (T4).
    Executor,
    /// Control rejected the record on re-validation and returned a disposition (plan 6.6 item 5).
    Control,
}

/// The persisted `Failed (TerminalRejection)` condition under `HALT`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HaltRecord {
    pub side: HaltSide,
    pub lease_epoch: u64,
    pub event_id: String,
    pub reason: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub position: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outbox_seq: Option<u64>,
}

/// Everything recovered from durable state.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Recovered {
    /// Records ordered by `(leaseEpoch, outboxSeq)`.
    pub entries: Vec<OutboxEntry>,
    pub epochs: BTreeMap<u64, EpochMark>,
    pub local: Option<StoredCheckpoint>,
    pub accepted: Option<StoredCheckpoint>,
    pub halt: Option<HaltRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StoreError {
    /// Nothing was changed (for example, the disk is full and the partial write was rolled back).
    NotWritten(String),
    /// The write may or may not be durable. The supervisor stops using the store until it is recovered.
    Indeterminate(String),
    /// Durable state that is not this store's current format, or another instance's. Never converted.
    Unsupported(String),
    /// Durable state that violates S1 or cannot be parsed.
    Corrupt(String),
}

impl fmt::Display for StoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotWritten(detail) => write!(f, "not written: {detail}"),
            Self::Indeterminate(detail) => write!(f, "write outcome unknown: {detail}"),
            Self::Unsupported(detail) => write!(f, "unsupported trigger store: {detail}"),
            Self::Corrupt(detail) => write!(f, "corrupt trigger store: {detail}"),
        }
    }
}

impl std::error::Error for StoreError {}

/// The persistence points. Each call is atomic.
pub trait Store {
    fn load(&mut self) -> Result<Recovered, StoreError>;
    /// T1 / T3: append one record, then fsync.
    fn append(&mut self, entry: &OutboxEntry) -> Result<(), StoreError>;
    /// T8 / T10: record `mark` and delete the epoch's records it covers (up to `acked_outbox_seq`, or all of
    /// them when fenced `Pruned`), then fsync.
    fn mark_epoch(&mut self, mark: &EpochMark) -> Result<(), StoreError>;
    /// T5 (and the executor half of T7/T14): temp file, fsync, atomic rename.
    fn write_checkpoint(&mut self, checkpoint: &StoredCheckpoint) -> Result<(), StoreError>;
    /// T12 discard: remove the checkpoint of one level.
    fn discard_checkpoint(&mut self, level: CheckpointLevel) -> Result<(), StoreError>;
    /// T4: persist or clear the halt condition.
    fn write_halt(&mut self, halt: Option<&HaltRecord>) -> Result<(), StoreError>;
    /// T13: delete the instance's local state after removal.
    fn destroy(&mut self) -> Result<(), StoreError>;
}

/// Validates S1 on recovered state: each epoch's remaining records are exactly
/// `acked_outbox_seq + 1 ..= high_outbox_seq` (none for a pruned fence), with no duplicate or gap.
pub fn check_recovered(recovered: &mut Recovered) -> Result<(), StoreError> {
    recovered
        .entries
        .sort_by_key(|e| (e.lease_epoch, e.outbox_seq));
    for entry in &recovered.entries {
        if entry.event.tgleaseepoch != entry.lease_epoch
            || entry.event.tgsequence != entry.outbox_seq
        {
            return Err(StoreError::Corrupt(format!(
                "record {}:{} disagrees with its envelope",
                entry.lease_epoch, entry.outbox_seq
            )));
        }
        let mark = recovered
            .epochs
            .entry(entry.lease_epoch)
            .or_insert(EpochMark {
                lease_epoch: entry.lease_epoch,
                high_outbox_seq: 0,
                acked_outbox_seq: 0,
                fenced: None,
            });
        mark.high_outbox_seq = mark.high_outbox_seq.max(entry.outbox_seq);
    }
    for mark in recovered.epochs.values() {
        let seqs: Vec<u64> = recovered
            .entries
            .iter()
            .filter(|e| e.lease_epoch == mark.lease_epoch)
            .map(|e| e.outbox_seq)
            .collect();
        let expected: Vec<u64> = if mark.fenced == Some(Fence::Pruned) {
            Vec::new()
        } else {
            (mark.acked_outbox_seq + 1..=mark.high_outbox_seq).collect()
        };
        if mark.acked_outbox_seq > mark.high_outbox_seq || seqs != expected {
            return Err(StoreError::Corrupt(format!(
                "epoch {} outbox sequences are not gap-free",
                mark.lease_epoch
            )));
        }
    }
    Ok(())
}

const OUTBOX_FORMAT: &str = "toolgate.trigger-outbox.v1";
const CHECKPOINT_FORMAT: &str = "toolgate.trigger-checkpoint.v1";
const HALT_FORMAT: &str = "toolgate.trigger-halt.v1";
const OUTBOX: &str = "outbox.jsonl";
const HALT: &str = "halt.json";

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Header {
    format: String,
    instance_id: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
enum Line {
    Mark(EpochMark),
    Entry(Box<OutboxEntry>),
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Document<T> {
    format: String,
    instance_id: String,
    value: T,
}

/// File store: one directory per instance. The outbox is an append-only JSON-lines log fsynced per record;
/// truncation rewrites it to a temp file and renames it over the log. Checkpoints and the halt condition are
/// small documents written to a temp file, fsynced and renamed.
pub struct FsStore {
    dir: PathBuf,
    instance_id: String,
    outbox: Option<File>,
    outbox_len: u64,
    entries: BTreeMap<(u64, u64), OutboxEntry>,
    epochs: BTreeMap<u64, EpochMark>,
}

fn io_not_written(context: &str, error: io::Error) -> StoreError {
    StoreError::NotWritten(format!("{context}: {error}"))
}

#[cfg(unix)]
fn sync_dir(dir: &Path) -> io::Result<()> {
    File::open(dir)?.sync_all()
}

#[cfg(not(unix))]
fn sync_dir(_dir: &Path) -> io::Result<()> {
    // NTFS makes the rename durable with the file system journal; directories cannot be opened for fsync.
    Ok(())
}

impl FsStore {
    /// Opens (or creates) the directory of one instance. State is read by [`Store::load`].
    pub fn open(dir: impl Into<PathBuf>, instance_id: &str) -> Result<Self, StoreError> {
        let dir = dir.into();
        fs::create_dir_all(&dir).map_err(|e| io_not_written("create the store directory", e))?;
        Ok(Self {
            dir,
            instance_id: instance_id.to_owned(),
            outbox: None,
            outbox_len: 0,
            entries: BTreeMap::new(),
            epochs: BTreeMap::new(),
        })
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    fn checkpoint_name(level: CheckpointLevel) -> &'static str {
        match level {
            CheckpointLevel::Local => "checkpoint-local.json",
            CheckpointLevel::Accepted => "checkpoint-accepted.json",
        }
    }

    /// Writes `bytes` to `<name>.tmp`, fsyncs it and renames it over `name`.
    fn replace(&self, name: &str, bytes: &[u8]) -> Result<(), StoreError> {
        let target = self.dir.join(name);
        let temp = self.dir.join(format!("{name}.tmp"));
        let written = (|| {
            let mut file = File::create(&temp)?;
            file.write_all(bytes)?;
            file.sync_all()
        })();
        if let Err(error) = written {
            let _ = fs::remove_file(&temp);
            return Err(io_not_written(name, error));
        }
        fs::rename(&temp, &target).map_err(|e| {
            let _ = fs::remove_file(&temp);
            io_not_written(name, e)
        })?;
        sync_dir(&self.dir).map_err(|e| StoreError::Indeterminate(format!("{name}: {e}")))
    }

    fn remove(&self, name: &str) -> Result<(), StoreError> {
        match fs::remove_file(self.dir.join(name)) {
            Ok(()) => {
                sync_dir(&self.dir).map_err(|e| StoreError::Indeterminate(format!("{name}: {e}")))
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(io_not_written(name, error)),
        }
    }

    fn document<T: Serialize>(&self, format: &str, value: T) -> Vec<u8> {
        serde_json::to_vec(&Document {
            format: format.into(),
            instance_id: self.instance_id.clone(),
            value,
        })
        .expect("store documents serialize")
    }

    fn read_document<T: serde::de::DeserializeOwned>(
        &self,
        name: &str,
        format: &str,
    ) -> Result<Option<T>, StoreError> {
        let bytes = match fs::read(self.dir.join(name)) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(StoreError::Corrupt(format!("{name}: {error}"))),
        };
        let header: Header = serde_json::from_slice::<serde_json::Value>(&bytes)
            .ok()
            .and_then(|v| {
                Some(Header {
                    format: v.get("format")?.as_str()?.into(),
                    instance_id: v.get("instanceId")?.as_str()?.into(),
                })
            })
            .ok_or_else(|| StoreError::Unsupported(format!("{name} has no format header")))?;
        self.check_header(name, &header, format)?;
        let document: Document<T> = serde_json::from_slice(&bytes)
            .map_err(|e| StoreError::Corrupt(format!("{name}: {e}")))?;
        Ok(Some(document.value))
    }

    fn check_header(&self, name: &str, header: &Header, format: &str) -> Result<(), StoreError> {
        if header.format != format {
            return Err(StoreError::Unsupported(format!(
                "{name} has format {}",
                header.format
            )));
        }
        if header.instance_id != self.instance_id {
            return Err(StoreError::Unsupported(format!(
                "{name} belongs to instance {}",
                header.instance_id
            )));
        }
        Ok(())
    }

    fn outbox_bytes(&self) -> Vec<u8> {
        let mut out = serde_json::to_vec(&Header {
            format: OUTBOX_FORMAT.into(),
            instance_id: self.instance_id.clone(),
        })
        .expect("header");
        out.push(b'\n');
        for mark in self.epochs.values() {
            out.extend(serde_json::to_vec(&Line::Mark(mark.clone())).expect("mark"));
            out.push(b'\n');
        }
        for entry in self.entries.values() {
            out.extend(serde_json::to_vec(&Line::Entry(Box::new(entry.clone()))).expect("entry"));
            out.push(b'\n');
        }
        out
    }

    /// Rewrites the outbox from the mirror and reopens it for appending.
    fn rewrite_outbox(&mut self) -> Result<(), StoreError> {
        self.outbox = None;
        let bytes = self.outbox_bytes();
        self.replace(OUTBOX, &bytes)?;
        self.open_outbox(bytes.len() as u64)
    }

    fn open_outbox(&mut self, len: u64) -> Result<(), StoreError> {
        let file = OpenOptions::new()
            .append(true)
            .open(self.dir.join(OUTBOX))
            .map_err(|e| StoreError::Indeterminate(format!("reopen outbox: {e}")))?;
        self.outbox = Some(file);
        self.outbox_len = len;
        Ok(())
    }

    fn read_outbox(&mut self) -> Result<bool, StoreError> {
        let bytes = match fs::read(self.dir.join(OUTBOX)) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(true),
            Err(error) => return Err(StoreError::Corrupt(format!("{OUTBOX}: {error}"))),
        };
        let mut lines: Vec<&[u8]> = bytes.split(|b| *b == b'\n').collect();
        // The last element is the text after the last newline: empty for a clean log, or a torn append that
        // was never fsynced as a whole and was therefore never acknowledged to the SDK.
        let torn = !lines.pop().unwrap_or_default().is_empty();
        let header = lines
            .first()
            .ok_or_else(|| StoreError::Unsupported(format!("{OUTBOX} has no header")))?;
        let header: Header = serde_json::from_slice(header)
            .map_err(|_| StoreError::Unsupported(format!("{OUTBOX} has no format header")))?;
        self.check_header(OUTBOX, &header, OUTBOX_FORMAT)?;
        for (index, line) in lines.iter().enumerate().skip(1) {
            match serde_json::from_slice::<Line>(line) {
                Ok(Line::Mark(mark)) => {
                    self.epochs.insert(mark.lease_epoch, mark);
                }
                Ok(Line::Entry(entry)) => {
                    if self
                        .entries
                        .insert((entry.lease_epoch, entry.outbox_seq), *entry)
                        .is_some()
                    {
                        return Err(StoreError::Corrupt(format!(
                            "{OUTBOX} line {} repeats an outboxSeq",
                            index + 1
                        )));
                    }
                }
                Err(error) => {
                    return Err(StoreError::Corrupt(format!(
                        "{OUTBOX} line {}: {error}",
                        index + 1
                    )))
                }
            }
        }
        Ok(torn)
    }
}

impl Store for FsStore {
    fn load(&mut self) -> Result<Recovered, StoreError> {
        self.outbox = None;
        self.entries.clear();
        self.epochs.clear();
        for name in [
            OUTBOX,
            HALT,
            Self::checkpoint_name(CheckpointLevel::Local),
            Self::checkpoint_name(CheckpointLevel::Accepted),
        ] {
            // A temp file is a write that never reached its rename; the previous version is authoritative.
            let _ = fs::remove_file(self.dir.join(format!("{name}.tmp")));
        }
        let rewrite = self.read_outbox()?;
        let mut recovered = Recovered {
            entries: self.entries.values().cloned().collect(),
            epochs: self.epochs.clone(),
            local: self.read_document(
                Self::checkpoint_name(CheckpointLevel::Local),
                CHECKPOINT_FORMAT,
            )?,
            accepted: self.read_document(
                Self::checkpoint_name(CheckpointLevel::Accepted),
                CHECKPOINT_FORMAT,
            )?,
            halt: self.read_document(HALT, HALT_FORMAT)?,
        };
        check_recovered(&mut recovered)?;
        for (level, checkpoint) in [
            (CheckpointLevel::Local, &recovered.local),
            (CheckpointLevel::Accepted, &recovered.accepted),
        ] {
            if checkpoint.as_ref().is_some_and(|c| c.level != level) {
                return Err(StoreError::Corrupt(format!(
                    "{} holds another level",
                    Self::checkpoint_name(level)
                )));
            }
        }
        self.epochs = recovered.epochs.clone();
        if rewrite {
            self.rewrite_outbox()?;
        } else {
            let len = fs::metadata(self.dir.join(OUTBOX))
                .map_err(|e| StoreError::Corrupt(e.to_string()))?
                .len();
            self.open_outbox(len)?;
        }
        Ok(recovered)
    }

    fn append(&mut self, entry: &OutboxEntry) -> Result<(), StoreError> {
        let key = (entry.lease_epoch, entry.outbox_seq);
        let mut line = serde_json::to_vec(&Line::Entry(Box::new(entry.clone()))).expect("entry");
        line.push(b'\n');
        let file = self
            .outbox
            .as_mut()
            .ok_or_else(|| StoreError::NotWritten("store not loaded".into()))?;
        match file.write_all(&line).and_then(|()| file.sync_data()) {
            Ok(()) => {
                self.outbox_len += line.len() as u64;
                self.entries.insert(key, entry.clone());
                Ok(())
            }
            Err(error) => {
                // Roll the torn bytes back so the record is certainly absent; otherwise its fate is unknown.
                let rollback = file
                    .set_len(self.outbox_len)
                    .and_then(|()| file.sync_data());
                match rollback {
                    Ok(()) => Err(io_not_written("append", error)),
                    Err(second) => Err(StoreError::Indeterminate(format!(
                        "append: {error}; rollback: {second}"
                    ))),
                }
            }
        }
    }

    fn mark_epoch(&mut self, mark: &EpochMark) -> Result<(), StoreError> {
        let previous = (self.entries.clone(), self.epochs.clone());
        self.epochs.insert(mark.lease_epoch, mark.clone());
        let epoch = mark.lease_epoch;
        self.entries.retain(|(ep, seq), _| {
            *ep != epoch || (mark.fenced != Some(Fence::Pruned) && *seq > mark.acked_outbox_seq)
        });
        match self.rewrite_outbox() {
            Ok(()) => Ok(()),
            Err(StoreError::NotWritten(detail)) => {
                (self.entries, self.epochs) = previous;
                let len = fs::metadata(self.dir.join(OUTBOX))
                    .map(|m| m.len())
                    .unwrap_or(self.outbox_len);
                self.open_outbox(len)?;
                Err(StoreError::NotWritten(detail))
            }
            Err(other) => Err(other),
        }
    }

    fn write_checkpoint(&mut self, checkpoint: &StoredCheckpoint) -> Result<(), StoreError> {
        let bytes = self.document(CHECKPOINT_FORMAT, checkpoint);
        self.replace(Self::checkpoint_name(checkpoint.level), &bytes)
    }

    fn discard_checkpoint(&mut self, level: CheckpointLevel) -> Result<(), StoreError> {
        self.remove(Self::checkpoint_name(level))
    }

    fn write_halt(&mut self, halt: Option<&HaltRecord>) -> Result<(), StoreError> {
        match halt {
            Some(halt) => {
                let bytes = self.document(HALT_FORMAT, halt);
                self.replace(HALT, &bytes)
            }
            None => self.remove(HALT),
        }
    }

    fn destroy(&mut self) -> Result<(), StoreError> {
        self.outbox = None;
        self.entries.clear();
        self.epochs.clear();
        match fs::remove_dir_all(&self.dir) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(StoreError::Indeterminate(format!("destroy: {error}"))),
        }
    }
}
