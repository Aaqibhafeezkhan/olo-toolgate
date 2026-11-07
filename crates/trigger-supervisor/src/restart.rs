// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Restart backoff and crash-loop detection (plan 6.9).
//!
//! `sleep = random(0, min(5 min, 5 s × 2^attempt))`. After 5 failed starts within 10 minutes the instance is
//! `Failed (CrashLoop)` until the config revision changes or an admin `RUN` resets the budget. A run of
//! 10 minutes also resets it.

pub const BASE_DELAY_MS: u64 = 5_000;
pub const MAX_DELAY_MS: u64 = 5 * 60_000;
pub const CRASH_LOOP_FAILURES: usize = 5;
pub const CRASH_LOOP_WINDOW_MS: u64 = 10 * 60_000;
pub const STABLE_RUN_MS: u64 = 10 * 60_000;

/// Randomness for full jitter and generated event ids. Production uses [`SystemRandom`].
pub trait Random: Send {
    fn next_u64(&mut self) -> u64;
}

/// The operating system's CSPRNG.
pub struct SystemRandom(ring::rand::SystemRandom);

impl Default for SystemRandom {
    fn default() -> Self {
        Self(ring::rand::SystemRandom::new())
    }
}

impl Random for SystemRandom {
    fn next_u64(&mut self) -> u64 {
        let mut bytes = [0u8; 8];
        ring::rand::SecureRandom::fill(&self.0, &mut bytes)
            .expect("the system CSPRNG is available");
        u64::from_le_bytes(bytes)
    }
}

/// A deterministic generator (SplitMix64) for tests and simulations.
pub struct SeededRandom(pub u64);

impl Random for SeededRandom {
    fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RestartBudget {
    failures: Vec<u64>,
    attempt: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RestartDecision {
    /// Start again at this time.
    RetryAt(u64),
    /// `Failed (CrashLoop)`.
    CrashLoop,
}

impl RestartBudget {
    /// The upper bound of the next jittered delay.
    pub fn ceiling_ms(&self) -> u64 {
        BASE_DELAY_MS
            .saturating_mul(1u64 << self.attempt.min(16))
            .min(MAX_DELAY_MS)
    }

    /// Records a failed start or an unexpected exit at `now_ms`.
    pub fn failed(&mut self, now_ms: u64, random: &mut dyn Random) -> RestartDecision {
        self.failures
            .retain(|at| now_ms.saturating_sub(*at) < CRASH_LOOP_WINDOW_MS);
        self.failures.push(now_ms);
        if self.failures.len() >= CRASH_LOOP_FAILURES {
            return RestartDecision::CrashLoop;
        }
        let ceiling = self.ceiling_ms();
        self.attempt = self.attempt.saturating_add(1);
        RestartDecision::RetryAt(now_ms + random.next_u64() % (ceiling + 1))
    }

    /// A process that ran for [`STABLE_RUN_MS`] resets the budget.
    pub fn ran(&mut self, started_ms: u64, now_ms: u64) {
        if now_ms.saturating_sub(started_ms) >= STABLE_RUN_MS {
            self.reset();
        }
    }

    /// Config revision change or admin `RUN`.
    pub fn reset(&mut self) {
        self.failures.clear();
        self.attempt = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delays_are_full_jitter_under_a_doubling_capped_ceiling() {
        let mut budget = RestartBudget::default();
        let mut random = SeededRandom(7);
        let mut now = 0;
        for expected in [5_000, 10_000, 20_000, 40_000] {
            assert_eq!(budget.ceiling_ms(), expected);
            let RestartDecision::RetryAt(at) = budget.failed(now, &mut random) else {
                panic!("crash loop too early")
            };
            assert!(at >= now && at <= now + expected);
            now += 4 * 60_000; // spread out so that only the last ones share a window
        }
        let mut long = RestartBudget {
            failures: Vec::new(),
            attempt: 12,
        };
        assert_eq!(long.ceiling_ms(), MAX_DELAY_MS);
        assert!(
            matches!(long.failed(0, &mut random), RestartDecision::RetryAt(at) if at <= MAX_DELAY_MS)
        );
    }

    #[test]
    fn five_failures_in_ten_minutes_are_a_crash_loop_until_reset() {
        let mut budget = RestartBudget::default();
        let mut random = SeededRandom(1);
        for minute in 0..4 {
            assert!(matches!(
                budget.failed(minute * 60_000, &mut random),
                RestartDecision::RetryAt(_)
            ));
        }
        assert_eq!(
            budget.failed(9 * 60_000, &mut random),
            RestartDecision::CrashLoop
        );
        budget.reset();
        assert!(matches!(
            budget.failed(9 * 60_000, &mut random),
            RestartDecision::RetryAt(_)
        ));
    }

    #[test]
    fn failures_outside_the_window_and_stable_runs_do_not_count() {
        let mut budget = RestartBudget::default();
        let mut random = SeededRandom(2);
        for minute in [0, 3, 6, 9] {
            budget.failed(minute * 60_000, &mut random);
        }
        // The failure at minute 0 has left the window by minute 11.
        assert!(matches!(
            budget.failed(11 * 60_000, &mut random),
            RestartDecision::RetryAt(_)
        ));
        budget.ran(11 * 60_000, 21 * 60_000);
        assert_eq!(budget.ceiling_ms(), BASE_DELAY_MS);
    }
}
