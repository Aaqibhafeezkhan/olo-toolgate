-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_console_devices (
 tenant_id text NOT NULL, session_id text NOT NULL, user_id text NOT NULL,
 device_id text NOT NULL, nonce_digest text NOT NULL, code_digest text NOT NULL,
 csr text NOT NULL, fingerprint text NOT NULL,
 expires_at bigint NOT NULL, verified_at bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,session_id)
);
CREATE INDEX control_console_devices_expiry ON control_console_devices(tenant_id,expires_at);
GRANT SELECT,INSERT,UPDATE,DELETE ON control_console_devices TO toolgate_control_runtime;
