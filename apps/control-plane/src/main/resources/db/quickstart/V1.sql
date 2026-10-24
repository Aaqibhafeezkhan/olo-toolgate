-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Initial release schema. Initialize an empty database; pre-release schemas are unsupported.

CREATE TABLE control_tenants(tenant_id TEXT NOT NULL PRIMARY KEY CHECK(length(tenant_id)=36 AND tenant_id NOT GLOB '*[^0-9a-f-]*' AND substr(tenant_id,9,1)='-' AND substr(tenant_id,14,1)='-' AND substr(tenant_id,19,1)='-' AND substr(tenant_id,24,1)='-' AND length(replace(tenant_id,'-',''))=32),alias TEXT NOT NULL UNIQUE CHECK(length(alias) BETWEEN 1 AND 63 AND alias NOT GLOB '*[^a-z0-9-]*' AND substr(alias,1,1) GLOB '[a-z0-9]'),display_name TEXT NOT NULL CHECK(length(display_name) BETWEEN 1 AND 256),status TEXT NOT NULL CHECK(status IN('PROVISIONING','ACTIVE','SUSPENDED')),created_at INTEGER NOT NULL CHECK(created_at>=0),updated_at INTEGER NOT NULL CHECK(updated_at>=created_at),initialization_failure_code TEXT CHECK(initialization_failure_code='INITIALIZATION_FAILED'),initialization_failed_at INTEGER CHECK(initialization_failed_at>=0),CHECK((initialization_failure_code IS NULL)=(initialization_failed_at IS NULL)),CHECK(status='PROVISIONING' OR initialization_failure_code IS NULL));
-- statement
CREATE TABLE control_tenant_authority(tenant_id TEXT NOT NULL PRIMARY KEY REFERENCES control_tenants(tenant_id),revision INTEGER NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND 9007199254740991),authorization_epoch INTEGER NOT NULL DEFAULT 0 CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),directory_cache_generation INTEGER NOT NULL DEFAULT 0 CHECK(directory_cache_generation>=0));
-- statement
CREATE TABLE control_tenant_hosts(hostname TEXT NOT NULL PRIMARY KEY CHECK(hostname=lower(hostname) AND length(hostname) BETWEEN 1 AND 253),tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id));
-- statement
CREATE TABLE control_tenant_initializations(tenant_id TEXT NOT NULL PRIMARY KEY REFERENCES control_tenants(tenant_id),version INTEGER NOT NULL CHECK(version=1),effective_digest TEXT NOT NULL CHECK(length(effective_digest)=64 AND effective_digest NOT GLOB '*[^a-f0-9]*'),provenance TEXT NOT NULL CHECK(length(CAST(provenance AS BLOB))<=65536),completed_at INTEGER NOT NULL CHECK(completed_at>=0));
-- statement
CREATE TABLE control_tenant_identity_keys(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),purpose TEXT NOT NULL CHECK(purpose IN ('DISCOVERY','DEVICE_CA')),key_id TEXT NOT NULL CHECK(length(key_id) BETWEEN 1 AND 128),state TEXT NOT NULL CHECK(state IN ('ACTIVE','READ_ONLY','RETIRED')),provider_reference TEXT NOT NULL CHECK(length(provider_reference)<=512),public_material TEXT NOT NULL CHECK(length(CAST(public_material AS BLOB))<=16384),key_fingerprint TEXT NOT NULL UNIQUE CHECK(length(key_fingerprint)=64 AND key_fingerprint NOT GLOB '*[^a-f0-9]*'),generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),PRIMARY KEY(tenant_id,purpose,key_id));
-- statement
CREATE UNIQUE INDEX control_tenant_identity_keys_active ON control_tenant_identity_keys(tenant_id,purpose) WHERE state='ACTIVE';
-- statement
CREATE TRIGGER control_tenant_identity_key_guard BEFORE UPDATE ON control_tenant_identity_keys WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.purpose<>OLD.purpose OR NEW.key_id<>OLD.key_id OR NEW.provider_reference<>OLD.provider_reference OR NEW.public_material<>OLD.public_material OR NEW.key_fingerprint<>OLD.key_fingerprint OR NEW.generation<OLD.generation OR (OLD.state='RETIRED' AND NEW.state<>'RETIRED') BEGIN SELECT RAISE(ABORT,'invalid identity key transition'); END;
-- statement
CREATE TRIGGER control_tenant_identity_keys_no_delete BEFORE DELETE ON control_tenant_identity_keys BEGIN SELECT RAISE(ABORT,'identity key retention requires explicit offboarding'); END;
-- statement
CREATE TRIGGER control_tenant_identity_keys_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenant_identity_keys WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TABLE control_tenant_runtime_profiles(tenant_id TEXT NOT NULL PRIMARY KEY REFERENCES control_tenants(tenant_id),server_id TEXT NOT NULL CHECK(length(server_id) BETWEEN 1 AND 128),organization TEXT NOT NULL CHECK(length(organization) BETWEEN 1 AND 256),control_url TEXT NOT NULL CHECK(length(CAST(control_url AS BLOB))<=2048),gateway_url TEXT NOT NULL CHECK(length(CAST(gateway_url AS BLOB))<=2048));
-- statement
CREATE TRIGGER control_tenant_runtime_profiles_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenant_runtime_profiles WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_tenant_initializations_immutable_update BEFORE UPDATE ON control_tenant_initializations BEGIN SELECT RAISE(ABORT,'initialization is immutable'); END;
-- statement
CREATE TRIGGER control_tenant_initializations_immutable_delete BEFORE DELETE ON control_tenant_initializations BEGIN SELECT RAISE(ABORT,'initialization is immutable'); END;
-- statement
CREATE TABLE control_record_ids(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL,record_id TEXT NOT NULL,PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_audit(sequence INTEGER PRIMARY KEY AUTOINCREMENT,tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,actor_id TEXT NOT NULL,operation TEXT NOT NULL,target TEXT NOT NULL,revision INTEGER NOT NULL,request_id TEXT NOT NULL,request_digest TEXT NOT NULL,evidence TEXT CHECK(evidence IS NULL OR length(CAST(evidence AS BLOB))<=4096),occurred_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
-- statement
CREATE TABLE control_idempotency(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,actor_id TEXT NOT NULL,key TEXT NOT NULL,request_digest TEXT NOT NULL,status INTEGER NOT NULL,body TEXT NOT NULL,revision INTEGER NOT NULL,expires_at INTEGER NOT NULL DEFAULT(unixepoch()+86400),PRIMARY KEY(tenant_id,actor_id,key));
-- statement
CREATE TABLE control_approval_clocks(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,observed_at INTEGER NOT NULL CHECK(observed_at BETWEEN 0 AND 9007199254740991));
-- statement
CREATE TABLE control_enrollments(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,enrollment_id TEXT NOT NULL,code_digest TEXT NOT NULL,device_digest TEXT NOT NULL,document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=16384),csr TEXT NOT NULL CHECK(length(CAST(csr AS BLOB))<=16384),user_id TEXT,certificate TEXT CHECK(length(CAST(certificate AS BLOB))<=32768),expires_at INTEGER NOT NULL CHECK(expires_at>=0),last_poll INTEGER NOT NULL DEFAULT 0 CHECK(last_poll>=0),PRIMARY KEY(tenant_id,enrollment_id),UNIQUE(tenant_id,code_digest));
-- statement
CREATE TABLE control_fleet(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL CHECK(kind IN('RELEASE','DESIRED','ROLLOUT')),record_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=131072),PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_builder(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL CHECK(kind IN('DRAFT','VERSION','TEST')),record_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=65536),PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE "control_endpoints"(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,device_id TEXT NOT NULL,key_fingerprint TEXT NOT NULL,document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_extract(document,'$.deviceId')=device_id AND json_extract(document,'$.tenantId')=tenant_id),csr TEXT NOT NULL CHECK(length(CAST(csr AS BLOB))<=16384),report_digest TEXT NOT NULL,acknowledgment TEXT NOT NULL CHECK(length(CAST(acknowledgment AS BLOB))<=131072),PRIMARY KEY(tenant_id,device_id),UNIQUE(tenant_id,key_fingerprint));
-- statement
CREATE TABLE control_endpoint_configurations (
    tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,
    device_id TEXT NOT NULL,
    source_revision INTEGER NOT NULL CHECK (source_revision>=0),
    document TEXT NOT NULL CHECK (length(CAST(document AS BLOB))<=98304),
    acknowledged_digest TEXT,
    local_tools TEXT NOT NULL CHECK (length(CAST(local_tools AS BLOB))<=49152),
    PRIMARY KEY (tenant_id,device_id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES control_endpoints(tenant_id,device_id)
);
-- statement
CREATE TABLE control_mcp_requests (
    tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL, request_id TEXT NOT NULL, device_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED','RESPONSE_RECEIVED','DONE','FAILED','EXPIRED')),
    received_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
    record TEXT NOT NULL CHECK (length(CAST(record AS BLOB))<=8192),
    task TEXT NOT NULL CHECK (length(CAST(task AS BLOB))<=16384),
    result TEXT CHECK (length(CAST(result AS BLOB))<=65536),
    PRIMARY KEY(tenant_id,request_id),
    FOREIGN KEY(tenant_id,device_id) REFERENCES control_endpoints(tenant_id,device_id)
);
-- statement
CREATE TABLE "control_policy_bundles"(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),directory_revision INTEGER NOT NULL,document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=2000100),policy TEXT NOT NULL CHECK(length(CAST(policy AS BLOB))<=1048576),published_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,sequence));
-- statement
CREATE TABLE "control_records"(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL CHECK(kind IN('USER','TEAM','AGENT','TOOL','POLICY','DEVICE','ROLE','DEVICE_GROUP','AGENT_GROUP','TOOL_GROUP','GRANT','DELEGATION','AGENT_DELEGATION','BINDING','EXTRACTOR','WORKLOAD_BINDING','IDENTITY_BINDING','DEVICE_EVIDENCE')),record_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(json_valid(document) AND json_extract(document,'$.id')=record_id AND json_extract(document,'$.revision')=revision),PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_authorization_outbox(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,revision bigint NOT NULL,authorization_epoch bigint NOT NULL,occurred_at bigint NOT NULL,published_at bigint,PRIMARY KEY(tenant_id,revision));
-- statement
CREATE TABLE control_enterprise_invocations(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id varchar(128) NOT NULL,request_digest char(64) NOT NULL,state varchar(32) NOT NULL,authorization_epoch bigint NOT NULL,created_at bigint NOT NULL,expires_at bigint NOT NULL,revision bigint NOT NULL,document text NOT NULL, tool_id TEXT CHECK(length(tool_id) BETWEEN 1 AND 256), package_id TEXT CHECK(length(package_id) BETWEEN 1 AND 128), package_digest TEXT CHECK(length(package_digest)=71 AND substr(package_digest,1,7)='sha256:' AND substr(package_digest,8) NOT GLOB '*[^0-9a-f]*'), pool_id TEXT CHECK(length(pool_id) BETWEEN 1 AND 128), runtime_digest TEXT CHECK(length(runtime_digest)=71 AND substr(runtime_digest,1,7)='sha256:' AND substr(runtime_digest,8) NOT GLOB '*[^0-9a-f]*'), effect_safety TEXT CHECK(effect_safety IN('READ_ONLY','IDEMPOTENT','NON_IDEMPOTENT','UNKNOWN')), requires_execution_ledger INTEGER CHECK(requires_execution_ledger IN(0,1)), lease_holder TEXT CHECK(length(lease_holder) BETWEEN 1 AND 256), lease_expires_at INTEGER CHECK(lease_expires_at BETWEEN 0 AND 9007199254740991), cancel_requested_at INTEGER CHECK(cancel_requested_at BETWEEN 0 AND 9007199254740991), not_started INTEGER NOT NULL DEFAULT 0 CHECK(not_started IN(0,1)), task_id TEXT CHECK(length(task_id) BETWEEN 22 AND 64 AND task_id NOT GLOB '*[^A-Za-z0-9_-]*'), task_state TEXT, task_owner_digest TEXT CHECK(length(task_owner_digest)=64 AND task_owner_digest NOT GLOB '*[^0-9a-f]*'), task_retain_until INTEGER CHECK(task_retain_until BETWEEN 0 AND 9007199254740991), task_input_deadline_at INTEGER CHECK(task_input_deadline_at BETWEEN 0 AND 9007199254740991), task_last_polled_at INTEGER CHECK(task_last_polled_at BETWEEN 0 AND 9007199254740991), task_document TEXT CHECK(length(CAST(task_document AS BLOB))<=32768),PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_enterprise_approvals(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,approval_id varchar(128) NOT NULL,approval_type varchar(16) NOT NULL CHECK(approval_type IN('OPERATION','CONFIGURATION')),invocation_id varchar(128) NOT NULL,request_digest char(64) NOT NULL,authorization_epoch bigint NOT NULL,expires_at bigint NOT NULL,revision bigint NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,approval_id));
-- statement
CREATE TABLE control_enterprise_nonces(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,nonce varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,evaluation_digest char(64) NOT NULL,authorization_epoch bigint NOT NULL,issued_at bigint NOT NULL,expires_at bigint NOT NULL,consumed_at bigint,PRIMARY KEY(tenant_id,nonce));
-- statement
CREATE TABLE control_enterprise_adoptions(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,actor_type varchar(16) NOT NULL,actor_id varchar(128) NOT NULL,directory_revision bigint NOT NULL,snapshot_sequence bigint NOT NULL,graph_digest char(64) NOT NULL,observed_at bigint NOT NULL, authorization_epoch bigint NOT NULL DEFAULT 0,PRIMARY KEY(tenant_id,actor_type,actor_id));
-- statement
CREATE TABLE control_invocation_authority(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),invocation_id TEXT NOT NULL,expires_at bigint NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_operation_cancellations(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),invocation_id TEXT NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_credential_history(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,credential_sha256 TEXT NOT NULL CHECK(length(credential_sha256)=64),PRIMARY KEY(tenant_id,credential_sha256));
-- statement
CREATE TABLE control_reviewed_recovery(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,review_digest TEXT NOT NULL CHECK(length(review_digest)=64),PRIMARY KEY(tenant_id,review_digest));
-- statement
CREATE TABLE control_configuration_changes(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,change_id varchar(128) NOT NULL,revision bigint NOT NULL,request_digest char(64) NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,change_id));
-- statement
CREATE TABLE control_tenant_vault_keys(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),version INTEGER NOT NULL CHECK(version BETWEEN 1 AND 1024),state TEXT NOT NULL CHECK(state IN('ACTIVE','READ_ONLY','RETIRED')),provider_reference TEXT NOT NULL CHECK(length(provider_reference)<=128),wrapped_encryption_key TEXT NOT NULL CHECK(length(wrapped_encryption_key)<=1024),wrapped_retry_key TEXT NOT NULL CHECK(length(wrapped_retry_key)<=1024),PRIMARY KEY(tenant_id,version));
-- statement
CREATE UNIQUE INDEX control_tenant_vault_keys_current ON control_tenant_vault_keys(tenant_id) WHERE state='ACTIVE';
-- statement
CREATE TABLE control_secrets(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,name varchar(128) NOT NULL,tool_group_id varchar(128) NOT NULL,device_group_id varchar(128) NOT NULL,version INTEGER NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),key_version INTEGER NOT NULL,cipher text NOT NULL CHECK(length(cipher)<=65536),PRIMARY KEY(tenant_id,name),FOREIGN KEY(tenant_id,key_version) REFERENCES control_tenant_vault_keys(tenant_id,version));
-- statement
CREATE TRIGGER control_vault_key_guard BEFORE UPDATE ON control_tenant_vault_keys WHEN NEW.version<>OLD.version OR NEW.tenant_id<>OLD.tenant_id OR OLD.state='RETIRED' OR (NEW.state<>OLD.state AND NOT ((OLD.state='ACTIVE' AND NEW.state='READ_ONLY') OR (OLD.state='READ_ONLY' AND NEW.state='RETIRED'))) OR (NEW.state='RETIRED' AND EXISTS(SELECT 1 FROM control_secrets WHERE tenant_id=OLD.tenant_id AND key_version=OLD.version)) BEGIN SELECT RAISE(ABORT,'invalid vault key transition'); END;
-- statement
CREATE TRIGGER control_vault_key_no_delete BEFORE DELETE ON control_tenant_vault_keys BEGIN SELECT RAISE(ABORT,'vault key retention requires explicit offboarding'); END;
-- statement
CREATE TRIGGER control_secret_version_guard BEFORE UPDATE ON control_secrets WHEN NEW.name<>OLD.name OR NEW.version<>OLD.version+1 BEGIN SELECT RAISE(ABORT,'invalid secret version transition'); END;
-- statement
CREATE TABLE control_queued_authority(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL,record_id TEXT NOT NULL,creator_user_id TEXT NOT NULL,PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_execution_budgets(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,budget_id TEXT NOT NULL,invocation_id TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(tenant_id,budget_id,invocation_id));
-- statement
CREATE TABLE control_outcome_reconciliations(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,reconciliation_id varchar(128) NOT NULL,revision bigint NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,reconciliation_id));
-- statement
CREATE TABLE control_recovery_floors(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,snapshot_sequence bigint NOT NULL CHECK(snapshot_sequence>=0));
-- statement
CREATE TABLE control_server_settings(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=16384));
-- statement
CREATE TABLE control_business_keys(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,tool_id TEXT NOT NULL CHECK(length(tool_id) BETWEEN 1 AND 256),namespace TEXT NOT NULL CHECK(namespace IN('AGENT','DELEGATED_USER','TENANT')),namespace_identity TEXT NOT NULL CHECK(length(namespace_identity)<=128),business_key_hash TEXT NOT NULL CHECK(length(business_key_hash)=64 AND business_key_hash NOT GLOB '*[^0-9a-f]*'),source TEXT NOT NULL CHECK(source IN('HEADER','ARGUMENT','DEDUPE_WINDOW')),fingerprint TEXT NOT NULL CHECK(length(fingerprint)=71 AND substr(fingerprint,1,7)='sha256:' AND substr(fingerprint,8) NOT GLOB '*[^0-9a-f]*'),caller_agent_id TEXT NOT NULL,caller_user_id TEXT,invocation_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),dedupe_expires_at INTEGER CHECK(dedupe_expires_at BETWEEN 0 AND 9007199254740991),retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),outcome TEXT CHECK(outcome IN('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED','OUTCOME_UNKNOWN')),tombstoned_at INTEGER CHECK(tombstoned_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,tool_id,namespace,namespace_identity,business_key_hash),CHECK((namespace='TENANT')=(namespace_identity='')),CHECK((source='DEDUPE_WINDOW')=(dedupe_expires_at IS NOT NULL)),CHECK(dedupe_expires_at IS NULL OR dedupe_expires_at>created_at),CHECK(retain_until>=created_at),CHECK(tombstoned_at IS NULL OR outcome IS NOT NULL));
-- statement
CREATE TABLE control_invocation_results(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,fencing_nonce TEXT NOT NULL,result_digest TEXT NOT NULL CHECK(length(result_digest)=71 AND substr(result_digest,1,7)='sha256:' AND substr(result_digest,8) NOT GLOB '*[^0-9a-f]*'),byte_length INTEGER NOT NULL CHECK(byte_length BETWEEN 0 AND 4194304),payload BLOB,created_at INTEGER NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),purged_at INTEGER CHECK(purged_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,invocation_id),FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id),CHECK((payload IS NULL)=(purged_at IS NOT NULL)),CHECK(payload IS NULL OR length(payload)=byte_length),CHECK(retain_until>=created_at));
-- statement
CREATE TABLE control_bootstrap_admins(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,user_id TEXT NOT NULL);
-- statement
CREATE TABLE control_server_packages(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,package_digest TEXT NOT NULL,package_id TEXT NOT NULL,version TEXT NOT NULL,
    uploaded_at INTEGER NOT NULL,document TEXT NOT NULL,content BLOB NOT NULL,PRIMARY KEY(tenant_id,package_digest));
