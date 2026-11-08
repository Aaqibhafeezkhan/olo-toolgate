// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Trigger supervisor shared by the endpoint client and the Tool Host (issue #274, ADR 058, plan 6).
//!
//! - [`canonical`]: the canonical form and digest of trigger descriptors (NFC, RFC 8785).
//! - [`frames`]: the trigger-mode stdio frames, over the generated contract types.
//! - [`store`]: the durable outbox (`outboxSeq`), checkpoints and halt condition, with [`store::FsStore`].
//! - [`supervisor`]: the per-instance state machine that implements the executor side of
//!   `docs/specs/trigger_delivery.tla`: the contiguous `LOCAL`/`ACCEPTED` barrier, rejection classes, loss
//!   records, Control acknowledgements and fencing, leases, health, restarts and removal.
//! - [`restart`]: backoff with full jitter and crash-loop detection.
//! - [`process`]: spawning and stopping the trigger process with tokio.
//!
//! The endpoint client links this crate (module 5 of plan 24.2); the Tool Host links it in module 8.

pub mod canonical;
pub mod frames;
#[cfg(feature = "testing")]
pub mod memory;
pub mod process;
pub mod restart;
pub mod store;
pub mod supervisor;
pub mod time;

pub use frames::{Inbound, Outbound};
pub use store::{FsStore, Store, StoredCheckpoint};
pub use supervisor::{Action, InstanceConfig, Lease, OutboxBounds, Phase, Supervisor};
