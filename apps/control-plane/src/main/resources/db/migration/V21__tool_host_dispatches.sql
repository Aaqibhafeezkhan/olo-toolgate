-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Calls for Tool Hosts that fetch their work from Control instead of taking it from the Gateway (pull mode), so a host
-- on a laptop behind NAT runs tools the same way as one in the data centre.
CREATE TABLE control_tool_host_dispatches(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,host_id varchar(128) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('QUEUED','CLAIMED','ADMITTED','REFUSED','FINISHED','EXPIRED')),
    expires_at bigint NOT NULL CHECK(expires_at BETWEEN 0 AND 9007199254740991),created_at bigint NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
    document text NOT NULL CHECK(octet_length(document)<=16777216),PRIMARY KEY(tenant_id,invocation_id));
CREATE INDEX control_tool_host_dispatches_queue ON control_tool_host_dispatches(tenant_id,host_id,state,created_at);

GRANT SELECT,INSERT,UPDATE,DELETE ON control_tool_host_dispatches TO toolgate_control_runtime;