-- statement
CREATE TABLE control_server_documents(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,digest TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,digest));
-- statement
CREATE TABLE control_server_deployments(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,package_id TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,package_id));
-- statement
CREATE TABLE control_tool_hosts(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,device_id TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,device_id));
-- statement
CREATE TABLE control_tool_host_dispatches(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,host_id TEXT NOT NULL,state TEXT NOT NULL,
    expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_mcp_servers(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,slug TEXT NOT NULL,revision INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,slug));
-- statement
CREATE TABLE control_mcp_snapshots(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,slug TEXT NOT NULL,revision INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,slug,revision));
-- statement
CREATE TABLE control_mcp_approvals(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,slug TEXT NOT NULL,sequence INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,slug,sequence));
-- statement
CREATE TABLE control_mcp_scans(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,scan_id TEXT NOT NULL,slug TEXT NOT NULL,host_id TEXT NOT NULL,state TEXT NOT NULL,
    deadline INTEGER NOT NULL,created_at INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,scan_id));
-- statement
CREATE TABLE control_console_devices (
 tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL, session_id TEXT NOT NULL, user_id TEXT NOT NULL,
 device_id TEXT NOT NULL, nonce_digest TEXT NOT NULL, code_digest TEXT NOT NULL,
 csr TEXT NOT NULL, fingerprint TEXT NOT NULL,
 expires_at INTEGER NOT NULL, verified_at INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,session_id)
);
-- statement
CREATE TABLE control_invocation_event_streams(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,
    last_cursor INTEGER NOT NULL CHECK(last_cursor BETWEEN 0 AND 9007199254740991),
    event_count INTEGER NOT NULL CHECK(event_count>=0),byte_count INTEGER NOT NULL CHECK(byte_count>=0),
    dropped_frames INTEGER NOT NULL CHECK(dropped_frames>=0),refused_frames INTEGER NOT NULL CHECK(refused_frames>=0),
    retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),purged_at INTEGER CHECK(purged_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY(tenant_id,invocation_id),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id));
