-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Server packages run by Tool Hosts: sealed uploads by package digest, the canonical service profile and globals
-- documents Tool Hosts fetch by digest, one current deployment per package id, and registered Tool Hosts.
CREATE TABLE control_server_packages(tenant_id varchar(128) NOT NULL,package_digest char(71) NOT NULL CHECK(package_digest ~ '^sha256:[a-f0-9]{64}$'),
    package_id varchar(127) NOT NULL,version varchar(128) NOT NULL,uploaded_at bigint NOT NULL CHECK(uploaded_at BETWEEN 0 AND 9007199254740991),
    document text NOT NULL CHECK(octet_length(document)<=4194304),content bytea NOT NULL CHECK(octet_length(content)<=67108864),
    PRIMARY KEY(tenant_id,package_digest));
CREATE TABLE control_server_documents(tenant_id varchar(128) NOT NULL,digest char(71) NOT NULL CHECK(digest ~ '^sha256:[a-f0-9]{64}$'),
    document text NOT NULL CHECK(octet_length(document)<=1048576),PRIMARY KEY(tenant_id,digest));
CREATE TABLE control_server_deployments(tenant_id varchar(128) NOT NULL,package_id varchar(127) NOT NULL,
    document text NOT NULL CHECK(octet_length(document)<=65536),PRIMARY KEY(tenant_id,package_id));
CREATE TABLE control_tool_hosts(tenant_id varchar(128) NOT NULL,device_id varchar(128) NOT NULL,
    document text NOT NULL CHECK(octet_length(document)<=16384),PRIMARY KEY(tenant_id,device_id));

GRANT SELECT,INSERT ON control_server_packages,control_server_documents TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON control_server_deployments,control_tool_hosts TO toolgate_control_runtime;
