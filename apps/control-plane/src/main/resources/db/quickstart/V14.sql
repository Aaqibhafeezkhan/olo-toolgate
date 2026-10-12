-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_tool_host_dispatches(tenant_id TEXT NOT NULL,invocation_id TEXT NOT NULL,host_id TEXT NOT NULL,state TEXT NOT NULL,
    expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE INDEX control_tool_host_dispatches_queue ON control_tool_host_dispatches(tenant_id,host_id,state,created_at);
