// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! In-memory [`Store`] with fault injection at every persistence point (feature `testing`).
//!
//! The durable state lives in a shared [`MemDisk`], so a test can "crash" the executor by dropping its
//! supervisor and recover a new one from the same disk. An armed [`Fault`] fires on the next persistence
//! operation (optionally of one kind):
//! - [`Fault::CrashBefore`]: the executor dies before the write; nothing is durable;
//! - [`Fault::CrashAfter`]: the write is durable, then the executor dies before it sees the result;
//! - [`Fault::FailClean`]: the write fails with nothing changed and the executor keeps running.

use crate::store::{
    check_recovered, CheckpointLevel, EpochMark, Fence, HaltRecord, OutboxEntry, Recovered, Store,
    StoreError, StoredCheckpoint,
};
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, MutexGuard};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpKind {
    Append,
    MarkEpoch,
    WriteLocal,
    WriteAccepted,
    DiscardCheckpoint,
    WriteHalt,
    Destroy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fault {
    CrashBefore,
    CrashAfter,
    FailClean,
}

#[derive(Debug, Clone, Default)]
pub struct MemDisk {
    pub entries: BTreeMap<(u64, u64), OutboxEntry>,
    pub epochs: BTreeMap<u64, EpochMark>,
    pub local: Option<StoredCheckpoint>,
    pub accepted: Option<StoredCheckpoint>,
    pub halt: Option<HaltRecord>,
    pub destroyed: bool,
    /// Every completed persistence operation, in order.
    pub log: Vec<OpKind>,
    /// Set when a crash fault fired; the executor using this disk must be discarded and recovered.
    pub crashed: bool,
    armed: Option<(Option<OpKind>, Fault)>,
}

impl MemDisk {
    /// Arms `fault` for the next persistence operation of `kind` (any kind when `None`).
    pub fn arm(&mut self, kind: Option<OpKind>, fault: Fault) {
        self.armed = Some((kind, fault));
    }

    pub fn disarm(&mut self) {
        self.armed = None;
    }

    pub fn is_armed(&self) -> bool {
        self.armed.is_some()
    }

    /// Wipes everything (disk loss, reinstall).
    pub fn wipe(&mut self) {
        *self = MemDisk::default();
    }
}

/// A handle on a shared [`MemDisk`].
#[derive(Debug, Clone, Default)]
pub struct MemStore {
    disk: Arc<Mutex<MemDisk>>,
}

impl MemStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn disk(&self) -> MutexGuard<'_, MemDisk> {
        self.disk
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn op(&mut self, kind: OpKind, apply: impl FnOnce(&mut MemDisk)) -> Result<(), StoreError> {
        let mut disk = self.disk();
        if disk.crashed {
            return Err(StoreError::Indeterminate("executor crashed".into()));
        }
        let fault = match disk.armed {
            Some((wanted, fault)) if wanted.is_none_or(|w| w == kind) => {
                disk.armed = None;
                Some(fault)
            }
            _ => None,
        };
        match fault {
            Some(Fault::CrashBefore) => {
                disk.crashed = true;
                Err(StoreError::Indeterminate(format!("crash before {kind:?}")))
            }
            Some(Fault::FailClean) => Err(StoreError::NotWritten(format!(
                "injected failure of {kind:?}"
            ))),
            Some(Fault::CrashAfter) => {
                apply(&mut disk);
                disk.log.push(kind);
                disk.crashed = true;
                Err(StoreError::Indeterminate(format!("crash after {kind:?}")))
            }
            None => {
                apply(&mut disk);
                disk.log.push(kind);
                Ok(())
            }
        }
    }
}

impl Store for MemStore {
    fn load(&mut self) -> Result<Recovered, StoreError> {
        let mut disk = self.disk();
        disk.crashed = false;
        if disk.destroyed {
            *disk = MemDisk::default();
        }
        let mut recovered = Recovered {
            entries: disk.entries.values().cloned().collect(),
            epochs: disk.epochs.clone(),
            local: disk.local.clone(),
            accepted: disk.accepted.clone(),
            halt: disk.halt.clone(),
        };
        check_recovered(&mut recovered)?;
        Ok(recovered)
    }

    fn append(&mut self, entry: &OutboxEntry) -> Result<(), StoreError> {
        let entry = entry.clone();
        self.op(OpKind::Append, |disk| {
            disk.entries
                .insert((entry.lease_epoch, entry.outbox_seq), entry);
        })
    }

    fn mark_epoch(&mut self, mark: &EpochMark) -> Result<(), StoreError> {
        let mark = mark.clone();
        self.op(OpKind::MarkEpoch, |disk| {
            let epoch = mark.lease_epoch;
            disk.entries.retain(|(ep, seq), _| {
                *ep != epoch || (mark.fenced != Some(Fence::Pruned) && *seq > mark.acked_outbox_seq)
            });
            disk.epochs.insert(epoch, mark);
        })
    }

    fn write_checkpoint(&mut self, checkpoint: &StoredCheckpoint) -> Result<(), StoreError> {
        let checkpoint = checkpoint.clone();
        let kind = match checkpoint.level {
            CheckpointLevel::Local => OpKind::WriteLocal,
            CheckpointLevel::Accepted => OpKind::WriteAccepted,
        };
        self.op(kind, |disk| match checkpoint.level {
            CheckpointLevel::Local => disk.local = Some(checkpoint),
            CheckpointLevel::Accepted => disk.accepted = Some(checkpoint),
        })
    }

    fn discard_checkpoint(&mut self, level: CheckpointLevel) -> Result<(), StoreError> {
        self.op(OpKind::DiscardCheckpoint, |disk| match level {
            CheckpointLevel::Local => disk.local = None,
            CheckpointLevel::Accepted => disk.accepted = None,
        })
    }

    fn write_halt(&mut self, halt: Option<&HaltRecord>) -> Result<(), StoreError> {
        let halt = halt.cloned();
        self.op(OpKind::WriteHalt, |disk| disk.halt = halt)
    }

    fn destroy(&mut self) -> Result<(), StoreError> {
        self.op(OpKind::Destroy, |disk| {
            let log = std::mem::take(&mut disk.log);
            *disk = MemDisk {
                destroyed: true,
                log,
                ..MemDisk::default()
            };
        })
    }
}
