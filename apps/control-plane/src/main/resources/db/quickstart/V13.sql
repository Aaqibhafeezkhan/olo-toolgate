-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_server_packages(tenant_id TEXT NOT NULL,package_digest TEXT NOT NULL,package_id TEXT NOT NULL,version TEXT NOT NULL,
    uploaded_at INTEGER NOT NULL,document TEXT NOT NULL,content BLOB NOT NULL,PRIMARY KEY(tenant_id,package_digest));
-- statement
CREATE TABLE control_server_documents(tenant_id TEXT NOT NULL,digest TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,digest));
-- statement
CREATE TABLE control_server_deployments(tenant_id TEXT NOT NULL,package_id TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,package_id));
-- statement
CREATE TABLE control_tool_hosts(tenant_id TEXT NOT NULL,device_id TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,device_id));