-- statement
CREATE TABLE control_invocation_events(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,
    event_cursor INTEGER NOT NULL CHECK(event_cursor BETWEEN 1 AND 9007199254740991),
    source TEXT NOT NULL CHECK(source IN('EXECUTOR','PLATFORM')),kind TEXT NOT NULL CHECK(kind IN('LOG','PROGRESS','INPUT_REQUEST','STATE','RESULT')),
    attempt INTEGER CHECK(attempt BETWEEN 1 AND 16),fencing_token TEXT,sequence INTEGER CHECK(sequence BETWEEN 1 AND 2147483647),
    recorded_at INTEGER NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=131072),
    PRIMARY KEY(tenant_id,invocation_id,event_cursor),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_invocation_event_streams(tenant_id,invocation_id),
    CHECK((source='EXECUTOR')=(fencing_token IS NOT NULL AND attempt IS NOT NULL)),
    CHECK(sequence IS NULL OR source='EXECUTOR'));
-- statement
CREATE TABLE control_package_provisioning(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,profile_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71 AND substr(profile_digest,1,7)='sha256:'),
    document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=262144),PRIMARY KEY(tenant_id,profile_id));
-- statement
CREATE INDEX control_audit_local_cursor ON control_audit(tenant_id,sequence);
-- statement
CREATE INDEX control_enrollments_local_cursor ON control_enrollments(tenant_id,expires_at);
-- statement
CREATE INDEX control_idempotency_local_cursor ON control_idempotency(tenant_id,expires_at);
-- statement
CREATE INDEX control_mcp_pending ON control_mcp_requests(tenant_id,device_id,state,received_at);
-- statement
CREATE INDEX control_enterprise_invocation_window ON control_enterprise_invocations(tenant_id,created_at);
-- statement
CREATE INDEX control_budget_window ON control_execution_budgets(tenant_id,budget_id,created_at);
-- statement
CREATE UNIQUE INDEX control_invocation_task ON control_enterprise_invocations(tenant_id,task_id) WHERE task_id IS NOT NULL;
-- statement
CREATE INDEX control_invocation_lease ON control_enterprise_invocations(tenant_id,lease_expires_at) WHERE state='EXECUTING';
-- statement
CREATE INDEX control_invocation_task_retention ON control_enterprise_invocations(tenant_id,task_retain_until) WHERE task_id IS NOT NULL;
-- statement
CREATE INDEX control_invocation_input_deadline ON control_enterprise_invocations(tenant_id,task_input_deadline_at) WHERE task_state IN('AWAITING_APPROVAL','INPUT_REQUIRED');
-- statement
CREATE INDEX control_invocation_active_package ON control_enterprise_invocations(tenant_id,package_digest) WHERE state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');
-- statement
CREATE INDEX control_invocation_active_tool ON control_enterprise_invocations(tenant_id,tool_id) WHERE state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');
-- statement
CREATE INDEX control_business_key_invocation ON control_business_keys(tenant_id,invocation_id);
-- statement
CREATE INDEX control_business_key_retention ON control_business_keys(tenant_id,retain_until) WHERE tombstoned_at IS NULL;
-- statement
CREATE INDEX control_invocation_result_retention ON control_invocation_results(tenant_id,retain_until) WHERE purged_at IS NULL;
-- statement
CREATE INDEX control_tool_host_dispatches_queue ON control_tool_host_dispatches(tenant_id,host_id,state,created_at);
-- statement
CREATE INDEX control_mcp_scans_queue ON control_mcp_scans(tenant_id,host_id,state,created_at);
-- statement
CREATE INDEX control_mcp_scans_server ON control_mcp_scans(tenant_id,slug,state);
-- statement
CREATE INDEX control_console_devices_expiry ON control_console_devices(tenant_id,expires_at);
-- statement
CREATE INDEX control_invocation_event_stream_retention ON control_invocation_event_streams(tenant_id,retain_until) WHERE purged_at IS NULL;
-- statement
CREATE UNIQUE INDEX control_invocation_event_identity ON control_invocation_events(tenant_id,invocation_id,fencing_token,sequence) WHERE sequence IS NOT NULL;
-- statement
CREATE TRIGGER control_audit_update BEFORE UPDATE ON control_audit BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_audit_delete BEFORE DELETE ON control_audit BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_record_ids_update BEFORE UPDATE ON control_record_ids BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_record_ids_delete BEFORE DELETE ON control_record_ids BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_fleet_update BEFORE UPDATE ON control_fleet WHEN OLD.kind='RELEASE' OR NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.record_id<>OLD.record_id OR NEW.revision<>OLD.revision+1 BEGIN SELECT RAISE(ABORT,'immutable local version'); END;
-- statement
CREATE TRIGGER control_fleet_delete BEFORE DELETE ON control_fleet  BEGIN SELECT RAISE(ABORT,'local retention violation'); END;
-- statement
CREATE TRIGGER control_builder_update BEFORE UPDATE ON control_builder WHEN OLD.kind='VERSION' OR (OLD.kind='DRAFT' AND json_extract(OLD.document,'$.sealed')=1) OR NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.record_id<>OLD.record_id OR NEW.revision<>OLD.revision+1 BEGIN SELECT RAISE(ABORT,'immutable local version'); END;
-- statement
CREATE TRIGGER control_builder_delete BEFORE DELETE ON control_builder WHEN OLD.kind<>'TEST' OR json_extract(OLD.document,'$.expiresAtUnixMs')>=unixepoch()*1000-604800000 BEGIN SELECT RAISE(ABORT,'local retention violation'); END;
-- statement
CREATE TRIGGER control_policy_bundles_update BEFORE UPDATE ON control_policy_bundles BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_policy_bundles_delete BEFORE DELETE ON control_policy_bundles BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_epoch_guard BEFORE UPDATE ON control_tenant_authority WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.revision<OLD.revision OR NEW.authorization_epoch<OLD.authorization_epoch OR NEW.directory_cache_generation<OLD.directory_cache_generation BEGIN SELECT RAISE(ABORT,'authorization cannot move backwards'); END;
-- statement
CREATE TRIGGER control_credential_history_immutable_update BEFORE UPDATE ON control_credential_history BEGIN SELECT RAISE(ABORT,'immutable credential history'); END;
-- statement
CREATE TRIGGER control_credential_history_immutable_delete BEFORE DELETE ON control_credential_history BEGIN SELECT RAISE(ABORT,'immutable credential history'); END;
-- statement
CREATE TRIGGER control_reviewed_recovery_immutable_update BEFORE UPDATE ON control_reviewed_recovery BEGIN SELECT RAISE(ABORT,'immutable reviewed recovery'); END;
-- statement
CREATE TRIGGER control_reviewed_recovery_immutable_delete BEFORE DELETE ON control_reviewed_recovery BEGIN SELECT RAISE(ABORT,'immutable reviewed recovery'); END;
-- statement
CREATE TRIGGER control_invocation_v2_insert BEFORE INSERT ON control_enterprise_invocations WHEN NOT ((NEW.requires_execution_ledger IS NULL OR (NEW.tool_id IS NOT NULL AND NEW.package_digest IS NOT NULL AND NEW.effect_safety IS NOT NULL AND NEW.requires_execution_ledger=(NEW.effect_safety IN('NON_IDEMPOTENT','UNKNOWN'))))
 AND (NEW.task_id IS NULL)=(NEW.task_state IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_owner_digest IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_retain_until IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_document IS NULL) AND (NEW.task_id IS NULL OR NEW.requires_execution_ledger IS NOT NULL)
 AND (NEW.task_state IS NULL
  OR (NEW.state='PENDING_APPROVAL' AND NEW.task_state='AWAITING_APPROVAL')
  OR (NEW.state='QUEUED' AND NEW.task_state='QUEUED')
  OR (NEW.state IN('RESERVED','DISPATCHED') AND NEW.task_state IN('DISPATCHED','CANCEL_REQUESTED'))
  OR (NEW.state='EXECUTING' AND NEW.task_state IN('RUNNING','INPUT_REQUIRED','CANCEL_REQUESTED'))
  OR (NEW.state='SUCCEEDED' AND NEW.task_state='COMPLETED')
  OR (NEW.state IN('FAILED','PARTIAL') AND NEW.task_state='FAILED')
  OR (NEW.state='CANCELLED' AND NEW.task_state IN('CANCELLED','REJECTED'))
  OR (NEW.state='EXPIRED' AND NEW.task_state='EXPIRED')
  OR (NEW.state='OUTCOME_UNKNOWN' AND NEW.task_state='OUTCOME_UNKNOWN'))
 AND (NEW.lease_holder IS NULL)=(NEW.lease_expires_at IS NULL) AND (NEW.lease_holder IS NULL OR NEW.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED'))
 AND (NEW.task_state IS NOT 'CANCEL_REQUESTED' OR NEW.cancel_requested_at IS NOT NULL) AND (NEW.not_started=0 OR NEW.state='FAILED')) BEGIN SELECT RAISE(ABORT,'invalid v2 invocation state'); END;
-- statement
CREATE TRIGGER control_invocation_v2_update BEFORE UPDATE ON control_enterprise_invocations WHEN NOT ((NEW.requires_execution_ledger IS NULL OR (NEW.tool_id IS NOT NULL AND NEW.package_digest IS NOT NULL AND NEW.effect_safety IS NOT NULL AND NEW.requires_execution_ledger=(NEW.effect_safety IN('NON_IDEMPOTENT','UNKNOWN'))))
 AND (NEW.task_id IS NULL)=(NEW.task_state IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_owner_digest IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_retain_until IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_document IS NULL) AND (NEW.task_id IS NULL OR NEW.requires_execution_ledger IS NOT NULL)
 AND (NEW.task_state IS NULL
  OR (NEW.state='PENDING_APPROVAL' AND NEW.task_state='AWAITING_APPROVAL')
  OR (NEW.state='QUEUED' AND NEW.task_state='QUEUED')
  OR (NEW.state IN('RESERVED','DISPATCHED') AND NEW.task_state IN('DISPATCHED','CANCEL_REQUESTED'))
  OR (NEW.state='EXECUTING' AND NEW.task_state IN('RUNNING','INPUT_REQUIRED','CANCEL_REQUESTED'))
  OR (NEW.state='SUCCEEDED' AND NEW.task_state='COMPLETED')
  OR (NEW.state IN('FAILED','PARTIAL') AND NEW.task_state='FAILED')
  OR (NEW.state='CANCELLED' AND NEW.task_state IN('CANCELLED','REJECTED'))
  OR (NEW.state='EXPIRED' AND NEW.task_state='EXPIRED')
  OR (NEW.state='OUTCOME_UNKNOWN' AND NEW.task_state='OUTCOME_UNKNOWN'))
 AND (NEW.lease_holder IS NULL)=(NEW.lease_expires_at IS NULL) AND (NEW.lease_holder IS NULL OR NEW.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED'))
 AND (NEW.task_state IS NOT 'CANCEL_REQUESTED' OR NEW.cancel_requested_at IS NOT NULL) AND (NEW.not_started=0 OR NEW.state='FAILED')) BEGIN SELECT RAISE(ABORT,'invalid v2 invocation state'); END;
-- statement
CREATE TRIGGER control_invocation_result_delete BEFORE DELETE ON control_invocation_results BEGIN SELECT RAISE(ABORT,'invocation results are purged, never deleted'); END;
-- statement
CREATE TRIGGER control_invocation_result_update BEFORE UPDATE ON control_invocation_results WHEN OLD.purged_at IS NOT NULL OR NEW.payload IS NOT NULL OR NEW.purged_at IS NULL OR NEW.tenant_id<>OLD.tenant_id OR NEW.invocation_id<>OLD.invocation_id OR NEW.fencing_nonce<>OLD.fencing_nonce OR NEW.result_digest<>OLD.result_digest OR NEW.byte_length<>OLD.byte_length OR NEW.created_at<>OLD.created_at OR NEW.retain_until<>OLD.retain_until BEGIN SELECT RAISE(ABORT,'invocation result is immutable except for its purge'); END;
-- statement
CREATE TRIGGER control_business_key_delete BEFORE DELETE ON control_business_keys BEGIN SELECT RAISE(ABORT,'business keys are tombstoned, never deleted'); END;
-- statement
CREATE TRIGGER control_business_key_update BEFORE UPDATE ON control_business_keys WHEN OLD.tombstoned_at IS NOT NULL OR NEW.source<>OLD.source
 OR (OLD.source='DEDUPE_WINDOW' AND NEW.invocation_id<>OLD.invocation_id AND OLD.dedupe_expires_at>NEW.created_at)
 OR (OLD.source<>'DEDUPE_WINDOW' AND (NEW.fingerprint<>OLD.fingerprint OR NEW.caller_agent_id<>OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id
   OR ((NEW.invocation_id<>OLD.invocation_id OR NEW.created_at<>OLD.created_at) AND NOT (COALESCE(OLD.outcome,'') IN('EXPIRED','FAILED') AND NEW.outcome IS NULL))
   OR (NEW.invocation_id=OLD.invocation_id AND OLD.outcome IS NOT NULL AND NEW.outcome IS NOT OLD.outcome AND NOT (OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED')))))
 BEGIN SELECT RAISE(ABORT,'business key reservation is immutable'); END;
-- statement
CREATE TRIGGER control_bootstrap_admin_update BEFORE UPDATE ON control_bootstrap_admins
BEGIN SELECT RAISE(ABORT,'setup administrator is immutable'); END;
-- statement
CREATE TRIGGER control_bootstrap_admin_delete BEFORE DELETE ON control_bootstrap_admins
BEGIN SELECT RAISE(ABORT,'setup administrator is immutable'); END;
-- statement
CREATE TRIGGER control_records_cache_insert AFTER INSERT ON control_records BEGIN
 UPDATE control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant authority missing') END;
END;
-- statement
CREATE TRIGGER control_records_cache_update AFTER UPDATE ON control_records BEGIN
 UPDATE control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant authority missing') END;

END;
-- statement
CREATE TRIGGER control_records_cache_delete AFTER DELETE ON control_records BEGIN
 UPDATE control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant authority missing') END;
END;
-- statement
CREATE TRIGGER control_settings_cache_insert AFTER INSERT ON control_server_settings BEGIN
 UPDATE control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant authority missing') END;
END;
-- statement
CREATE TRIGGER control_settings_cache_update AFTER UPDATE ON control_server_settings BEGIN
 UPDATE control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant authority missing') END;

END;
-- statement
CREATE TRIGGER control_settings_cache_delete AFTER DELETE ON control_server_settings BEGIN
 UPDATE control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant authority missing') END;
END;

-- statement
CREATE TRIGGER control_tenants_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenants WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_tenant_authority_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenant_authority WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_record_ids_tenant_immutable BEFORE UPDATE OF tenant_id ON control_record_ids WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_audit_tenant_immutable BEFORE UPDATE OF tenant_id ON control_audit WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_idempotency_tenant_immutable BEFORE UPDATE OF tenant_id ON control_idempotency WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_approval_clocks_tenant_immutable BEFORE UPDATE OF tenant_id ON control_approval_clocks WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_enrollments_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enrollments WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_fleet_tenant_immutable BEFORE UPDATE OF tenant_id ON control_fleet WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_builder_tenant_immutable BEFORE UPDATE OF tenant_id ON control_builder WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_endpoints_tenant_immutable BEFORE UPDATE OF tenant_id ON control_endpoints WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_endpoint_configurations_tenant_immutable BEFORE UPDATE OF tenant_id ON control_endpoint_configurations WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_mcp_requests_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_requests WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_policy_bundles_tenant_immutable BEFORE UPDATE OF tenant_id ON control_policy_bundles WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_records_tenant_immutable BEFORE UPDATE OF tenant_id ON control_records WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_authorization_outbox_tenant_immutable BEFORE UPDATE OF tenant_id ON control_authorization_outbox WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_enterprise_invocations_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_invocations WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_enterprise_approvals_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_approvals WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_enterprise_nonces_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_nonces WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_enterprise_adoptions_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_adoptions WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_credential_history_tenant_immutable BEFORE UPDATE OF tenant_id ON control_credential_history WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_reviewed_recovery_tenant_immutable BEFORE UPDATE OF tenant_id ON control_reviewed_recovery WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_configuration_changes_tenant_immutable BEFORE UPDATE OF tenant_id ON control_configuration_changes WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_secrets_tenant_immutable BEFORE UPDATE OF tenant_id ON control_secrets WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_queued_authority_tenant_immutable BEFORE UPDATE OF tenant_id ON control_queued_authority WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_execution_budgets_tenant_immutable BEFORE UPDATE OF tenant_id ON control_execution_budgets WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_outcome_reconciliations_tenant_immutable BEFORE UPDATE OF tenant_id ON control_outcome_reconciliations WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_recovery_floors_tenant_immutable BEFORE UPDATE OF tenant_id ON control_recovery_floors WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_server_settings_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_settings WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_business_keys_tenant_immutable BEFORE UPDATE OF tenant_id ON control_business_keys WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_invocation_results_tenant_immutable BEFORE UPDATE OF tenant_id ON control_invocation_results WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_bootstrap_admins_tenant_immutable BEFORE UPDATE OF tenant_id ON control_bootstrap_admins WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_server_packages_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_packages WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_server_documents_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_documents WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_server_deployments_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_deployments WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_tool_hosts_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tool_hosts WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_tool_host_dispatches_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tool_host_dispatches WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_mcp_approvals_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_approvals WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_mcp_scans_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_scans WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_mcp_servers_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_servers WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_mcp_snapshots_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_snapshots WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_console_devices_tenant_immutable BEFORE UPDATE OF tenant_id ON control_console_devices WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_invocation_event_streams_tenant_immutable BEFORE UPDATE OF tenant_id ON control_invocation_event_streams WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_invocation_events_tenant_immutable BEFORE UPDATE OF tenant_id ON control_invocation_events WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_package_provisioning_tenant_immutable BEFORE UPDATE OF tenant_id ON control_package_provisioning WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
CREATE TRIGGER control_tenants_no_delete BEFORE DELETE ON control_tenants BEGIN SELECT RAISE(ABORT,'tenant retention requires explicit offboarding'); END;
-- statement
CREATE TRIGGER control_tenants_lifecycle BEFORE UPDATE ON control_tenants WHEN NEW.created_at<>OLD.created_at OR NEW.updated_at<OLD.updated_at OR (NEW.status<>OLD.status AND NOT ((OLD.status='PROVISIONING' AND NEW.status='ACTIVE') OR (OLD.status='ACTIVE' AND NEW.status='SUSPENDED') OR (OLD.status='SUSPENDED' AND NEW.status='ACTIVE'))) BEGIN SELECT RAISE(ABORT,'invalid tenant lifecycle transition'); END;

-- statement
-- BEGIN TENANT CAPACITY
CREATE TABLE control_tenant_capacity(
 tenant_id TEXT NOT NULL PRIMARY KEY REFERENCES control_tenants(tenant_id),
 stored_bytes INTEGER NOT NULL DEFAULT 0 CHECK(stored_bytes>=0),
 stored_rows INTEGER NOT NULL DEFAULT 0 CHECK(stored_rows>=0),
 queued_rows INTEGER NOT NULL DEFAULT 0 CHECK(queued_rows>=0),
 max_stored_bytes INTEGER NOT NULL DEFAULT 1073741824 CHECK(max_stored_bytes BETWEEN 4096 AND 1099511627776),
 max_stored_rows INTEGER NOT NULL DEFAULT 100000 CHECK(max_stored_rows BETWEEN 1 AND 1000000),
 max_queued_rows INTEGER NOT NULL DEFAULT 256 CHECK(max_queued_rows BETWEEN 1 AND 100000),
 CHECK(stored_rows<=max_stored_rows AND queued_rows<=max_queued_rows AND stored_bytes+queued_rows*16777216<=max_stored_bytes));
-- statement
CREATE TRIGGER control_capacity_seed AFTER INSERT ON control_tenants BEGIN INSERT INTO control_tenant_capacity(tenant_id) VALUES(NEW.tenant_id); END;
-- statement
CREATE TRIGGER control_builder_capacity_insert AFTER INSERT ON control_builder BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(CASE WHEN NEW.kind='TEST' AND json_extract(NEW.document,'$.state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_builder_capacity_delete AFTER DELETE ON control_builder BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(CASE WHEN OLD.kind='TEST' AND json_extract(OLD.document,'$.state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_builder_capacity_update AFTER UPDATE ON control_builder BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((CASE WHEN NEW.kind='TEST' AND json_extract(NEW.document,'$.state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END)-(CASE WHEN OLD.kind='TEST' AND json_extract(OLD.document,'$.state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_requests_capacity_insert AFTER INSERT ON control_mcp_requests BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.record AS BLOB)),0)+COALESCE(length(CAST(NEW.task AS BLOB)),0)+COALESCE(length(CAST(NEW.result AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(CASE WHEN NEW.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_requests_capacity_delete AFTER DELETE ON control_mcp_requests BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.record AS BLOB)),0)+COALESCE(length(CAST(OLD.task AS BLOB)),0)+COALESCE(length(CAST(OLD.result AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(CASE WHEN OLD.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_requests_capacity_update AFTER UPDATE ON control_mcp_requests BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.record AS BLOB)),0)+COALESCE(length(CAST(NEW.task AS BLOB)),0)+COALESCE(length(CAST(NEW.result AS BLOB)),0))-(COALESCE(length(CAST(OLD.record AS BLOB)),0)+COALESCE(length(CAST(OLD.task AS BLOB)),0)+COALESCE(length(CAST(OLD.result AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((CASE WHEN NEW.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END)-(CASE WHEN OLD.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_enterprise_invocations_capacity_insert AFTER INSERT ON control_enterprise_invocations BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(CASE WHEN NEW.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_enterprise_invocations_capacity_delete AFTER DELETE ON control_enterprise_invocations BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(CASE WHEN OLD.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_enterprise_invocations_capacity_update AFTER UPDATE ON control_enterprise_invocations BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((CASE WHEN NEW.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END)-(CASE WHEN OLD.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_tool_host_dispatches_capacity_insert AFTER INSERT ON control_tool_host_dispatches BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_tool_host_dispatches_capacity_delete AFTER DELETE ON control_tool_host_dispatches BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_tool_host_dispatches_capacity_update AFTER UPDATE ON control_tool_host_dispatches BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END)-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_scans_capacity_insert AFTER INSERT ON control_mcp_scans BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_scans_capacity_delete AFTER DELETE ON control_mcp_scans BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_scans_capacity_update AFTER UPDATE ON control_mcp_scans BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END)-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_servers_capacity_insert AFTER INSERT ON control_mcp_servers BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_servers_capacity_delete AFTER DELETE ON control_mcp_servers BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_servers_capacity_update AFTER UPDATE ON control_mcp_servers BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_snapshots_capacity_insert AFTER INSERT ON control_mcp_snapshots BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_snapshots_capacity_delete AFTER DELETE ON control_mcp_snapshots BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_snapshots_capacity_update AFTER UPDATE ON control_mcp_snapshots BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_approvals_capacity_insert AFTER INSERT ON control_mcp_approvals BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_approvals_capacity_delete AFTER DELETE ON control_mcp_approvals BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_mcp_approvals_capacity_update AFTER UPDATE ON control_mcp_approvals BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_server_packages_capacity_insert AFTER INSERT ON control_server_packages BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.content AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_server_packages_capacity_delete AFTER DELETE ON control_server_packages BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.content AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_server_packages_capacity_update AFTER UPDATE ON control_server_packages BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.content AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.content AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_server_documents_capacity_insert AFTER INSERT ON control_server_documents BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_server_documents_capacity_delete AFTER DELETE ON control_server_documents BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_server_documents_capacity_update AFTER UPDATE ON control_server_documents BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_secrets_capacity_insert AFTER INSERT ON control_secrets BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.cipher AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_secrets_capacity_delete AFTER DELETE ON control_secrets BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.cipher AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_secrets_capacity_update AFTER UPDATE ON control_secrets BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.cipher AS BLOB)),0))-(COALESCE(length(CAST(OLD.cipher AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_events_capacity_insert AFTER INSERT ON control_invocation_events BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_events_capacity_delete AFTER DELETE ON control_invocation_events BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_events_capacity_update AFTER UPDATE ON control_invocation_events BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_results_capacity_insert AFTER INSERT ON control_invocation_results BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.payload AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_results_capacity_delete AFTER DELETE ON control_invocation_results BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.payload AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_results_capacity_update AFTER UPDATE ON control_invocation_results BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.payload AS BLOB)),0))-(COALESCE(length(CAST(OLD.payload AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_policy_bundles_capacity_insert AFTER INSERT ON control_policy_bundles BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.policy AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_policy_bundles_capacity_delete AFTER DELETE ON control_policy_bundles BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.policy AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_policy_bundles_capacity_update AFTER UPDATE ON control_policy_bundles BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.policy AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.policy AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((0)-(0)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- END TENANT CAPACITY

-- statement
CREATE TRIGGER control_operation_cancellations_immutable_update BEFORE UPDATE ON control_operation_cancellations BEGIN SELECT RAISE(ABORT,'immutable cancellation'); END;
-- statement
CREATE TRIGGER control_operation_cancellations_immutable_delete BEFORE DELETE ON control_operation_cancellations BEGIN SELECT RAISE(ABORT,'immutable cancellation'); END;
