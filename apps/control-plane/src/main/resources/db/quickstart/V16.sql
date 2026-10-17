-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_console_devices (
 tenant_id TEXT NOT NULL, session_id TEXT NOT NULL, user_id TEXT NOT NULL,
 device_id TEXT NOT NULL, nonce_digest TEXT NOT NULL, code_digest TEXT NOT NULL,
 csr TEXT NOT NULL, fingerprint TEXT NOT NULL,
 expires_at INTEGER NOT NULL, verified_at INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,session_id)
);
-- statement
CREATE INDEX control_console_devices_expiry ON control_console_devices(tenant_id,expires_at);
