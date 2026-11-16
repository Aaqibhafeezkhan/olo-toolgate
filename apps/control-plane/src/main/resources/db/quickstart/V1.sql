-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
-- Fresh breaking-build schema. Existing stage databases are unsupported; no upgrades or backfills.
CREATE TABLE control_approval_clocks(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,observed_at INTEGER NOT NULL CHECK(observed_at BETWEEN 0 AND 9007199254740991));
-- statement
CREATE TABLE control_attempt_effects (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL, attempt_id TEXT NOT NULL, effect_id TEXT NOT NULL,
 PRIMARY KEY(tenant_id,operation_id,attempt_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,effect_id) REFERENCES control_effects(tenant_id,operation_id,effect_id)
);
-- statement
CREATE TABLE control_attempt_manifest_seals (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL, attempt_id TEXT NOT NULL,
 invocation_revision BIGINT NOT NULL CHECK(invocation_revision>=1),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 effect_count BIGINT NOT NULL CHECK(effect_count BETWEEN 0 AND 4096),
 manifest_digest TEXT NOT NULL CHECK(length(manifest_digest)=64 AND manifest_digest NOT GLOB '*[^a-f0-9]*'),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_valid(document) AND json_type(document)='object'),
 PRIMARY KEY(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id)
);
-- statement
CREATE TABLE control_attempt_recovery_scopes (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,
 attempt_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 pool_id TEXT NOT NULL,
 image_digest TEXT NOT NULL CHECK(length(image_digest)=71),
 runtime_digest TEXT NOT NULL CHECK(length(runtime_digest)=71),
 descriptor_digest TEXT NOT NULL CHECK(length(descriptor_digest)=71),
 profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71),
 profile_revision BIGINT NOT NULL CHECK(profile_revision BETWEEN 1 AND 9007199254740991),
 PRIMARY KEY(tenant_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id)
);
-- statement
CREATE TABLE control_attempt_reviews(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),operation_id TEXT NOT NULL,attempt_id TEXT NOT NULL,
 reconciliation_id TEXT NOT NULL,expected_outcome_revision BIGINT NOT NULL CHECK(expected_outcome_revision>=1),
 recorded_at BIGINT NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 source_digest TEXT NOT NULL CHECK(length(source_digest)=64 AND source_digest NOT GLOB '*[^a-f0-9]*'),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_valid(document) AND json_type(document)='object'),
 PRIMARY KEY(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_attempt_manifest_seals(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,expected_outcome_revision) REFERENCES control_operation_outcomes(tenant_id,operation_id,revision),
 FOREIGN KEY(tenant_id,reconciliation_id) REFERENCES control_outcome_reconciliations(tenant_id,reconciliation_id)
);
-- statement
CREATE TABLE control_attempt_settlements (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id), operation_id TEXT NOT NULL, attempt_id TEXT NOT NULL,
 proof TEXT NOT NULL CHECK(proof IN('NEVER_CONSUMED','QUALIFIED_BROKER_ONLY')),
 invocation_revision BIGINT NOT NULL CHECK(invocation_revision>=1),
 recorded_at BIGINT NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 source_digest TEXT NOT NULL CHECK(length(source_digest)=64 AND source_digest NOT GLOB '*[^a-f0-9]*'), document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_valid(document) AND json_type(document)='object'),
 PRIMARY KEY(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_attempt_manifest_seals(tenant_id,operation_id,attempt_id)
);
-- statement
CREATE TABLE control_attempt_snapshots (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,
 attempt_id TEXT NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>=1),
 state TEXT NOT NULL CHECK(state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING','SUCCEEDED','PARTIAL','FAILED','OUTCOME_UNKNOWN','CANCELLED','EXPIRED')),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=5242880 AND json_valid(document)
 AND COALESCE(json_extract(document,'$.id'),'')=operation_id
 AND COALESCE(json_extract(document,'$.reservedNonce'),'')=attempt_id
 AND COALESCE(json_extract(document,'$.state'),'')=state),
 PRIMARY KEY(tenant_id,attempt_id,revision),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id)
);
-- statement
CREATE TABLE control_audit(sequence INTEGER PRIMARY KEY AUTOINCREMENT,tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,chain_index INTEGER NOT NULL CHECK(chain_index>=1),previous_digest TEXT NOT NULL CHECK(length(previous_digest)=64 AND previous_digest NOT GLOB '*[^0-9a-f]*' AND (chain_index>1 OR previous_digest='0000000000000000000000000000000000000000000000000000000000000000')),actor_id TEXT NOT NULL,operation TEXT NOT NULL,target TEXT NOT NULL,revision INTEGER NOT NULL,request_id TEXT NOT NULL,request_digest TEXT NOT NULL,evidence TEXT CHECK(evidence IS NULL OR length(CAST(evidence AS BLOB))<=4096),occurred_at TEXT NOT NULL,row_digest TEXT NOT NULL CHECK(length(row_digest)=64 AND row_digest NOT GLOB '*[^0-9a-f]*'),UNIQUE(tenant_id,chain_index));
-- statement
CREATE TABLE control_audit_anchors(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,chain_index INTEGER NOT NULL CHECK(chain_index>=1),head_digest TEXT NOT NULL CHECK(length(head_digest)=64 AND head_digest NOT GLOB '*[^0-9a-f]*'),key_id TEXT NOT NULL CHECK(length(key_id) BETWEEN 1 AND 128),issued_at_unix_ms INTEGER NOT NULL CHECK(issued_at_unix_ms BETWEEN 0 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1024),signature TEXT NOT NULL CHECK(length(signature) BETWEEN 1 AND 1024),sink TEXT NOT NULL CHECK(length(sink) BETWEEN 1 AND 64),location TEXT NOT NULL CHECK(length(location) BETWEEN 1 AND 512),content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^0-9a-f]*'),recorded_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')),PRIMARY KEY(tenant_id,chain_index));
-- statement
CREATE TABLE control_authorization_outbox(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,revision bigint NOT NULL,authorization_epoch bigint NOT NULL,occurred_at bigint NOT NULL,published_at bigint,PRIMARY KEY(tenant_id,revision));
-- statement
CREATE TABLE control_bootstrap_admins(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,user_id TEXT NOT NULL);
-- statement
CREATE TABLE control_builder(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL CHECK(kind IN('DRAFT','VERSION','TEST')),record_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=65536),PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_business_key_history (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 tool_id TEXT NOT NULL,
 namespace TEXT NOT NULL CHECK(namespace IN('AGENT','DELEGATED_USER','TENANT')),
 namespace_identity TEXT NOT NULL CHECK(typeof(namespace_identity)='text'),
 business_key_hash TEXT NOT NULL CHECK(length(business_key_hash)=64),
 invocation_id TEXT NOT NULL,
 fingerprint TEXT NOT NULL CHECK(length(fingerprint)=71),
 caller_agent_id TEXT NOT NULL,
 caller_user_id TEXT,
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 dedupe_expires_at BIGINT NOT NULL CHECK(dedupe_expires_at>created_at),
 retain_until BIGINT NOT NULL CHECK(retain_until>=dedupe_expires_at),
 outcome TEXT NOT NULL CHECK(outcome IN('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED')),
 tombstoned_at BIGINT,
 archived_at BIGINT NOT NULL CHECK(archived_at>=dedupe_expires_at AND archived_at<=9007199254740991),
 PRIMARY KEY(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,invocation_id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_operations(tenant_id,operation_id),
 CHECK((namespace='TENANT')=(length(CAST(namespace_identity AS BLOB))=0))
);
-- statement
CREATE TABLE control_business_keys(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,tool_id TEXT NOT NULL CHECK(length(tool_id) BETWEEN 1 AND 256),namespace TEXT NOT NULL CHECK(namespace IN('AGENT','DELEGATED_USER','TENANT')),namespace_identity TEXT NOT NULL CHECK(typeof(namespace_identity)='text' AND length(namespace_identity)<=128),business_key_hash TEXT NOT NULL CHECK(length(business_key_hash)=64 AND business_key_hash NOT GLOB '*[^0-9a-f]*'),source TEXT NOT NULL CHECK(source IN('HEADER','ARGUMENT','DEDUPE_WINDOW')),fingerprint TEXT NOT NULL CHECK(length(fingerprint)=71 AND substr(fingerprint,1,7)='sha256:' AND substr(fingerprint,8) NOT GLOB '*[^0-9a-f]*'),caller_agent_id TEXT NOT NULL,caller_user_id TEXT,invocation_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),dedupe_expires_at INTEGER CHECK(dedupe_expires_at BETWEEN 0 AND 9007199254740991),retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),outcome TEXT CHECK(outcome IN('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED','OUTCOME_UNKNOWN','PARTIAL')),tombstoned_at INTEGER CHECK(tombstoned_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,tool_id,namespace,namespace_identity,business_key_hash),CHECK((namespace='TENANT')=(length(CAST(namespace_identity AS BLOB))=0)),CHECK((source='DEDUPE_WINDOW')=(dedupe_expires_at IS NOT NULL)),CHECK(dedupe_expires_at IS NULL OR dedupe_expires_at>created_at),CHECK(retain_until>=created_at),CHECK(tombstoned_at IS NULL OR outcome IS NOT NULL));
-- statement
CREATE TABLE control_configuration_changes(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,change_id varchar(128) NOT NULL,revision bigint NOT NULL,request_digest TEXT NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,change_id));
-- statement
CREATE TABLE control_console_devices (
 tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL, session_id TEXT NOT NULL, user_id TEXT NOT NULL,
 device_id TEXT NOT NULL, nonce_digest TEXT NOT NULL, code_digest TEXT NOT NULL,
 csr TEXT NOT NULL, fingerprint TEXT NOT NULL,
 expires_at INTEGER NOT NULL, verified_at INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,session_id)
);
-- statement
CREATE TABLE control_credential_history(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,credential_sha256 TEXT NOT NULL CHECK(length(credential_sha256)=64),PRIMARY KEY(tenant_id,credential_sha256));
-- statement
CREATE TABLE control_effect_consent_approvals(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,reviewer TEXT NOT NULL,
 authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
 approved_at INTEGER NOT NULL CHECK(approved_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
-- statement
CREATE TABLE control_effect_consent_revocations(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,reviewer TEXT NOT NULL,
 revoked_at INTEGER NOT NULL CHECK(revoked_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
-- statement
CREATE TABLE control_effect_consent_slots(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,slot_index INTEGER NOT NULL CHECK(slot_index BETWEEN 0 AND 63),
 route_id TEXT NOT NULL,mapped_digest TEXT NOT NULL CHECK(length(mapped_digest)=71 AND substr(mapped_digest,1,7)='sha256:' AND substr(mapped_digest,8) NOT GLOB '*[^a-f0-9]*'),permitted_count INTEGER NOT NULL CHECK(permitted_count BETWEEN 1 AND 64),
 PRIMARY KEY(tenant_id,consent_id,slot_index),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
-- statement
CREATE TABLE control_effect_consent_uses(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,slot_index INTEGER NOT NULL,effect_id TEXT NOT NULL,
 request_digest TEXT NOT NULL CHECK(length(request_digest)=71 AND substr(request_digest,1,7)='sha256:' AND substr(request_digest,8) NOT GLOB '*[^a-f0-9]*'),reserved_at INTEGER NOT NULL CHECK(reserved_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,effect_id),FOREIGN KEY(tenant_id,consent_id,slot_index) REFERENCES control_effect_consent_slots(tenant_id,consent_id,slot_index),
 FOREIGN KEY(tenant_id,effect_id) REFERENCES control_effects(tenant_id,effect_id)
);
-- statement
CREATE TABLE control_effect_consents(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,operation_id TEXT NOT NULL,
 preview_digest TEXT NOT NULL CHECK(length(preview_digest)=71 AND substr(preview_digest,1,7)='sha256:' AND substr(preview_digest,8) NOT GLOB '*[^a-f0-9]*'),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=2097152 AND json_valid(document) AND json_type(document)='object'),
 not_before INTEGER NOT NULL CHECK(not_before BETWEEN 0 AND 9007199254740991),
 expires_at INTEGER NOT NULL CHECK(expires_at>not_before AND expires_at<=9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id)
);
-- statement
CREATE TABLE control_effect_evidence(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id), effect_id TEXT NOT NULL,
 generation BIGINT NOT NULL, evidence_digest TEXT NOT NULL, source TEXT NOT NULL,
 outcome TEXT NOT NULL CHECK(outcome IN('COMPLETED','FAILED')), result_digest TEXT NOT NULL,
 recorded_at BIGINT NOT NULL CHECK(recorded_at>=0),
 PRIMARY KEY(tenant_id,effect_id,generation,evidence_digest),
 FOREIGN KEY(tenant_id,effect_id,generation) REFERENCES control_effect_generations(tenant_id,effect_id,generation)
);
-- statement
CREATE TABLE control_effect_generations(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 effect_id TEXT NOT NULL, generation BIGINT NOT NULL CHECK(generation>=1),
 operation_id TEXT NOT NULL, attempt_id TEXT NOT NULL,
 auth_nonce TEXT NOT NULL, device_id TEXT NOT NULL, broker_id TEXT NOT NULL, handler_id TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('AUTHORIZED','RELEASED','SENDING','CONSUMED','COMPLETED','FAILED','AMBIGUOUS','CONFLICT')),
 issued_at BIGINT NOT NULL CHECK(issued_at>=0), expires_at BIGINT NOT NULL CHECK(expires_at>issued_at),
 outcome_deadline BIGINT NOT NULL CHECK(outcome_deadline>=expires_at), revision BIGINT NOT NULL CHECK(revision>=1),
 consumer TEXT, claim_token TEXT,
 PRIMARY KEY(tenant_id,effect_id,generation), UNIQUE(tenant_id,auth_nonce),
 FOREIGN KEY(tenant_id,operation_id,effect_id) REFERENCES control_effects(tenant_id,operation_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK((consumer IS NULL)=(claim_token IS NULL)),
 CHECK(state<>'CONSUMED' OR consumer IS NOT NULL)
);
-- statement
CREATE TABLE control_effect_intents(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),effect_id TEXT NOT NULL,
 request_digest TEXT NOT NULL CHECK(length(request_digest)=71 AND substr(request_digest,1,7)='sha256:' AND substr(request_digest,8) NOT GLOB '*[^a-f0-9]*'),
 arguments_digest TEXT NOT NULL CHECK(length(arguments_digest)=71 AND substr(arguments_digest,1,7)='sha256:' AND substr(arguments_digest,8) NOT GLOB '*[^a-f0-9]*'),
 profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71 AND substr(profile_digest,1,7)='sha256:' AND substr(profile_digest,8) NOT GLOB '*[^a-f0-9]*'),
 classification TEXT NOT NULL CHECK(classification IN('READ','WRITE','DESTRUCTIVE','FINANCIAL')),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_valid(document) AND json_type(document)='object'),
 fenced_target_class TEXT CHECK(fenced_target_class IS NULL OR fenced_target_class IN('SHARED_SQL','SQL_WITH_EXTERNAL_ACTION')),
 authoritative_response INTEGER NOT NULL CHECK(authoritative_response IN(0,1)),
 recorded_at INTEGER NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,effect_id),
 FOREIGN KEY(tenant_id,effect_id) REFERENCES control_effects(tenant_id,effect_id)
);
-- statement
CREATE TABLE control_effect_target_bindings(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 effect_id varchar(128) NOT NULL,
 operation_id varchar(128) NOT NULL,
 route_id varchar(128) NOT NULL,
 profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71),
 guarantee_digest TEXT NOT NULL CHECK(length(guarantee_digest)=71),
 header TEXT NOT NULL CHECK(length(header) BETWEEN 1 AND 64),
 target_key TEXT NOT NULL CHECK(length(target_key)=67),
 credential_version BIGINT NOT NULL CHECK(credential_version BETWEEN 0 AND 9007199254740991),
 first_authorized_at BIGINT NOT NULL CHECK(first_authorized_at BETWEEN 0 AND 9007199254740991),
 valid_until BIGINT NOT NULL CHECK(valid_until>first_authorized_at AND valid_until<=9007199254740991),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=8192 AND json_valid(document)),
 PRIMARY KEY(tenant_id,effect_id),
 FOREIGN KEY(tenant_id,effect_id) REFERENCES control_effects(tenant_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
-- statement
CREATE TABLE control_effects(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 effect_id TEXT NOT NULL, operation_id TEXT NOT NULL, route_id TEXT NOT NULL,
 request_digest TEXT NOT NULL, current_generation BIGINT NOT NULL CHECK(current_generation>=1),
 PRIMARY KEY(tenant_id,effect_id), UNIQUE(tenant_id,operation_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
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
CREATE TABLE "control_endpoints"(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,device_id TEXT NOT NULL,key_fingerprint TEXT NOT NULL,document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_extract(document,'$.deviceId')=device_id AND json_extract(document,'$.tenantId')=tenant_id),csr TEXT NOT NULL CHECK(length(CAST(csr AS BLOB))<=16384),report_digest TEXT NOT NULL,acknowledgment TEXT NOT NULL CHECK(length(CAST(acknowledgment AS BLOB))<=131072),PRIMARY KEY(tenant_id,device_id),UNIQUE(tenant_id,key_fingerprint));
-- statement
CREATE TABLE control_enrollments(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,enrollment_id TEXT NOT NULL,code_digest TEXT NOT NULL,device_digest TEXT NOT NULL,document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=16384),csr TEXT NOT NULL CHECK(length(CAST(csr AS BLOB))<=16384),user_id TEXT,certificate TEXT CHECK(length(CAST(certificate AS BLOB))<=32768),expires_at INTEGER NOT NULL CHECK(expires_at>=0),last_poll INTEGER NOT NULL DEFAULT 0 CHECK(last_poll>=0),PRIMARY KEY(tenant_id,enrollment_id),UNIQUE(tenant_id,code_digest));
-- statement
CREATE TABLE control_enterprise_adoptions(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,actor_type varchar(16) NOT NULL,actor_id varchar(128) NOT NULL,directory_revision bigint NOT NULL,snapshot_sequence bigint NOT NULL,graph_digest TEXT NOT NULL,observed_at bigint NOT NULL, authorization_epoch bigint NOT NULL DEFAULT 0,PRIMARY KEY(tenant_id,actor_type,actor_id));
-- statement
CREATE TABLE control_enterprise_approvals(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,approval_id varchar(128) NOT NULL,approval_type varchar(16) NOT NULL CHECK(approval_type IN('OPERATION','CONFIGURATION')),invocation_id varchar(128) NOT NULL,request_digest TEXT NOT NULL,authorization_epoch bigint NOT NULL,expires_at bigint NOT NULL,revision bigint NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,approval_id));
-- statement
CREATE TABLE control_enterprise_invocations(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id varchar(128) NOT NULL,request_digest TEXT NOT NULL,state varchar(32) NOT NULL,authorization_epoch bigint NOT NULL,created_at bigint NOT NULL,expires_at bigint NOT NULL,revision bigint NOT NULL,document text NOT NULL, tool_id TEXT CHECK(length(tool_id) BETWEEN 1 AND 256), package_id TEXT CHECK(length(package_id) BETWEEN 1 AND 128), package_digest TEXT CHECK(length(package_digest)=71 AND substr(package_digest,1,7)='sha256:' AND substr(package_digest,8) NOT GLOB '*[^0-9a-f]*'), pool_id TEXT CHECK(length(pool_id) BETWEEN 1 AND 128), runtime_digest TEXT CHECK(length(runtime_digest)=71 AND substr(runtime_digest,1,7)='sha256:' AND substr(runtime_digest,8) NOT GLOB '*[^0-9a-f]*'), effect_safety TEXT CHECK(effect_safety IN('READ_ONLY','IDEMPOTENT','NON_IDEMPOTENT','UNKNOWN')), requires_execution_ledger INTEGER CHECK(requires_execution_ledger IN(0,1)), lease_holder TEXT CHECK(length(lease_holder) BETWEEN 1 AND 256), lease_expires_at INTEGER CHECK(lease_expires_at BETWEEN 0 AND 9007199254740991), cancel_requested_at INTEGER CHECK(cancel_requested_at BETWEEN 0 AND 9007199254740991), not_started INTEGER NOT NULL DEFAULT 0 CHECK(not_started IN(0,1)), task_id TEXT CHECK(length(task_id) BETWEEN 22 AND 64 AND task_id NOT GLOB '*[^A-Za-z0-9_-]*'), task_state TEXT, task_owner_digest TEXT CHECK(length(task_owner_digest)=64 AND task_owner_digest NOT GLOB '*[^0-9a-f]*'), task_retain_until INTEGER CHECK(task_retain_until BETWEEN 0 AND 9007199254740991), task_input_deadline_at INTEGER CHECK(task_input_deadline_at BETWEEN 0 AND 9007199254740991), task_last_polled_at INTEGER CHECK(task_last_polled_at BETWEEN 0 AND 9007199254740991), task_document TEXT CHECK(length(CAST(task_document AS BLOB))<=32768), insights_fact_seq INTEGER NOT NULL DEFAULT 0 CHECK(insights_fact_seq>=0),PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_enterprise_nonces(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,nonce varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,evaluation_digest TEXT NOT NULL,authorization_epoch bigint NOT NULL,issued_at bigint NOT NULL,expires_at bigint NOT NULL,consumed_at bigint, purpose TEXT NOT NULL DEFAULT 'LEGACY' CHECK(purpose IN('LEGACY','EXECUTION','SERVICE')),PRIMARY KEY(tenant_id,nonce));
-- statement
CREATE TABLE control_execution_budgets(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,budget_id TEXT NOT NULL,invocation_id TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(tenant_id,budget_id,invocation_id));
-- statement
CREATE TABLE control_execution_evidence (
    tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
    invocation_id TEXT NOT NULL,
    fencing_token TEXT NOT NULL,
    report_digest TEXT NOT NULL CHECK(length(report_digest)=64 AND report_digest NOT GLOB '*[^a-f0-9]*'),
    document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=5242880),
    recorded_at INTEGER NOT NULL CHECK(recorded_at>=0), qualified_report INTEGER NOT NULL DEFAULT 0 CHECK(qualified_report IN(0,1)),
    PRIMARY KEY(tenant_id,invocation_id,fencing_token,report_digest),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id)
);
-- statement
CREATE TABLE control_execution_leases (
    tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
    invocation_id TEXT NOT NULL,
    fencing_token TEXT NOT NULL,
    device_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL CHECK(expires_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY(tenant_id,invocation_id),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id)
);
-- statement
CREATE TABLE control_fleet(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL CHECK(kind IN('RELEASE','DESIRED','ROLLOUT')),record_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=131072),PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_idempotency(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,actor_id TEXT NOT NULL,key TEXT NOT NULL,request_digest TEXT NOT NULL,status INTEGER NOT NULL,body TEXT NOT NULL,revision INTEGER NOT NULL,expires_at INTEGER NOT NULL DEFAULT(unixepoch()+86400),PRIMARY KEY(tenant_id,actor_id,key));
-- statement
CREATE TABLE control_insights_admissions(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,bucket_start INTEGER NOT NULL,tool_id TEXT NOT NULL,category TEXT NOT NULL,count INTEGER NOT NULL CHECK(count>=0),PRIMARY KEY(tenant_id,bucket_start,tool_id,category));
-- statement
CREATE TABLE control_insights_attempts(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,attempt_number INTEGER NOT NULL CHECK(attempt_number BETWEEN 1 AND 64),execution_id TEXT CHECK(length(execution_id)=35 AND substr(execution_id,1,3)='ex-' AND substr(execution_id,4) NOT GLOB '*[^0-9a-f]*'),executor_id TEXT,started_at INTEGER,ended_at INTEGER,outcome TEXT,duration_ms INTEGER,PRIMARY KEY(tenant_id,invocation_id,attempt_number));
-- statement
CREATE TABLE control_insights_cells(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,series TEXT NOT NULL CHECK(series IN('TOOL','CLIENT')),granularity TEXT NOT NULL CHECK(granularity IN('MINUTE','HOUR','DAY')),bucket_start INTEGER NOT NULL,dim TEXT NOT NULL,outcome TEXT NOT NULL,calls INTEGER NOT NULL CHECK(calls>=0),duration_count INTEGER NOT NULL CHECK(duration_count>=0),duration_sum INTEGER NOT NULL CHECK(duration_sum>=0),histogram TEXT NOT NULL CHECK(length(CAST(histogram AS BLOB))<=1024),PRIMARY KEY(tenant_id,series,granularity,bucket_start,dim,outcome));
-- statement
CREATE TABLE control_insights_checkpoints(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,last_fact_seq INTEGER NOT NULL CHECK(last_fact_seq>=0),contribution TEXT NOT NULL CHECK(length(CAST(contribution AS BLOB))<=8192),final_at INTEGER,PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_insights_facts(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,fact_seq INTEGER NOT NULL CHECK(fact_seq BETWEEN 1 AND 9007199254740991),revision INTEGER NOT NULL,from_state TEXT,to_state TEXT NOT NULL,occurred_at INTEGER NOT NULL CHECK(occurred_at BETWEEN 0 AND 9007199254740991),execution_id TEXT CHECK(length(execution_id)=35 AND substr(execution_id,1,3)='ex-' AND substr(execution_id,4) NOT GLOB '*[^0-9a-f]*'),detail TEXT NOT NULL CHECK(length(CAST(detail AS BLOB))<=8192),projected_at INTEGER,PRIMARY KEY(tenant_id,invocation_id,fact_seq));
-- statement
CREATE TABLE control_insights_gateway_epochs(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,gateway_id TEXT NOT NULL,epoch TEXT NOT NULL,last_sequence INTEGER NOT NULL CHECK(last_sequence>=0),missing INTEGER NOT NULL CHECK(missing>=0),dropped INTEGER NOT NULL CHECK(dropped>=0),final INTEGER NOT NULL CHECK(final IN(0,1)),last_seen INTEGER NOT NULL,PRIMARY KEY(tenant_id,gateway_id,epoch));
-- statement
CREATE TABLE control_insights_invocations(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,tool_id TEXT NOT NULL,action TEXT NOT NULL,client_id TEXT,user_id TEXT,agent_id TEXT,tool_call_id TEXT,session_id TEXT,agent_run_id TEXT,parent_invocation_id TEXT,outcome TEXT NOT NULL,error_category TEXT,attempts INTEGER NOT NULL CHECK(attempts BETWEEN 0 AND 64),accepted_at INTEGER NOT NULL,approval_requested_at INTEGER,approval_resolved_at INTEGER,queued_at INTEGER,first_started_at INTEGER,terminal_at INTEGER,approval_ms INTEGER,queue_ms INTEGER,execution_ms INTEGER,end_to_end_ms INTEGER,partial_coverage INTEGER NOT NULL CHECK(partial_coverage IN(0,1)),span_state TEXT CHECK(span_state IN('RETAINED','SAMPLED_OUT','DROPPED')),PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_insights_spans(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,span_id TEXT NOT NULL CHECK(length(span_id)=16 AND span_id NOT GLOB '*[^0-9a-f]*'),trace_id TEXT NOT NULL CHECK(length(trace_id)=32 AND trace_id NOT GLOB '*[^0-9a-f]*'),parent_span_id TEXT CHECK(length(parent_span_id)=16 AND parent_span_id NOT GLOB '*[^0-9a-f]*'),name TEXT NOT NULL,source TEXT NOT NULL CHECK(source IN('GATEWAY','TOOL_HOST','CLIENT')),execution_id TEXT CHECK(length(execution_id)=35 AND substr(execution_id,1,3)='ex-' AND substr(execution_id,4) NOT GLOB '*[^0-9a-f]*'),start_at INTEGER NOT NULL,duration_ms INTEGER NOT NULL CHECK(duration_ms BETWEEN 0 AND 86400000),status TEXT NOT NULL CHECK(status IN('OK','ERROR')),route_id TEXT,http_status INTEGER CHECK(http_status BETWEEN 100 AND 599),error_code TEXT,stored_at INTEGER NOT NULL,PRIMARY KEY(tenant_id,invocation_id,span_id));
-- statement
CREATE TABLE control_insights_tenant_state(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,collecting_since INTEGER NOT NULL,summaries_suspended_since INTEGER,summary_rows INTEGER NOT NULL DEFAULT 0 CHECK(summary_rows>=0),metrics_exported_through INTEGER,PRIMARY KEY(tenant_id));
-- statement
CREATE TABLE control_invocation_authority(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),invocation_id TEXT NOT NULL,expires_at bigint NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
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
CREATE TABLE control_invocation_results(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,fencing_nonce TEXT NOT NULL,result_digest TEXT NOT NULL CHECK(length(result_digest)=71 AND substr(result_digest,1,7)='sha256:' AND substr(result_digest,8) NOT GLOB '*[^0-9a-f]*'),byte_length INTEGER NOT NULL CHECK(byte_length BETWEEN 0 AND 4194304),payload BLOB,created_at INTEGER NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),purged_at INTEGER CHECK(purged_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,invocation_id),FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id),CHECK((payload IS NULL)=(purged_at IS NOT NULL)),CHECK(payload IS NULL OR length(payload)=byte_length),CHECK(retain_until>=created_at));
-- statement
CREATE TABLE control_mcp_approvals(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,slug TEXT NOT NULL,sequence INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,slug,sequence));
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
CREATE TABLE control_mcp_scans(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,scan_id TEXT NOT NULL,slug TEXT NOT NULL,host_id TEXT NOT NULL,state TEXT NOT NULL,
    deadline INTEGER NOT NULL,created_at INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,scan_id));
-- statement
CREATE TABLE control_mcp_servers(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,slug TEXT NOT NULL,revision INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,slug));
-- statement
CREATE TABLE control_mcp_snapshots(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,slug TEXT NOT NULL,revision INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,slug,revision));
-- statement
CREATE TABLE control_operation_attempts (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 attempt_id TEXT NOT NULL,
 operation_id TEXT NOT NULL,
 evaluation_digest TEXT NOT NULL,
 authorization_epoch BIGINT NOT NULL,
 issued_at BIGINT NOT NULL CHECK(issued_at>=0),
 expires_at BIGINT NOT NULL CHECK(expires_at>issued_at),
 PRIMARY KEY(tenant_id,attempt_id),
 UNIQUE(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES control_enterprise_nonces(tenant_id,nonce)
);
-- statement
CREATE TABLE control_operation_cancellations(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),invocation_id TEXT NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_operation_outcomes (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id), operation_id TEXT NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>=1), invocation_revision BIGINT NOT NULL CHECK(invocation_revision>=1),
 state TEXT NOT NULL CHECK(state IN('RUNNING','OUTCOME_UNKNOWN','RECONCILIATION_REQUIRED','SUCCEEDED','PARTIAL','FAILED')),
 source_digest TEXT NOT NULL CHECK(length(source_digest)=64 AND source_digest NOT GLOB '*[^a-f0-9]*'),
 recorded_at BIGINT NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_valid(document) AND json_type(document)='object'),
 PRIMARY KEY(tenant_id,operation_id,revision),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
-- statement
CREATE TABLE "control_operation_recoveries" (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,
 from_revision BIGINT NOT NULL CHECK(from_revision>=1),
 to_revision BIGINT NOT NULL CHECK(to_revision=from_revision+1),
 operation_revision BIGINT NOT NULL CHECK(operation_revision>=1),
 previous_nonce TEXT,
 original_deadline BIGINT NOT NULL,
 proof TEXT NOT NULL CHECK(proof IN('NEVER_CONSUMED','QUALIFIED_BROKER_NO_WRITE','TARGET_DEDUPLICATION')),
 attempt_ids TEXT NOT NULL CHECK(length(CAST(attempt_ids AS BLOB))<=32768 AND json_valid(attempt_ids) AND json_type(attempt_ids)='array'),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=33554432),
 PRIMARY KEY(tenant_id,operation_id,from_revision),
 UNIQUE(tenant_id,operation_id,to_revision),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id,previous_nonce) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK(original_deadline>created_at)
);
-- statement
CREATE TABLE control_operation_review_owners(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),operation_id TEXT NOT NULL,
 directory_revision BIGINT NOT NULL CHECK(directory_revision>=0),
 source_digest TEXT NOT NULL CHECK(length(source_digest)=64 AND source_digest NOT GLOB '*[^a-f0-9]*'),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=16384 AND json_valid(document) AND json_type(document)='object'),
 PRIMARY KEY(tenant_id,operation_id),FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
-- statement
CREATE TABLE control_operations (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,
 request_digest TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('RUNNING','OUTCOME_UNKNOWN','FAILED')),
 legacy INTEGER CHECK(legacy IN(0,1)) NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>=1),
 created_at BIGINT NOT NULL CHECK(created_at>=0),
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id)
);
-- statement
CREATE TABLE control_outcome_reconciliations(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,reconciliation_id varchar(128) NOT NULL,revision bigint NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,reconciliation_id));
-- statement
CREATE TABLE control_package_provisioning(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,profile_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71 AND substr(profile_digest,1,7)='sha256:'),
    document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=262144),PRIMARY KEY(tenant_id,profile_id));
-- statement
CREATE TABLE "control_policy_bundles"(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),directory_revision INTEGER NOT NULL,document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=2000100),policy TEXT NOT NULL CHECK(length(CAST(policy AS BLOB))<=1048576),published_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,sequence));
-- statement
CREATE TABLE control_queued_authority(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL,record_id TEXT NOT NULL,creator_user_id TEXT NOT NULL,PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_record_ids(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL,record_id TEXT NOT NULL,PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE "control_records"(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL CHECK(kind IN('USER','TEAM','AGENT','TOOL','POLICY','DEVICE','ROLE','DEVICE_GROUP','AGENT_GROUP','TOOL_GROUP','GRANT','DELEGATION','AGENT_DELEGATION','BINDING','EXTRACTOR','WORKLOAD_BINDING','IDENTITY_BINDING','DEVICE_EVIDENCE')),record_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(json_valid(document) AND json_extract(document,'$.id')=record_id AND json_extract(document,'$.revision')=revision),PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TABLE control_recovery_floors(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,snapshot_sequence bigint NOT NULL CHECK(snapshot_sequence>=0));
-- statement
CREATE TABLE control_reviewed_recovery(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,review_digest TEXT NOT NULL CHECK(length(review_digest)=64),PRIMARY KEY(tenant_id,review_digest));
-- statement
CREATE TABLE control_secrets(tenant_id varchar(128) REFERENCES control_tenants(tenant_id) NOT NULL,name varchar(128) NOT NULL,tool_group_id varchar(128) NOT NULL,device_group_id varchar(128) NOT NULL,version INTEGER NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),key_version INTEGER NOT NULL,cipher text NOT NULL CHECK(length(cipher)<=65536),PRIMARY KEY(tenant_id,name),FOREIGN KEY(tenant_id,key_version) REFERENCES control_tenant_vault_keys(tenant_id,version));
-- statement
CREATE TABLE control_server_deployments(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,package_id TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,package_id));
-- statement
CREATE TABLE control_server_documents(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,digest TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,digest));
-- statement
CREATE TABLE control_server_packages(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,package_digest TEXT NOT NULL,package_id TEXT NOT NULL,version TEXT NOT NULL,
    uploaded_at INTEGER NOT NULL,document TEXT NOT NULL,content BLOB NOT NULL,PRIMARY KEY(tenant_id,package_digest));
-- statement
CREATE TABLE control_server_settings(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL PRIMARY KEY,revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=16384));
-- statement
CREATE TABLE control_tenant_authority(tenant_id TEXT NOT NULL PRIMARY KEY REFERENCES control_tenants(tenant_id),revision INTEGER NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND 9007199254740991),authorization_epoch INTEGER NOT NULL DEFAULT 0 CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),directory_cache_generation INTEGER NOT NULL DEFAULT 0 CHECK(directory_cache_generation>=0));
-- statement
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
CREATE TABLE control_tenant_hosts(hostname TEXT NOT NULL PRIMARY KEY CHECK(hostname=lower(hostname) AND length(hostname) BETWEEN 1 AND 253),tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id));
-- statement
CREATE TABLE control_tenant_identity_keys(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),purpose TEXT NOT NULL CHECK(purpose IN ('DISCOVERY','DEVICE_CA')),key_id TEXT NOT NULL CHECK(length(key_id) BETWEEN 1 AND 128),state TEXT NOT NULL CHECK(state IN ('ACTIVE','READ_ONLY','RETIRED')),provider_reference TEXT NOT NULL CHECK(length(provider_reference)<=512),public_material TEXT NOT NULL CHECK(length(CAST(public_material AS BLOB))<=16384),key_fingerprint TEXT NOT NULL UNIQUE CHECK(length(key_fingerprint)=64 AND key_fingerprint NOT GLOB '*[^a-f0-9]*'),generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),PRIMARY KEY(tenant_id,purpose,key_id));
-- statement
CREATE TABLE control_tenant_initializations(tenant_id TEXT NOT NULL PRIMARY KEY REFERENCES control_tenants(tenant_id),version INTEGER NOT NULL CHECK(version=1),effective_digest TEXT NOT NULL CHECK(length(effective_digest)=64 AND effective_digest NOT GLOB '*[^a-f0-9]*'),provenance TEXT NOT NULL CHECK(length(CAST(provenance AS BLOB))<=65536),completed_at INTEGER NOT NULL CHECK(completed_at>=0));
-- statement
CREATE TABLE control_tenant_runtime_profiles(tenant_id TEXT NOT NULL PRIMARY KEY REFERENCES control_tenants(tenant_id),server_id TEXT NOT NULL CHECK(length(server_id) BETWEEN 1 AND 128),organization TEXT NOT NULL CHECK(length(organization) BETWEEN 1 AND 256),control_url TEXT NOT NULL CHECK(length(CAST(control_url AS BLOB))<=2048),gateway_url TEXT NOT NULL CHECK(length(CAST(gateway_url AS BLOB))<=2048));
-- statement
CREATE TABLE control_tenant_vault_keys(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),version INTEGER NOT NULL CHECK(version BETWEEN 1 AND 1024),state TEXT NOT NULL CHECK(state IN('ACTIVE','READ_ONLY','RETIRED')),provider_reference TEXT NOT NULL CHECK(length(provider_reference)<=128),wrapped_encryption_key TEXT NOT NULL CHECK(length(wrapped_encryption_key)<=1024),wrapped_retry_key TEXT NOT NULL CHECK(length(wrapped_retry_key)<=1024),PRIMARY KEY(tenant_id,version));
-- statement
CREATE TABLE control_tenants(tenant_id TEXT NOT NULL PRIMARY KEY CHECK(length(tenant_id)=36 AND tenant_id NOT GLOB '*[^0-9a-f-]*' AND substr(tenant_id,9,1)='-' AND substr(tenant_id,14,1)='-' AND substr(tenant_id,19,1)='-' AND substr(tenant_id,24,1)='-' AND length(replace(tenant_id,'-',''))=32),alias TEXT NOT NULL UNIQUE CHECK(length(alias) BETWEEN 1 AND 63 AND alias NOT GLOB '*[^a-z0-9-]*' AND substr(alias,1,1) GLOB '[a-z0-9]'),display_name TEXT NOT NULL CHECK(length(display_name) BETWEEN 1 AND 256),status TEXT NOT NULL CHECK(status IN('PROVISIONING','ACTIVE','SUSPENDED')),created_at INTEGER NOT NULL CHECK(created_at>=0),updated_at INTEGER NOT NULL CHECK(updated_at>=created_at),initialization_failure_code TEXT CHECK(initialization_failure_code='INITIALIZATION_FAILED'),initialization_failed_at INTEGER CHECK(initialization_failed_at>=0),CHECK((initialization_failure_code IS NULL)=(initialization_failed_at IS NULL)),CHECK(status='PROVISIONING' OR initialization_failure_code IS NULL));
-- statement
CREATE TABLE control_tool_host_dispatches(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,invocation_id TEXT NOT NULL,host_id TEXT NOT NULL,state TEXT NOT NULL,
    expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
-- statement
CREATE TABLE control_tool_hosts(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,device_id TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(tenant_id,device_id));
-- statement
CREATE INDEX control_audit_local_cursor ON control_audit(tenant_id,sequence);
-- statement
CREATE INDEX control_budget_window ON control_execution_budgets(tenant_id,budget_id,created_at);
-- statement
CREATE INDEX control_business_key_invocation ON control_business_keys(tenant_id,invocation_id);
-- statement
CREATE INDEX control_business_key_retention ON control_business_keys(tenant_id,retain_until) WHERE tombstoned_at IS NULL;
-- statement
CREATE INDEX control_console_devices_expiry ON control_console_devices(tenant_id,expires_at);
-- statement
CREATE INDEX control_effect_expiry ON control_effect_generations(tenant_id,state,expires_at,outcome_deadline);
-- statement
CREATE INDEX control_enrollments_local_cursor ON control_enrollments(tenant_id,expires_at);
-- statement
CREATE INDEX control_enterprise_invocation_window ON control_enterprise_invocations(tenant_id,created_at);
-- statement
CREATE INDEX control_idempotency_local_cursor ON control_idempotency(tenant_id,expires_at);
-- statement
CREATE INDEX control_insights_attempts_execution ON control_insights_attempts(tenant_id,execution_id) WHERE execution_id IS NOT NULL;
-- statement
CREATE INDEX control_insights_facts_unprojected ON control_insights_facts(tenant_id,invocation_id,fact_seq) WHERE projected_at IS NULL;
-- statement
CREATE INDEX control_insights_invocations_accepted ON control_insights_invocations(tenant_id,accepted_at);
-- statement
CREATE INDEX control_insights_invocations_parent ON control_insights_invocations(tenant_id,parent_invocation_id) WHERE parent_invocation_id IS NOT NULL;
-- statement
CREATE INDEX control_insights_invocations_run ON control_insights_invocations(tenant_id,agent_run_id) WHERE agent_run_id IS NOT NULL;
-- statement
CREATE INDEX control_insights_invocations_session ON control_insights_invocations(tenant_id,session_id) WHERE session_id IS NOT NULL;
-- statement
CREATE INDEX control_insights_invocations_tool_call ON control_insights_invocations(tenant_id,tool_call_id) WHERE tool_call_id IS NOT NULL;
-- statement
CREATE INDEX control_insights_spans_stored ON control_insights_spans(tenant_id,stored_at);
-- statement
CREATE INDEX control_invocation_active_package ON control_enterprise_invocations(tenant_id,package_digest) WHERE state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');
-- statement
CREATE INDEX control_invocation_active_tool ON control_enterprise_invocations(tenant_id,tool_id) WHERE state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');
-- statement
CREATE UNIQUE INDEX control_invocation_event_identity ON control_invocation_events(tenant_id,invocation_id,fencing_token,sequence) WHERE sequence IS NOT NULL;
-- statement
CREATE INDEX control_invocation_event_stream_retention ON control_invocation_event_streams(tenant_id,retain_until) WHERE purged_at IS NULL;
-- statement
CREATE INDEX control_invocation_input_deadline ON control_enterprise_invocations(tenant_id,task_input_deadline_at) WHERE task_state IN('AWAITING_APPROVAL','INPUT_REQUIRED');
-- statement
CREATE INDEX control_invocation_lease ON control_enterprise_invocations(tenant_id,lease_expires_at) WHERE state='EXECUTING';
-- statement
CREATE INDEX control_invocation_result_retention ON control_invocation_results(tenant_id,retain_until) WHERE purged_at IS NULL;
-- statement
CREATE UNIQUE INDEX control_invocation_task ON control_enterprise_invocations(tenant_id,task_id) WHERE task_id IS NOT NULL;
-- statement
CREATE INDEX control_invocation_task_retention ON control_enterprise_invocations(tenant_id,task_retain_until) WHERE task_id IS NOT NULL;
-- statement
CREATE INDEX control_mcp_pending ON control_mcp_requests(tenant_id,device_id,state,received_at);
-- statement
CREATE INDEX control_mcp_scans_queue ON control_mcp_scans(tenant_id,host_id,state,created_at);
-- statement
CREATE INDEX control_mcp_scans_server ON control_mcp_scans(tenant_id,slug,state);
-- statement
CREATE INDEX control_nonce_operation_purpose ON control_enterprise_nonces(tenant_id,invocation_id,purpose);
-- statement
CREATE UNIQUE INDEX control_one_live_effect_generation ON control_effect_generations(tenant_id,effect_id) WHERE state IN('AUTHORIZED','SENDING','CONSUMED');
-- statement
CREATE UNIQUE INDEX control_tenant_identity_keys_active ON control_tenant_identity_keys(tenant_id,purpose) WHERE state='ACTIVE';
-- statement
CREATE UNIQUE INDEX control_tenant_vault_keys_current ON control_tenant_vault_keys(tenant_id) WHERE state='ACTIVE';
-- statement
CREATE INDEX control_tool_host_dispatches_queue ON control_tool_host_dispatches(tenant_id,host_id,state,created_at);
-- statement
CREATE TRIGGER attempt_effect_guard BEFORE INSERT ON control_attempt_effects
 WHEN NOT(COALESCE(EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.operation_id AND g.attempt_id=NEW.attempt_id AND g.effect_id=NEW.effect_id)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),0)) BEGIN SELECT RAISE(ABORT,'manifest membership requires open admitted generation'); END;
-- statement
CREATE TRIGGER attempt_effects_capacity AFTER INSERT ON control_attempt_effects BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(NEW.effect_id) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER attempt_effects_delete_immutable BEFORE DELETE ON control_attempt_effects BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_effects_update_immutable BEFORE UPDATE ON control_attempt_effects BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_manifest_seal_guard BEFORE INSERT ON control_attempt_manifest_seals
 WHEN NOT(COALESCE(EXISTS(SELECT 1 FROM control_operation_attempts a JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 WHERE a.tenant_id=NEW.tenant_id AND a.operation_id=NEW.operation_id AND a.attempt_id=NEW.attempt_id
 AND NEW.created_at>=a.issued_at AND i.revision=NEW.invocation_revision
 AND (i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') OR COALESCE(json_extract(i.document,'$.reservedNonce'),'')<>NEW.attempt_id))
 AND json_extract(NEW.document,'$.format')='toolgate-attempt-manifest-v1'
 AND json_extract(NEW.document,'$.operationId')=NEW.operation_id AND json_extract(NEW.document,'$.attemptId')=NEW.attempt_id
 AND json_type(NEW.document,'$.effects')='array' AND NOT EXISTS(SELECT 1 FROM json_each(NEW.document,'$.effects') j WHERE j.type<>'text')
 AND NEW.effect_count=(SELECT count(*) FROM json_each(NEW.document,'$.effects') j)
 AND NEW.effect_count=(SELECT count(*) FROM control_attempt_effects m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_effects m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NOT EXISTS(SELECT 1 FROM json_each(NEW.document,'$.effects') j WHERE j.value=m.effect_id)),0)) BEGIN SELECT RAISE(ABORT,'manifest sealing requires closed authority and exact membership'); END;
-- statement
CREATE TRIGGER attempt_manifest_seals_capacity AFTER INSERT ON control_attempt_manifest_seals BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER attempt_manifest_seals_delete_immutable BEFORE DELETE ON control_attempt_manifest_seals BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_manifest_seals_update_immutable BEFORE UPDATE ON control_attempt_manifest_seals BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_review_capacity AFTER INSERT ON control_attempt_reviews BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER attempt_review_delete_immutable BEFORE DELETE ON control_attempt_reviews BEGIN SELECT RAISE(ABORT,'attempt review is immutable'); END;
-- statement
CREATE TRIGGER attempt_review_guard BEFORE INSERT ON control_attempt_reviews WHEN NOT(EXISTS(SELECT 1 FROM control_attempt_manifest_seals m
 JOIN control_operation_attempts a ON a.tenant_id=m.tenant_id AND a.operation_id=m.operation_id AND a.attempt_id=m.attempt_id
 JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id
 JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 JOIN control_operation_outcomes v ON v.tenant_id=i.tenant_id AND v.operation_id=i.invocation_id AND v.revision=NEW.expected_outcome_revision
 JOIN control_outcome_reconciliations c ON c.tenant_id=i.tenant_id AND c.reconciliation_id=NEW.reconciliation_id
 JOIN control_tenant_authority t ON t.tenant_id=i.tenant_id
 WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NOT o.legacy AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.recorded_at
 AND i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING')
 AND v.state='OUTCOME_UNKNOWN' AND v.invocation_revision=i.revision
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)
 AND json_type(v.document,'$.journalComplete')='true'
 AND json_extract(v.document,'$.unresolvedEffectCount')=0
 AND json_extract(c.document,'$.state')='APPLIED'
 AND json_extract(c.document,'$.invocationId')=i.invocation_id
 AND json_extract(c.document,'$.request.expectedRevision')=i.revision
 AND json_extract(c.document,'$.request.attemptSettlement.expectedOutcomeRevision')=NEW.expected_outcome_revision
 AND json_type(c.document,'$.request.attemptSettlement.noFurtherEffects')='true'

 AND EXISTS(SELECT 1 FROM control_records u WHERE u.tenant_id=i.tenant_id AND u.kind='USER' AND u.record_id=json_extract(c.document,'$.requesterUserId') AND json_extract(u.document,'$.enabled')=1)
 AND EXISTS(SELECT 1 FROM control_records u WHERE u.tenant_id=i.tenant_id AND u.kind='USER' AND u.record_id=json_extract(c.document,'$.reviewerUserId') AND json_extract(u.document,'$.enabled')=1)
 AND NOT EXISTS(SELECT 1 FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT json_extract(i.document,'$.evaluation.context.agentId') UNION SELECT json_extract(h.value,'$.agentId') FROM json_each(i.document,'$.evaluation.context.chain') h) AND json_extract(d.document,'$.ownerUserId') IN(json_extract(c.document,'$.requesterUserId'),json_extract(c.document,'$.reviewerUserId')))
 AND NEW.attempt_id IN(SELECT value FROM json_each(c.document,'$.request.attemptSettlement.attemptIds'))
 AND json_extract(c.document,'$.requesterUserId')<>json_extract(c.document,'$.reviewerUserId')
 AND (EXISTS(SELECT 1 FROM control_operation_review_owners w WHERE w.tenant_id=i.tenant_id AND w.operation_id=i.invocation_id
 AND json_extract(c.document,'$.requesterUserId') NOT IN(SELECT value FROM json_each(w.document,'$.owners')) AND json_extract(c.document,'$.reviewerUserId') NOT IN(SELECT value FROM json_each(w.document,'$.owners')))
 OR (NOT EXISTS(SELECT 1 FROM control_operation_review_owners w WHERE w.tenant_id=i.tenant_id AND w.operation_id=i.invocation_id)
 AND json_extract(i.document,'$.evaluation.context.mode')='HUMAN' AND json_extract(i.document,'$.evaluation.context.agentId') IS NULL AND json_array_length(i.document,'$.evaluation.context.chain')=0
 AND json_extract(c.document,'$.requesterUserId')<>json_extract(i.document,'$.evaluation.context.userId') AND json_extract(c.document,'$.reviewerUserId')<>json_extract(i.document,'$.evaluation.context.userId')))
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id AND l.fencing_token=m.attempt_id AND l.expires_at>NEW.recorded_at)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=m.tenant_id AND s.operation_id=m.operation_id AND s.attempt_id=m.attempt_id)
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id
 AND ((g.state NOT IN('RELEASED','COMPLETED','FAILED') AND NOT(g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_effect_target_bindings b
 JOIN control_effect_intents intent ON intent.tenant_id=b.tenant_id AND intent.effect_id=b.effect_id AND intent.profile_digest=b.profile_digest
 JOIN control_effect_evidence witness ON witness.tenant_id=b.tenant_id AND witness.effect_id=b.effect_id
 WHERE b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id AND witness.generation=g.generation
 AND json_extract(b.document,'$.statusRouteId') IS NOT NULL AND substr(witness.source,1,14)='TARGET_LOOKUP:' AND length(witness.source)=78
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence other WHERE other.tenant_id=witness.tenant_id AND other.effect_id=witness.effect_id
 AND other.generation=witness.generation AND substr(other.source,1,14)='TARGET_LOOKUP:'
 AND (other.source<>witness.source OR other.outcome<>witness.outcome OR other.result_digest<>witness.result_digest))))) OR (g.state IN('COMPLETED','FAILED') AND (g.consumer IS NULL OR g.claim_token IS NULL OR NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation AND e.source='TARGET:'||g.consumer AND e.outcome=g.state)))))
 AND json_extract(NEW.document,'$.format')='toolgate-attempt-review-v1' AND json_extract(NEW.document,'$.operationId')=NEW.operation_id
 AND json_extract(NEW.document,'$.attemptId')=NEW.attempt_id AND json_extract(NEW.document,'$.manifestDigest')=m.manifest_digest
 AND json_extract(NEW.document,'$.outcomeSourceDigest')=v.source_digest
 AND json_extract(NEW.document,'$.review.requestDigest')=json_extract(c.document,'$.requestDigest')
 AND json_extract(NEW.document,'$.directoryRevision')=t.revision AND json_extract(NEW.document,'$.authorizationEpoch')=t.authorization_epoch
))
 BEGIN SELECT RAISE(ABORT,'attempt review requires exact independent approved closed resolved evidence'); END;
-- statement
CREATE TRIGGER attempt_review_update_immutable BEFORE UPDATE ON control_attempt_reviews BEGIN SELECT RAISE(ABORT,'attempt review is immutable'); END;
-- statement
CREATE TRIGGER attempt_settlement_capacity AFTER INSERT ON control_attempt_settlements BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER attempt_settlement_delete_immutable BEFORE DELETE ON control_attempt_settlements BEGIN SELECT RAISE(ABORT,'attempt settlement is immutable'); END;
-- statement
CREATE TRIGGER attempt_settlement_guard BEFORE INSERT ON control_attempt_settlements
 WHEN NOT(COALESCE(EXISTS(SELECT 1 FROM control_attempt_manifest_seals m
 JOIN control_operation_attempts a ON a.tenant_id=m.tenant_id AND a.operation_id=m.operation_id AND a.attempt_id=m.attempt_id
 JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id
 JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NEW.recorded_at>=m.created_at AND i.revision=NEW.invocation_revision AND n.purpose='EXECUTION'
 AND (i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') OR COALESCE(json_extract(i.document,'$.reservedNonce'),'')<>NEW.attempt_id)
 AND json_extract(NEW.document,'$.format')='toolgate-attempt-settlement-v1'
 AND json_extract(NEW.document,'$.operationId')=NEW.operation_id AND json_extract(NEW.document,'$.attemptId')=NEW.attempt_id
 AND json_extract(NEW.document,'$.proof')=NEW.proof AND json_extract(NEW.document,'$.manifestDigest')=m.manifest_digest
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=m.tenant_id AND l.invocation_id=m.operation_id AND l.fencing_token=m.attempt_id AND l.expires_at>NEW.recorded_at)
 AND (SELECT count(*) FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id)<=4096
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id
 AND ((g.state NOT IN('RELEASED','COMPLETED','FAILED') AND NOT(g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_effect_target_bindings b
 JOIN control_effect_intents intent ON intent.tenant_id=b.tenant_id AND intent.effect_id=b.effect_id AND intent.profile_digest=b.profile_digest
 JOIN control_effect_evidence witness ON witness.tenant_id=b.tenant_id AND witness.effect_id=b.effect_id
 WHERE b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id AND witness.generation=g.generation
 AND json_extract(b.document,'$.statusRouteId') IS NOT NULL AND substr(witness.source,1,14)='TARGET_LOOKUP:' AND length(witness.source)=78
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence other WHERE other.tenant_id=witness.tenant_id AND other.effect_id=witness.effect_id
 AND other.generation=witness.generation AND substr(other.source,1,14)='TARGET_LOOKUP:'
 AND (other.source<>witness.source OR other.outcome<>witness.outcome OR other.result_digest<>witness.result_digest)))))
 OR (g.state IN('COMPLETED','FAILED') AND (g.consumer IS NULL OR g.claim_token IS NULL OR NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation
 AND e.source='TARGET:'||g.consumer AND e.outcome=g.state)))
 OR NOT EXISTS(SELECT 1 FROM control_attempt_effects x WHERE x.tenant_id=g.tenant_id AND x.operation_id=g.operation_id AND x.attempt_id=g.attempt_id AND x.effect_id=g.effect_id)))
 AND ((NEW.proof='NEVER_CONSUMED' AND n.consumed_at IS NULL AND n.expires_at<=NEW.recorded_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id))
 OR (NEW.proof='QUALIFIED_BROKER_ONLY' AND n.consumed_at IS NOT NULL AND EXISTS(
 SELECT 1 FROM control_attempt_recovery_scopes s JOIN control_endpoints ep ON ep.tenant_id=s.tenant_id AND ep.device_id=s.device_id
 JOIN control_records d ON d.tenant_id=s.tenant_id AND d.kind='DEVICE' AND d.record_id=s.device_id
 WHERE s.tenant_id=m.tenant_id AND s.operation_id=m.operation_id AND s.attempt_id=m.attempt_id AND s.device_id=json_extract(i.document,'$.evaluation.context.deviceId')
 AND json_extract(ep.document,'$.state')='ACTIVE' AND json_extract(d.document,'$.enabled')=1)))),0)) BEGIN SELECT RAISE(ABORT,'attempt settlement requires closed resolved witnessed authority'); END;
-- statement
CREATE TRIGGER attempt_settlement_update_immutable BEFORE UPDATE ON control_attempt_settlements BEGIN SELECT RAISE(ABORT,'attempt settlement is immutable'); END;
-- statement
CREATE TRIGGER consent_approval_guard BEFORE INSERT ON control_effect_consent_approvals WHEN NOT EXISTS(SELECT 1 FROM control_effect_consents c JOIN control_tenant_authority t ON t.tenant_id=c.tenant_id
 WHERE c.tenant_id=NEW.tenant_id AND c.consent_id=NEW.consent_id AND t.authorization_epoch=NEW.authorization_epoch
 AND c.not_before<=NEW.approved_at AND c.expires_at>NEW.approved_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_consent_revocations r WHERE r.tenant_id=c.tenant_id AND r.consent_id=c.consent_id)) BEGIN SELECT RAISE(ABORT,'effect consent scope or allowance refused'); END;
-- statement
CREATE TRIGGER consent_approvals_capacity AFTER INSERT ON control_effect_consent_approvals BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER consent_approvals_immutable_delete BEFORE DELETE ON control_effect_consent_approvals BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_approvals_immutable_update BEFORE UPDATE ON control_effect_consent_approvals BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_revocations_capacity AFTER INSERT ON control_effect_consent_revocations BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER consent_revocations_immutable_delete BEFORE DELETE ON control_effect_consent_revocations BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_revocations_immutable_update BEFORE UPDATE ON control_effect_consent_revocations BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_slots_capacity AFTER INSERT ON control_effect_consent_slots BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER consent_slots_immutable_delete BEFORE DELETE ON control_effect_consent_slots BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_slots_immutable_update BEFORE UPDATE ON control_effect_consent_slots BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_use_guard BEFORE INSERT ON control_effect_consent_uses WHEN NOT EXISTS(SELECT 1 FROM control_effect_consent_slots s
 JOIN control_effect_consents c ON c.tenant_id=s.tenant_id AND c.consent_id=s.consent_id
 JOIN control_effect_consent_approvals a ON a.tenant_id=c.tenant_id AND a.consent_id=c.consent_id
 JOIN control_tenant_authority t ON t.tenant_id=c.tenant_id AND (a.authorization_epoch=t.authorization_epoch OR (a.authorization_epoch<t.authorization_epoch AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=t.tenant_id AND k.authorization_epoch>a.authorization_epoch AND k.authorization_epoch<=t.authorization_epoch)=t.authorization_epoch-a.authorization_epoch))
 JOIN control_effects e ON e.tenant_id=c.tenant_id AND e.operation_id=c.operation_id AND e.route_id=s.route_id
 JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE s.tenant_id=NEW.tenant_id AND s.consent_id=NEW.consent_id AND s.slot_index=NEW.slot_index
 AND e.effect_id=NEW.effect_id AND e.request_digest=NEW.request_digest
 AND c.not_before<=NEW.reserved_at AND c.expires_at>NEW.reserved_at AND a.approved_at<=NEW.reserved_at
 AND g.state='AUTHORIZED' AND g.expires_at>NEW.reserved_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_consent_revocations r WHERE r.tenant_id=c.tenant_id AND r.consent_id=c.consent_id)
 AND (SELECT count(*) FROM control_effect_consent_uses u WHERE u.tenant_id=s.tenant_id AND u.consent_id=s.consent_id AND u.slot_index=s.slot_index)<s.permitted_count) BEGIN SELECT RAISE(ABORT,'effect consent scope or allowance refused'); END;
-- statement
CREATE TRIGGER consent_uses_capacity AFTER INSERT ON control_effect_consent_uses BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER consent_uses_immutable_delete BEFORE DELETE ON control_effect_consent_uses BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_uses_immutable_update BEFORE UPDATE ON control_effect_consent_uses BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consents_capacity AFTER INSERT ON control_effect_consents BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER consents_immutable_delete BEFORE DELETE ON control_effect_consents BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consents_immutable_update BEFORE UPDATE ON control_effect_consents BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER control_approval_clocks_tenant_immutable BEFORE UPDATE OF tenant_id ON control_approval_clocks WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_attempt_binding_guard BEFORE INSERT ON control_operation_attempts WHEN
 NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.nonce=NEW.attempt_id
 AND n.purpose<>'SERVICE' AND n.invocation_id=NEW.operation_id AND n.evaluation_digest=NEW.evaluation_digest AND n.authorization_epoch=NEW.authorization_epoch
 AND n.issued_at=NEW.issued_at AND n.expires_at=NEW.expires_at)
 BEGIN SELECT RAISE(ABORT,'attempt must bind its original permit'); END;
-- statement
CREATE TRIGGER control_attempt_reservation_guard BEFORE INSERT ON control_enterprise_nonces WHEN NEW.purpose<>'SERVICE' AND (
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id)
 OR EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.purpose<>'SERVICE'
 AND ((n.consumed_at IS NULL AND n.expires_at>NEW.issued_at)
 OR NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce)
 OR (n.consumed_at IS NOT NULL AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=n.tenant_id AND r.operation_id=n.invocation_id
 AND r.proof IN('QUALIFIED_BROKER_NO_WRITE','TARGET_DEDUPLICATION') AND r.created_at<=NEW.issued_at AND r.to_revision<=i.revision AND EXISTS(SELECT 1 FROM json_each(r.attempt_ids) AS j WHERE j.value=n.nonce))))))
 OR EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED')) BEGIN SELECT RAISE(ABORT,'predecessor requires positive recovery coverage'); END;
-- statement
CREATE TRIGGER control_attempt_snapshots_immutable_delete BEFORE DELETE ON control_attempt_snapshots BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_attempt_snapshots_immutable_update BEFORE UPDATE ON control_attempt_snapshots BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_audit_chain BEFORE INSERT ON control_audit WHEN NEW.chain_index IS NOT COALESCE((SELECT max(chain_index) FROM control_audit WHERE tenant_id=NEW.tenant_id),0)+1 OR NEW.previous_digest IS NOT COALESCE((SELECT row_digest FROM control_audit WHERE tenant_id=NEW.tenant_id ORDER BY chain_index DESC LIMIT 1),'0000000000000000000000000000000000000000000000000000000000000000') BEGIN SELECT RAISE(ABORT,'audit chain link mismatch'); END;
-- statement
CREATE TRIGGER control_audit_delete BEFORE DELETE ON control_audit BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_audit_tenant_immutable BEFORE UPDATE OF tenant_id ON control_audit WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_audit_update BEFORE UPDATE ON control_audit BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_audit_anchors_delete BEFORE DELETE ON control_audit_anchors BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_audit_anchors_update BEFORE UPDATE ON control_audit_anchors BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_authorization_outbox_tenant_immutable BEFORE UPDATE OF tenant_id ON control_authorization_outbox WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_bootstrap_admin_delete BEFORE DELETE ON control_bootstrap_admins
BEGIN SELECT RAISE(ABORT,'setup administrator is immutable'); END;
-- statement
CREATE TRIGGER control_bootstrap_admin_update BEFORE UPDATE ON control_bootstrap_admins
BEGIN SELECT RAISE(ABORT,'setup administrator is immutable'); END;
-- statement
CREATE TRIGGER control_bootstrap_admins_tenant_immutable BEFORE UPDATE OF tenant_id ON control_bootstrap_admins WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_builder_delete BEFORE DELETE ON control_builder WHEN OLD.kind<>'TEST' OR json_extract(OLD.document,'$.expiresAtUnixMs')>=unixepoch()*1000-604800000 BEGIN SELECT RAISE(ABORT,'local retention violation'); END;
-- statement
CREATE TRIGGER control_builder_tenant_immutable BEFORE UPDATE OF tenant_id ON control_builder WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_builder_update BEFORE UPDATE ON control_builder WHEN OLD.kind='VERSION' OR (OLD.kind='DRAFT' AND json_extract(OLD.document,'$.sealed')=1) OR NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.record_id<>OLD.record_id OR NEW.revision<>OLD.revision+1 BEGIN SELECT RAISE(ABORT,'immutable local version'); END;
-- statement
CREATE TRIGGER control_business_key_delete BEFORE DELETE ON control_business_keys BEGIN SELECT RAISE(ABORT,'business keys are tombstoned, never deleted'); END;
-- statement
CREATE TRIGGER control_business_key_history_delete BEFORE DELETE ON control_business_key_history BEGIN SELECT RAISE(ABORT,'business key history is immutable'); END;
-- statement
CREATE TRIGGER control_business_key_history_insert BEFORE INSERT ON control_business_key_history WHEN NOT EXISTS(SELECT 1 FROM control_business_keys k JOIN control_operations o
 ON o.tenant_id=k.tenant_id AND o.operation_id=k.invocation_id
 WHERE k.tenant_id IS NEW.tenant_id AND k.tool_id IS NEW.tool_id AND k.namespace IS NEW.namespace AND k.namespace_identity IS NEW.namespace_identity AND k.business_key_hash IS NEW.business_key_hash AND k.invocation_id IS NEW.invocation_id AND k.fingerprint IS NEW.fingerprint AND k.caller_agent_id IS NEW.caller_agent_id AND k.caller_user_id IS NEW.caller_user_id AND k.created_at IS NEW.created_at AND k.dedupe_expires_at IS NEW.dedupe_expires_at AND k.retain_until IS NEW.retain_until AND k.outcome IS NEW.outcome AND k.tombstoned_at IS NEW.tombstoned_at AND k.source='DEDUPE_WINDOW' AND k.dedupe_expires_at<=NEW.archived_at
 AND o.legacy=0 AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))) BEGIN SELECT RAISE(ABORT,'window archive requires an exact resolved reservation'); END;
-- statement
CREATE TRIGGER control_business_key_history_update BEFORE UPDATE ON control_business_key_history BEGIN SELECT RAISE(ABORT,'business key history is immutable'); END;
-- statement
CREATE TRIGGER control_business_key_update BEFORE UPDATE ON control_business_keys WHEN NOT(NEW.tenant_id IS OLD.tenant_id AND NEW.tool_id IS OLD.tool_id AND NEW.namespace IS OLD.namespace AND NEW.namespace_identity IS OLD.namespace_identity AND NEW.business_key_hash IS OLD.business_key_hash AND NEW.source IS OLD.source AND NEW.fingerprint IS OLD.fingerprint AND NEW.caller_agent_id IS OLD.caller_agent_id AND NEW.caller_user_id IS OLD.caller_user_id AND NEW.invocation_id IS OLD.invocation_id AND NEW.created_at IS OLD.created_at AND NEW.dedupe_expires_at IS OLD.dedupe_expires_at AND NEW.retain_until IS OLD.retain_until AND NEW.tombstoned_at IS OLD.tombstoned_at AND EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=OLD.tenant_id AND v.operation_id=OLD.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)
 AND NEW.outcome IS CASE v.state WHEN 'RUNNING' THEN NULL WHEN 'SUCCEEDED' THEN 'COMPLETED'
 WHEN 'FAILED' THEN 'FAILED' WHEN 'PARTIAL' THEN 'PARTIAL' ELSE 'OUTCOME_UNKNOWN' END)) AND ((NEW.tenant_id IS NOT OLD.tenant_id OR NEW.tool_id IS NOT OLD.tool_id OR NEW.namespace IS NOT OLD.namespace OR NEW.namespace_identity IS NOT OLD.namespace_identity OR NEW.business_key_hash IS NOT OLD.business_key_hash) OR NEW.source<>OLD.source OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source<>'DEDUPE_WINDOW' AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS NOT OLD.fingerprint OR NEW.caller_agent_id IS NOT OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id OR NEW.dedupe_expires_at IS NOT OLD.dedupe_expires_at OR NEW.retain_until IS NOT OLD.retain_until) OR
 ((NEW.invocation_id<>OLD.invocation_id OR NEW.created_at<>OLD.created_at)
 AND NOT(COALESCE(OLD.outcome,'') IN('EXPIRED','FAILED') AND NEW.outcome IS NULL)))) OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source='DEDUPE_WINDOW' AND
 (OLD.dedupe_expires_at>NEW.created_at OR NEW.fingerprint IS NOT OLD.fingerprint OR NEW.caller_agent_id IS NOT OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id OR NEW.outcome IS NOT NULL
 OR NEW.tombstoned_at IS NOT NULL OR NEW.retain_until<NEW.dedupe_expires_at
 OR NOT EXISTS(SELECT 1 FROM control_business_key_history h JOIN control_operations o
 ON o.tenant_id=h.tenant_id AND o.operation_id=h.invocation_id
 WHERE h.tenant_id IS OLD.tenant_id AND h.tool_id IS OLD.tool_id AND h.namespace IS OLD.namespace AND h.namespace_identity IS OLD.namespace_identity AND h.business_key_hash IS OLD.business_key_hash AND h.invocation_id IS OLD.invocation_id AND h.fingerprint IS OLD.fingerprint AND h.caller_agent_id IS OLD.caller_agent_id AND h.caller_user_id IS OLD.caller_user_id AND h.created_at IS OLD.created_at AND h.dedupe_expires_at IS OLD.dedupe_expires_at AND h.retain_until IS OLD.retain_until AND h.outcome IS OLD.outcome AND h.tombstoned_at IS OLD.tombstoned_at AND h.archived_at=NEW.created_at AND o.legacy=0
 AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))))) OR (NEW.invocation_id=OLD.invocation_id AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS NOT OLD.fingerprint OR NEW.caller_agent_id IS NOT OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id OR NEW.dedupe_expires_at IS NOT OLD.dedupe_expires_at OR NEW.retain_until IS NOT OLD.retain_until) OR NEW.created_at<>OLD.created_at
 OR (NEW.outcome IS NOT OLD.outcome AND OLD.outcome IS NOT NULL
 AND NOT(OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED'))) OR (NEW.tombstoned_at IS NOT OLD.tombstoned_at AND
 (NEW.tombstoned_at IS NULL OR NEW.tombstoned_at<OLD.retain_until OR
 OLD.outcome IS NULL OR OLD.outcome='OUTCOME_UNKNOWN' OR
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=OLD.tenant_id
 AND o.operation_id=OLD.invocation_id AND o.legacy=0 AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id))))))))) AND NOT(NEW.tenant_id IS OLD.tenant_id AND NEW.tool_id IS OLD.tool_id AND NEW.namespace IS OLD.namespace AND NEW.namespace_identity IS OLD.namespace_identity AND NEW.business_key_hash IS OLD.business_key_hash AND NEW.source IS OLD.source AND NEW.fingerprint IS OLD.fingerprint AND NEW.caller_agent_id IS OLD.caller_agent_id AND NEW.caller_user_id IS OLD.caller_user_id AND NEW.invocation_id IS OLD.invocation_id AND NEW.created_at IS OLD.created_at AND NEW.dedupe_expires_at IS OLD.dedupe_expires_at AND NEW.retain_until IS OLD.retain_until AND NEW.tombstoned_at IS OLD.tombstoned_at
 AND NEW.outcome IS NULL AND OLD.outcome IN('FAILED','EXPIRED','OUTCOME_UNKNOWN')
 AND OLD.tombstoned_at IS NULL
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision=i.revision AND i.state='QUEUED' AND o.state='RUNNING'
 AND OLD.retain_until>r.created_at)) BEGIN SELECT RAISE(ABORT,'business key identity, retention or recovery proof is immutable'); END;
-- statement
CREATE TRIGGER control_business_keys_tenant_immutable BEFORE UPDATE OF tenant_id ON control_business_keys WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_configuration_changes_tenant_immutable BEFORE UPDATE OF tenant_id ON control_configuration_changes WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_console_devices_tenant_immutable BEFORE UPDATE OF tenant_id ON control_console_devices WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_credential_history_immutable_delete BEFORE DELETE ON control_credential_history BEGIN SELECT RAISE(ABORT,'immutable credential history'); END;
-- statement
CREATE TRIGGER control_credential_history_immutable_update BEFORE UPDATE ON control_credential_history BEGIN SELECT RAISE(ABORT,'immutable credential history'); END;
-- statement
CREATE TRIGGER control_credential_history_tenant_immutable BEFORE UPDATE OF tenant_id ON control_credential_history WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_endpoint_configurations_tenant_immutable BEFORE UPDATE OF tenant_id ON control_endpoint_configurations WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_endpoints_tenant_immutable BEFORE UPDATE OF tenant_id ON control_endpoints WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_enrollments_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enrollments WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_enterprise_adoptions_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_adoptions WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_enterprise_approvals_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_approvals WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_enterprise_invocations_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_invocations WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_enterprise_nonces_tenant_immutable BEFORE UPDATE OF tenant_id ON control_enterprise_nonces WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_epoch_guard BEFORE UPDATE ON control_tenant_authority WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.revision<OLD.revision OR NEW.authorization_epoch<OLD.authorization_epoch OR NEW.directory_cache_generation<OLD.directory_cache_generation BEGIN SELECT RAISE(ABORT,'authorization cannot move backwards'); END;
-- statement
CREATE TRIGGER control_execution_budgets_tenant_immutable BEFORE UPDATE OF tenant_id ON control_execution_budgets WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_execution_evidence_no_delete BEFORE DELETE ON control_execution_evidence
BEGIN SELECT RAISE(ABORT,'execution evidence is immutable'); END;
-- statement
CREATE TRIGGER control_execution_evidence_no_update BEFORE UPDATE ON control_execution_evidence
BEGIN SELECT RAISE(ABORT,'execution evidence is immutable'); END;
-- statement
CREATE TRIGGER control_execution_leases_identity_immutable BEFORE UPDATE ON control_execution_leases WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.invocation_id<>OLD.invocation_id OR NEW.device_id<>OLD.device_id OR (NEW.fencing_token<>OLD.fencing_token AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.fencing_token
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision<=i.revision
 AND i.state='EXECUTING' AND json_extract(i.document,'$.reservedNonce')=NEW.fencing_token
 AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND NEW.expires_at<=i.expires_at
 AND json_extract(json_extract(r.document,'$.lease'),'$.fencingToken')=OLD.fencing_token AND json_extract(json_extract(r.document,'$.lease'),'$.deviceId')=OLD.device_id
 AND json_extract(json_extract(r.document,'$.lease'),'$.expiresAt')=OLD.expires_at AND EXISTS(SELECT 1 FROM json_each(r.attempt_ids) AS j WHERE j.value=OLD.fencing_token)))) BEGIN SELECT RAISE(ABORT,'execution identity requires archived recovery proof'); END;
-- statement
CREATE TRIGGER control_fleet_delete BEFORE DELETE ON control_fleet  BEGIN SELECT RAISE(ABORT,'local retention violation'); END;
-- statement
CREATE TRIGGER control_fleet_tenant_immutable BEFORE UPDATE OF tenant_id ON control_fleet WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_fleet_update BEFORE UPDATE ON control_fleet WHEN OLD.kind='RELEASE' OR NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.record_id<>OLD.record_id OR NEW.revision<>OLD.revision+1 BEGIN SELECT RAISE(ABORT,'immutable local version'); END;
-- statement
CREATE TRIGGER control_idempotency_tenant_immutable BEFORE UPDATE OF tenant_id ON control_idempotency WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_admissions_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_admissions WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_attempts_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_attempts WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_cells_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_cells WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_checkpoints_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_checkpoints WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_facts_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_facts WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_gateway_epochs_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_gateway_epochs WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_invocations_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_invocations WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_spans_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_spans WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_insights_tenant_state_tenant_immutable BEFORE UPDATE OF tenant_id ON control_insights_tenant_state WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_invocation_event_streams_tenant_immutable BEFORE UPDATE OF tenant_id ON control_invocation_event_streams WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_invocation_events_tenant_immutable BEFORE UPDATE OF tenant_id ON control_invocation_events WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_invocation_result_delete BEFORE DELETE ON control_invocation_results BEGIN SELECT RAISE(ABORT,'invocation results are purged, never deleted'); END;
-- statement
CREATE TRIGGER control_invocation_result_update BEFORE UPDATE ON control_invocation_results WHEN OLD.purged_at IS NOT NULL OR NEW.payload IS NOT NULL OR NEW.purged_at IS NULL OR NEW.tenant_id<>OLD.tenant_id OR NEW.invocation_id<>OLD.invocation_id OR NEW.fencing_nonce<>OLD.fencing_nonce OR NEW.result_digest<>OLD.result_digest OR NEW.byte_length<>OLD.byte_length OR NEW.created_at<>OLD.created_at OR NEW.retain_until<>OLD.retain_until BEGIN SELECT RAISE(ABORT,'invocation result is immutable except for its purge'); END;
-- statement
CREATE TRIGGER control_invocation_results_tenant_immutable BEFORE UPDATE OF tenant_id ON control_invocation_results WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
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
CREATE TRIGGER control_mcp_approvals_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_approvals WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_mcp_requests_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_requests WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_mcp_scans_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_scans WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_mcp_servers_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_servers WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_mcp_snapshots_tenant_immutable BEFORE UPDATE OF tenant_id ON control_mcp_snapshots WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_nonce_identity_guard BEFORE UPDATE ON control_enterprise_nonces WHEN
 NEW.tenant_id<>OLD.tenant_id OR NEW.nonce<>OLD.nonce OR NEW.invocation_id<>OLD.invocation_id
 OR NEW.evaluation_digest<>OLD.evaluation_digest OR NEW.authorization_epoch<>OLD.authorization_epoch
 OR NEW.issued_at<>OLD.issued_at OR NEW.expires_at<>OLD.expires_at OR NEW.purpose<>OLD.purpose
 OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS NOT OLD.consumed_at)
 OR (NEW.consumed_at IS NOT NULL AND (NEW.consumed_at<OLD.issued_at OR NEW.consumed_at>=OLD.expires_at))
 OR (NEW.purpose<>'SERVICE' AND OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=NEW.tenant_id AND a.attempt_id=NEW.nonce))
 OR (OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED'))
 BEGIN SELECT RAISE(ABORT,'nonce identity and consumption are immutable'); END;
-- statement
CREATE TRIGGER control_operation_attempts_immutable_delete BEFORE DELETE ON control_operation_attempts BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_operation_attempts_immutable_update BEFORE UPDATE ON control_operation_attempts BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_operation_cancellations_immutable_delete BEFORE DELETE ON control_operation_cancellations BEGIN SELECT RAISE(ABORT,'immutable cancellation'); END;
-- statement
CREATE TRIGGER control_operation_cancellations_immutable_update BEFORE UPDATE ON control_operation_cancellations BEGIN SELECT RAISE(ABORT,'immutable cancellation'); END;
-- statement
CREATE TRIGGER control_operations_delete BEFORE DELETE ON control_operations BEGIN SELECT RAISE(ABORT,'operation is retained'); END;
-- statement
CREATE TRIGGER control_operations_guard BEFORE UPDATE ON control_operations WHEN
 NEW.tenant_id<>OLD.tenant_id OR NEW.operation_id<>OLD.operation_id OR NEW.request_digest<>OLD.request_digest
 OR NEW.created_at<>OLD.created_at OR NEW.legacy<>OLD.legacy OR NEW.revision<>OLD.revision+1
 OR (OLD.state='FAILED' AND NEW.state<>'FAILED' AND NOT(NEW.state='RUNNING' AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.operation_id
 AND r.operation_revision=OLD.revision AND i.revision=r.to_revision AND i.state='QUEUED' AND i.expires_at=r.original_deadline)))
 BEGIN SELECT RAISE(ABORT,'operation identity or finality is immutable'); END;
-- statement
CREATE TRIGGER control_outcome_reconciliations_tenant_immutable BEFORE UPDATE OF tenant_id ON control_outcome_reconciliations WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_package_provisioning_tenant_immutable BEFORE UPDATE OF tenant_id ON control_package_provisioning WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_policy_bundles_delete BEFORE DELETE ON control_policy_bundles BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_policy_bundles_tenant_immutable BEFORE UPDATE OF tenant_id ON control_policy_bundles WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_policy_bundles_update BEFORE UPDATE ON control_policy_bundles BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_queued_authority_tenant_immutable BEFORE UPDATE OF tenant_id ON control_queued_authority WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_record_ids_delete BEFORE DELETE ON control_record_ids BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_record_ids_tenant_immutable BEFORE UPDATE OF tenant_id ON control_record_ids WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_record_ids_update BEFORE UPDATE ON control_record_ids BEGIN SELECT RAISE(ABORT,'append-only local state'); END;
-- statement
CREATE TRIGGER control_records_cache_delete AFTER DELETE ON control_records BEGIN
 UPDATE control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant authority missing') END;
END;
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
CREATE TRIGGER control_records_tenant_immutable BEFORE UPDATE OF tenant_id ON control_records WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_recovery_floors_tenant_immutable BEFORE UPDATE OF tenant_id ON control_recovery_floors WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_reviewed_recovery_immutable_delete BEFORE DELETE ON control_reviewed_recovery BEGIN SELECT RAISE(ABORT,'immutable reviewed recovery'); END;
-- statement
CREATE TRIGGER control_reviewed_recovery_immutable_update BEFORE UPDATE ON control_reviewed_recovery BEGIN SELECT RAISE(ABORT,'immutable reviewed recovery'); END;
-- statement
CREATE TRIGGER control_reviewed_recovery_tenant_immutable BEFORE UPDATE OF tenant_id ON control_reviewed_recovery WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_secret_version_guard BEFORE UPDATE ON control_secrets WHEN NEW.name<>OLD.name OR NEW.version<>OLD.version+1 BEGIN SELECT RAISE(ABORT,'invalid secret version transition'); END;
-- statement
CREATE TRIGGER control_secrets_tenant_immutable BEFORE UPDATE OF tenant_id ON control_secrets WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_server_deployments_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_deployments WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_server_documents_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_documents WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_server_packages_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_packages WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_server_settings_tenant_immutable BEFORE UPDATE OF tenant_id ON control_server_settings WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_settings_cache_delete AFTER DELETE ON control_server_settings BEGIN
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
CREATE TRIGGER control_snapshot_transition_guard BEFORE INSERT ON control_attempt_snapshots WHEN
 EXISTS(SELECT 1 FROM control_attempt_snapshots s WHERE s.tenant_id=NEW.tenant_id AND s.attempt_id=NEW.attempt_id
 AND (s.revision>=NEW.revision OR (s.state IN('SUCCEEDED','FAILED','PARTIAL','CANCELLED','EXPIRED') AND s.state<>NEW.state)
 OR (s.state='OUTCOME_UNKNOWN' AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING'))))
 BEGIN SELECT RAISE(ABORT,'attempt cannot reopen or rewrite history'); END;
-- statement
CREATE TRIGGER control_tenant_authority_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenant_authority WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_tenant_identity_key_guard BEFORE UPDATE ON control_tenant_identity_keys WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.purpose<>OLD.purpose OR NEW.key_id<>OLD.key_id OR NEW.provider_reference<>OLD.provider_reference OR NEW.public_material<>OLD.public_material OR NEW.key_fingerprint<>OLD.key_fingerprint OR NEW.generation<OLD.generation OR (OLD.state='RETIRED' AND NEW.state<>'RETIRED') BEGIN SELECT RAISE(ABORT,'invalid identity key transition'); END;
-- statement
CREATE TRIGGER control_tenant_identity_keys_no_delete BEFORE DELETE ON control_tenant_identity_keys BEGIN SELECT RAISE(ABORT,'identity key retention requires explicit offboarding'); END;
-- statement
CREATE TRIGGER control_tenant_identity_keys_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenant_identity_keys WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_tenant_initializations_immutable_delete BEFORE DELETE ON control_tenant_initializations BEGIN SELECT RAISE(ABORT,'initialization is immutable'); END;
-- statement
CREATE TRIGGER control_tenant_initializations_immutable_update BEFORE UPDATE ON control_tenant_initializations BEGIN SELECT RAISE(ABORT,'initialization is immutable'); END;
-- statement
CREATE TRIGGER control_tenant_runtime_profiles_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenant_runtime_profiles WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_tenants_lifecycle BEFORE UPDATE ON control_tenants WHEN NEW.created_at<>OLD.created_at OR NEW.updated_at<OLD.updated_at OR (NEW.status<>OLD.status AND NOT ((OLD.status='PROVISIONING' AND NEW.status='ACTIVE') OR (OLD.status='ACTIVE' AND NEW.status='SUSPENDED') OR (OLD.status='SUSPENDED' AND NEW.status='ACTIVE'))) BEGIN SELECT RAISE(ABORT,'invalid tenant lifecycle transition'); END;
-- statement
CREATE TRIGGER control_tenants_no_delete BEFORE DELETE ON control_tenants BEGIN SELECT RAISE(ABORT,'tenant retention requires explicit offboarding'); END;
-- statement
CREATE TRIGGER control_tenants_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tenants WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_tool_host_dispatches_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tool_host_dispatches WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_tool_hosts_tenant_immutable BEFORE UPDATE OF tenant_id ON control_tool_hosts WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_vault_key_guard BEFORE UPDATE ON control_tenant_vault_keys WHEN NEW.version<>OLD.version OR NEW.tenant_id<>OLD.tenant_id OR OLD.state='RETIRED' OR (NEW.state<>OLD.state AND NOT ((OLD.state='ACTIVE' AND NEW.state='READ_ONLY') OR (OLD.state='READ_ONLY' AND NEW.state='RETIRED'))) OR (NEW.state='RETIRED' AND EXISTS(SELECT 1 FROM control_secrets WHERE tenant_id=OLD.tenant_id AND key_version=OLD.version)) BEGIN SELECT RAISE(ABORT,'invalid vault key transition'); END;
-- statement
CREATE TRIGGER control_vault_key_no_delete BEFORE DELETE ON control_tenant_vault_keys BEGIN SELECT RAISE(ABORT,'vault key retention requires explicit offboarding'); END;
-- statement
CREATE TRIGGER effect_evidence_delete BEFORE DELETE ON control_effect_evidence BEGIN SELECT RAISE(ABORT,'effect evidence is immutable'); END;
-- statement
CREATE TRIGGER effect_evidence_update BEFORE UPDATE ON control_effect_evidence BEGIN SELECT RAISE(ABORT,'effect evidence is immutable'); END;
-- statement
CREATE TRIGGER effect_family_insert_guard BEFORE INSERT ON control_effects WHEN NEW.current_generation<>1 BEGIN SELECT RAISE(ABORT,'effect starts at generation one'); END;
-- statement
CREATE TRIGGER effect_generation_delete BEFORE DELETE ON control_effect_generations BEGIN SELECT RAISE(ABORT,'effect history is retained'); END;
-- statement
CREATE TRIGGER effect_generation_update BEFORE UPDATE ON control_effect_generations WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.generation<>OLD.generation OR NEW.operation_id<>OLD.operation_id OR NEW.attempt_id<>OLD.attempt_id OR NEW.auth_nonce<>OLD.auth_nonce OR NEW.device_id<>OLD.device_id OR NEW.broker_id<>OLD.broker_id OR NEW.handler_id<>OLD.handler_id OR NEW.issued_at<>OLD.issued_at OR NEW.expires_at<>OLD.expires_at OR NEW.outcome_deadline<>OLD.outcome_deadline OR NEW.revision<>OLD.revision+1 OR (OLD.consumer IS NOT NULL AND (NEW.consumer IS NOT OLD.consumer OR NEW.claim_token IS NOT OLD.claim_token)) OR (OLD.consumer IS NULL AND NEW.consumer IS NOT NULL AND NOT((OLD.state IN('AUTHORIZED','SENDING') AND NEW.state='CONSUMED') OR (OLD.state IN('AUTHORIZED','SENDING','AMBIGUOUS') AND (NEW.state='FAILED' AND NEW.consumer LIKE 'TARGET:%' AND EXISTS(
 SELECT 1 FROM control_effect_evidence proof WHERE proof.tenant_id=OLD.tenant_id AND proof.effect_id=OLD.effect_id
 AND proof.generation=OLD.generation AND proof.source='TARGET:'||NEW.consumer AND proof.outcome='FAILED'
 AND proof.result_digest='sha256:b775353541fcdd3e2ef3d940fbae7961712380d2390c74852a52e12487d8e7f2'))))) OR NOT((NEW.state=OLD.state) OR
 (OLD.state='AUTHORIZED' AND NEW.state IN('RELEASED','SENDING','CONSUMED','AMBIGUOUS')) OR
 (OLD.state='SENDING' AND NEW.state IN('CONSUMED','AMBIGUOUS')) OR
 (OLD.state IN('AUTHORIZED','SENDING') AND (NEW.state='FAILED' AND NEW.consumer LIKE 'TARGET:%' AND EXISTS(
 SELECT 1 FROM control_effect_evidence proof WHERE proof.tenant_id=OLD.tenant_id AND proof.effect_id=OLD.effect_id
 AND proof.generation=OLD.generation AND proof.source='TARGET:'||NEW.consumer AND proof.outcome='FAILED'
 AND proof.result_digest='sha256:b775353541fcdd3e2ef3d940fbae7961712380d2390c74852a52e12487d8e7f2'))) OR
 (OLD.state='CONSUMED' AND NEW.state IN('COMPLETED','FAILED','AMBIGUOUS')) OR
 (OLD.state='AMBIGUOUS' AND NEW.state IN('COMPLETED','FAILED')) OR
 (OLD.state IN('COMPLETED','FAILED') AND NEW.state='CONFLICT')) BEGIN SELECT RAISE(ABORT,'invalid effect identity or transition'); END;
-- statement
CREATE TRIGGER effect_identity_delete BEFORE DELETE ON control_effects BEGIN SELECT RAISE(ABORT,'effect history is retained'); END;
-- statement
CREATE TRIGGER effect_identity_update BEFORE UPDATE ON control_effects WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.operation_id<>OLD.operation_id OR NEW.route_id<>OLD.route_id OR NEW.request_digest<>OLD.request_digest OR NEW.current_generation<>OLD.current_generation+1 OR NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=OLD.tenant_id AND g.effect_id=OLD.effect_id AND g.generation=OLD.current_generation AND (g.state='RELEASED' OR (g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id
 WHERE r.tenant_id=g.tenant_id AND r.operation_id=g.operation_id AND r.proof='TARGET_DEDUPLICATION'
 AND r.to_revision<=i.revision AND i.state='EXECUTING' AND json_extract(i.document,'$.reservedNonce')<>g.attempt_id
 AND EXISTS(SELECT 1 FROM json_each(r.document,'$.targetPlan') p WHERE json_extract(p.value,'$.effectId')=g.effect_id AND json_extract(p.value,'$.generation')=g.generation))))) BEGIN SELECT RAISE(ABORT,'invalid effect identity or transition'); END;
-- statement
CREATE TRIGGER effect_insert_guard BEFORE INSERT ON control_effect_generations WHEN NEW.state<>'AUTHORIZED' OR NEW.revision<>1 OR NEW.consumer IS NOT NULL OR NOT EXISTS(SELECT 1 FROM control_effects e WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.operation_id=NEW.operation_id AND e.current_generation=NEW.generation) BEGIN SELECT RAISE(ABORT,'generation must be current and new'); END;
-- statement
CREATE TRIGGER effect_intent_capacity AFTER INSERT ON control_effect_intents
 BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB))
 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER effect_intent_guard BEFORE INSERT ON control_effect_intents
 WHEN NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g
 ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.request_digest=NEW.request_digest
 AND g.state='AUTHORIZED' AND g.issued_at=NEW.recorded_at)
 BEGIN SELECT RAISE(ABORT,'intent requires matching fresh effect authority'); END;
-- statement
CREATE TRIGGER effect_intent_immutable_delete BEFORE DELETE ON control_effect_intents BEGIN SELECT RAISE(ABORT,'effect intent is immutable'); END;
-- statement
CREATE TRIGGER effect_intent_immutable_update BEFORE UPDATE ON control_effect_intents BEGIN SELECT RAISE(ABORT,'effect intent is immutable'); END;
-- statement
CREATE TRIGGER effect_manifest_guard BEFORE INSERT ON control_effect_generations
 WHEN NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND json_extract(i.document,'$.reservedNonce')=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND (i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) OR (i.authorization_epoch<(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.authorization_epoch>i.authorization_epoch AND k.authorization_epoch<=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id))=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)-i.authorization_epoch))
 AND NOT EXISTS(SELECT 1 FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.event_kind='KILL' AND k.mode='TERMINATE'
 AND NOT EXISTS(SELECT 1 FROM control_kill_supersessions x WHERE x.tenant_id=k.tenant_id AND x.kill_event_id=k.event_id)
 AND (k.scope_kind='TENANT' OR NOT EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id)
 OR EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id AND ((k.scope_kind='PACKAGE' AND (s.package_id IS NULL OR s.package_id=k.scope_value)) OR (k.scope_kind='PACKAGE_DIGEST' AND (s.package_digest IS NULL OR s.package_digest=k.scope_value)) OR (k.scope_kind='PUBLISHER' AND (s.publisher_id IS NULL OR s.publisher_id=k.scope_value)) OR (k.scope_kind='TOOL' AND (s.tool_id IS NULL OR s.tool_id=k.scope_value)) OR (k.scope_kind='POOL' AND (s.pool_id IS NULL OR s.pool_id=k.scope_value)) OR (k.scope_kind='RUNTIME_IMAGE' AND (s.image_digest IS NULL OR s.image_digest=k.scope_value))))))
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),0)) BEGIN SELECT RAISE(ABORT,'EFFECT_MANIFEST_SEALED or inactive execution authority'); END;
-- statement
CREATE TRIGGER effect_manifest_member AFTER INSERT ON control_effect_generations BEGIN
 INSERT INTO control_attempt_effects(tenant_id,operation_id,attempt_id,effect_id)
 VALUES(NEW.tenant_id,NEW.operation_id,NEW.attempt_id,NEW.effect_id) ON CONFLICT DO NOTHING; END;
-- statement
CREATE TRIGGER effect_manifest_transition_guard BEFORE UPDATE ON control_effect_generations
 WHEN OLD.state<>NEW.state AND OLD.state IN('AUTHORIZED','SENDING') AND NEW.state IN('SENDING','CONSUMED')
 AND NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND json_extract(i.document,'$.reservedNonce')=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND (i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) OR (i.authorization_epoch<(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.authorization_epoch>i.authorization_epoch AND k.authorization_epoch<=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id))=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)-i.authorization_epoch))
 AND NOT EXISTS(SELECT 1 FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.event_kind='KILL' AND k.mode='TERMINATE'
 AND NOT EXISTS(SELECT 1 FROM control_kill_supersessions x WHERE x.tenant_id=k.tenant_id AND x.kill_event_id=k.event_id)
 AND (k.scope_kind='TENANT' OR NOT EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id)
 OR EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id AND ((k.scope_kind='PACKAGE' AND (s.package_id IS NULL OR s.package_id=k.scope_value)) OR (k.scope_kind='PACKAGE_DIGEST' AND (s.package_digest IS NULL OR s.package_digest=k.scope_value)) OR (k.scope_kind='PUBLISHER' AND (s.publisher_id IS NULL OR s.publisher_id=k.scope_value)) OR (k.scope_kind='TOOL' AND (s.tool_id IS NULL OR s.tool_id=k.scope_value)) OR (k.scope_kind='POOL' AND (s.pool_id IS NULL OR s.pool_id=k.scope_value)) OR (k.scope_kind='RUNTIME_IMAGE' AND (s.image_digest IS NULL OR s.image_digest=k.scope_value))))))
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),0))
 BEGIN SELECT RAISE(ABORT,'EFFECT_MANIFEST_SEALED or inactive execution authority'); END;
-- statement
CREATE TRIGGER effect_target_binding_capacity AFTER INSERT ON control_effect_target_bindings BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,
 stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB))+length(NEW.target_key)+length(NEW.header)
 WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END;
END;
-- statement
CREATE TRIGGER effect_target_binding_delete BEFORE DELETE ON control_effect_target_bindings
 BEGIN SELECT RAISE(ABORT,'target effect binding is retained');END;
-- statement
CREATE TRIGGER effect_target_binding_insert BEFORE INSERT ON control_effect_target_bindings
 WHEN NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g
 ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=1
 WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.operation_id=NEW.operation_id
 AND e.route_id=NEW.route_id AND e.current_generation=1 AND g.state='AUTHORIZED'
 AND g.issued_at=NEW.first_authorized_at)
 OR json_extract(NEW.document,'$.header') IS NOT NEW.header
 OR json_extract(NEW.document,'$.retentionMs') IS NULL OR json_extract(NEW.document,'$.reviewExpiresAtUnixMs') IS NULL
 OR NEW.valid_until>min(NEW.first_authorized_at+json_extract(NEW.document,'$.retentionMs')
 -max(3600000,(json_extract(NEW.document,'$.retentionMs')+9)/10),
 json_extract(NEW.document,'$.reviewExpiresAtUnixMs'))
 BEGIN SELECT RAISE(ABORT,'target key requires first immutable effect authorization');END;
-- statement
CREATE TRIGGER effect_target_binding_update BEFORE UPDATE ON control_effect_target_bindings
 BEGIN SELECT RAISE(ABORT,'target effect binding is immutable');END;
-- statement
CREATE TRIGGER execution_reconciliation_consume_guard BEFORE UPDATE ON control_enterprise_nonces
 WHEN NEW.purpose='EXECUTION' AND OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND (EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED')
 OR EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.invocation_id AND g.state='CONFLICT'))
 BEGIN SELECT RAISE(ABORT,'reconciliation fences new execution authority'); END;
-- statement
CREATE TRIGGER execution_reconciliation_insert_guard BEFORE INSERT ON control_enterprise_nonces
 WHEN NEW.purpose='EXECUTION' AND (EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED')
 OR EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.invocation_id AND g.state='CONFLICT'))
 BEGIN SELECT RAISE(ABORT,'reconciliation fences new execution authority'); END;
-- statement
CREATE TRIGGER execution_report_qualification_guard BEFORE INSERT ON control_execution_evidence
 WHEN NEW.qualified_report=1 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n
 JOIN control_attempt_recovery_scopes s ON s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
 JOIN control_enterprise_invocations i ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id
 JOIN control_endpoints ep ON ep.tenant_id=s.tenant_id AND ep.device_id=s.device_id
 JOIN control_records d ON d.tenant_id=s.tenant_id AND d.kind='DEVICE' AND d.record_id=s.device_id
 WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.nonce=NEW.fencing_token
 AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.recorded_at
 AND s.device_id=json_extract(i.document,'$.evaluation.context.deviceId')
 AND json_extract(ep.document,'$.state')='ACTIVE' AND json_extract(d.document,'$.enabled')=1)
 BEGIN SELECT RAISE(ABORT,'qualified report requires independent current admission trust'); END;
-- statement
CREATE TRIGGER invocation_recovery_guard BEFORE UPDATE ON control_enterprise_invocations WHEN OLD.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN','CANCELLED','SUCCEEDED','PARTIAL') AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') AND NOT(NEW.state='QUEUED' AND NEW.revision=OLD.revision+1 AND NEW.tenant_id=OLD.tenant_id AND NEW.invocation_id=OLD.invocation_id AND NEW.request_digest=OLD.request_digest AND NEW.created_at=OLD.created_at AND NEW.expires_at=OLD.expires_at AND NEW.authorization_epoch=OLD.authorization_epoch
 AND json_extract(NEW.document,'$.state')=NEW.state AND json_extract(NEW.document,'$.revision')=NEW.revision AND json_extract(NEW.document,'$.expiresAtUnixMs')=NEW.expires_at
 AND json_extract(NEW.document,'$.evaluation') IS json_extract(OLD.document,'$.evaluation')
 AND json_extract(NEW.document,'$.reservedNonce') IS NULL AND json_extract(NEW.document,'$.resultDigest') IS NULL
 AND json_extract(NEW.document,'$.completedResources')='[]'
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id
 AND r.operation_id=OLD.invocation_id AND r.from_revision=OLD.revision AND r.to_revision=NEW.revision
 AND r.original_deadline=OLD.expires_at AND json_extract(r.document,'$.invocation')=OLD.document)) BEGIN SELECT RAISE(ABORT,'terminal invocation requires positive recovery proof'); END;
-- statement
CREATE TRIGGER operation_outcome_capacity AFTER INSERT ON control_operation_outcomes BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER operation_outcome_delete_immutable BEFORE DELETE ON control_operation_outcomes BEGIN SELECT RAISE(ABORT,'derived outcome is immutable'); END;
-- statement
CREATE TRIGGER operation_outcome_guard BEFORE INSERT ON control_operation_outcomes WHEN NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.revision=NEW.invocation_revision)
 AND NEW.revision=COALESCE((SELECT max(p.revision) FROM control_operation_outcomes p WHERE p.tenant_id=NEW.tenant_id AND p.operation_id=NEW.operation_id),0)+1
 AND json_extract(NEW.document,'$.operationId')=NEW.operation_id AND json_extract(NEW.document,'$.state')=NEW.state
 AND json_extract(NEW.document,'$.revision')=NEW.revision AND json_extract(NEW.document,'$.updatedAtUnixMs')=NEW.recorded_at
 AND json_type(NEW.document,'$.retryable')='false'
 AND ((NEW.state NOT IN('SUCCEEDED','PARTIAL','FAILED') AND json_type(NEW.document,'$.finalized')='false')
 OR (NEW.state IN('SUCCEEDED','PARTIAL','FAILED') AND json_type(NEW.document,'$.finalized')='true'
 AND json_type(NEW.document,'$.journalComplete')='true' AND json_extract(NEW.document,'$.unresolvedEffectCount')=0 AND json_extract(NEW.document,'$.unsettledAttemptCount')=0 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND NOT o.legacy
 AND i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING')
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce
 WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE'
 AND (n.purpose<>'EXECUTION' OR a.attempt_id IS NULL OR a.operation_id<>n.invocation_id OR a.evaluation_digest<>n.evaluation_digest
 OR a.authorization_epoch<>n.authorization_epoch OR a.issued_at<>n.issued_at OR a.expires_at<>n.expires_at
 OR (n.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce) AND NOT EXISTS(SELECT 1 FROM control_attempt_reviews q WHERE q.tenant_id=n.tenant_id AND q.operation_id=n.invocation_id AND q.attempt_id=n.nonce))))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND
 ((g.state NOT IN('RELEASED','COMPLETED','FAILED') AND NOT(g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_effect_target_bindings b
 JOIN control_effect_intents intent ON intent.tenant_id=b.tenant_id AND intent.effect_id=b.effect_id AND intent.profile_digest=b.profile_digest
 JOIN control_effect_evidence witness ON witness.tenant_id=b.tenant_id AND witness.effect_id=b.effect_id
 WHERE b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id AND witness.generation=g.generation
 AND json_extract(b.document,'$.statusRouteId') IS NOT NULL AND substr(witness.source,1,14)='TARGET_LOOKUP:' AND length(witness.source)=78
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence other WHERE other.tenant_id=witness.tenant_id AND other.effect_id=witness.effect_id
 AND other.generation=witness.generation AND substr(other.source,1,14)='TARGET_LOOKUP:'
 AND (other.source<>witness.source OR other.outcome<>witness.outcome OR other.result_digest<>witness.result_digest))))) OR (g.state IN('COMPLETED','FAILED') AND NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation
 AND e.source='TARGET:'||g.consumer AND e.outcome=g.state))))
 AND (NEW.state<>'SUCCEEDED' OR EXISTS(SELECT 1 FROM control_attempt_snapshots h
 WHERE h.tenant_id=i.tenant_id AND h.operation_id=i.invocation_id
 AND (EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=h.tenant_id AND s.operation_id=h.operation_id AND s.attempt_id=h.attempt_id)
 OR EXISTS(SELECT 1 FROM control_attempt_reviews q WHERE q.tenant_id=h.tenant_id AND q.operation_id=h.operation_id AND q.attempt_id=h.attempt_id)) AND (h.state='SUCCEEDED' OR EXISTS(SELECT 1 FROM control_execution_evidence e WHERE e.tenant_id=h.tenant_id
 AND e.invocation_id=h.operation_id AND e.fencing_token=h.attempt_id AND e.qualified_report=true
 AND json_extract(e.document,'$.outcome')='COMPLETED' AND json_extract(e.document,'$.result.kind')='RESULT' AND json_extract(e.document,'$.result.resultType')='complete' AND json_type(e.document,'$.result.isError')='false'))
 AND h.revision=(SELECT max(x.revision) FROM control_attempt_snapshots x WHERE x.tenant_id=h.tenant_id AND x.attempt_id=h.attempt_id)))
)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes p WHERE p.tenant_id=NEW.tenant_id AND p.operation_id=NEW.operation_id
 AND p.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=p.tenant_id AND x.operation_id=p.operation_id)
 AND ((p.state='RECONCILIATION_REQUIRED' AND NEW.state<>'RECONCILIATION_REQUIRED')
 OR (p.state IN('SUCCEEDED','PARTIAL','FAILED') AND NEW.state<>p.state AND NEW.state<>'RECONCILIATION_REQUIRED'
 AND NOT(p.state='FAILED' AND NEW.state='RUNNING' AND EXISTS(SELECT 1 FROM control_operation_recoveries r
 WHERE r.tenant_id=p.tenant_id AND r.operation_id=p.operation_id AND r.to_revision>p.invocation_revision AND r.to_revision<=NEW.invocation_revision))))),0))
 BEGIN SELECT RAISE(ABORT,'derived outcome requires complete evidence and immutable finality'); END;
-- statement
CREATE TRIGGER operation_outcome_update_immutable BEFORE UPDATE ON control_operation_outcomes BEGIN SELECT RAISE(ABORT,'derived outcome is immutable'); END;
-- statement
CREATE TRIGGER operation_recovery_capacity AFTER INSERT ON control_operation_recoveries BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM control_tenant_capacity WHERE tenant_id=NEW.tenant_id)
 THEN RAISE(ABORT,'tenant capacity unavailable') END;
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
END;
-- statement
CREATE TRIGGER operation_recovery_delete BEFORE DELETE ON control_operation_recoveries
 BEGIN SELECT RAISE(ABORT,'operation recovery is retained'); END;
-- statement
CREATE TRIGGER operation_recovery_insert BEFORE INSERT ON control_operation_recoveries
 WHEN NOT EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_operations o
 ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id
 AND i.revision=NEW.from_revision AND o.revision=NEW.operation_revision AND o.legacy=0
 AND i.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN') AND o.state IN('FAILED','OUTCOME_UNKNOWN')
 AND (i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id) OR (i.authorization_epoch<(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id) AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.authorization_epoch>i.authorization_epoch AND k.authorization_epoch<=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id))=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id)-i.authorization_epoch))
 AND i.expires_at=NEW.original_deadline AND i.expires_at>NEW.created_at
 AND json_extract(i.document,'$.reservedNonce') IS NEW.previous_nonce
 AND json_extract(NEW.document,'$.invocation')=i.document
 AND json_extract(NEW.document,'$.attemptIds')=json(NEW.attempt_ids)
 AND NOT EXISTS(SELECT 1 FROM json_each(NEW.attempt_ids) j WHERE j.type<>'text')
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_approvals a WHERE a.tenant_id=i.tenant_id AND a.invocation_id=i.invocation_id AND a.approval_type='OPERATION' AND json_extract(a.document,'$.state') IN('DENIED','REVOKED'))
 AND NOT EXISTS(SELECT 1 FROM control_business_keys k WHERE k.tenant_id=i.tenant_id AND k.invocation_id=i.invocation_id
 AND (k.tombstoned_at IS NOT NULL OR k.retain_until<=NEW.created_at))
 AND (json_extract(i.document,'$.reservedNonce') IS NULL OR EXISTS(SELECT 1 FROM control_operation_attempts a
 WHERE a.tenant_id=i.tenant_id AND a.operation_id=i.invocation_id AND a.attempt_id=json_extract(i.document,'$.reservedNonce')))
 AND (SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')<64
 AND (SELECT count(*) FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id)<=4096
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a
 ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id
 AND n.purpose<>'SERVICE' AND (n.purpose<>'EXECUTION' OR NOT(a.attempt_id IS NOT NULL AND a.operation_id=n.invocation_id
 AND a.evaluation_digest=n.evaluation_digest AND a.authorization_epoch=n.authorization_epoch
 AND a.issued_at=n.issued_at AND a.expires_at=n.expires_at)
 OR (n.consumed_at IS NULL AND n.expires_at>NEW.created_at)
 OR (n.consumed_at IS NOT NULL AND n.consumed_at>NEW.created_at)))
 AND (SELECT count(*) FROM json_each(NEW.attempt_ids) AS j)=(SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE'
 AND NOT EXISTS(SELECT 1 FROM json_each(NEW.attempt_ids) AS j WHERE j.value=n.nonce))
 AND (
 (NEW.proof='NEVER_CONSUMED' AND o.state='FAILED'
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id))
 OR (NEW.proof='QUALIFIED_BROKER_NO_WRITE'
 AND EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_attempt_recovery_scopes s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
 AND s.device_id=json_extract(i.document,'$.evaluation.context.deviceId')))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_enterprise_nonces n
 ON n.tenant_id=g.tenant_id AND n.nonce=g.attempt_id
 WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND (g.state<>'RELEASED' OR n.consumed_at IS NULL)))
 OR (NEW.proof='TARGET_DEDUPLICATION'
 AND EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_attempt_recovery_scopes s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
 AND s.device_id=json_extract(i.document,'$.evaluation.context.deviceId')))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_enterprise_nonces n
 ON n.tenant_id=g.tenant_id AND n.nonce=g.attempt_id
 WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND (g.state NOT IN('RELEASED','AMBIGUOUS') OR n.consumed_at IS NULL))
 AND json_type(NEW.document,'$.targetPlan')='array'
 AND (SELECT count(*) FROM json_each(NEW.document,'$.targetPlan') p) BETWEEN 1 AND 256
 AND (SELECT count(*) FROM json_each(NEW.document,'$.targetPlan') p)=(SELECT count(*) FROM control_effects e WHERE e.tenant_id=i.tenant_id AND e.operation_id=i.invocation_id)
 AND EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_effects e ON e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id WHERE e.tenant_id=i.tenant_id AND e.operation_id=i.invocation_id AND g.generation=e.current_generation AND g.state='AMBIGUOUS')
 AND NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE e.tenant_id=i.tenant_id AND e.operation_id=i.invocation_id AND NOT EXISTS(SELECT 1 FROM control_effect_target_bindings b
 WHERE b.tenant_id=e.tenant_id AND b.effect_id=e.effect_id AND b.valid_until>NEW.created_at
 AND EXISTS(SELECT 1 FROM json_each(NEW.document,'$.targetPlan') p JOIN control_configuration_changes c ON c.tenant_id=e.tenant_id AND c.change_id=json_extract(p.value,'$.reviewId')
 WHERE json_extract(p.value,'$.effectId')=e.effect_id AND json_extract(p.value,'$.generation')=e.current_generation AND json_extract(p.value,'$.requestDigest')=e.request_digest
 AND json_extract(p.value,'$.profileDigest')=b.profile_digest AND json_extract(p.value,'$.guaranteeDigest')=b.guarantee_digest
 AND json_extract(c.document,'$.state')='APPLIED' AND json_extract(c.document,'$.requiredReviews')>=1 AND (SELECT count(*) FROM json_each(c.document,'$.reviews') cr WHERE json_extract(cr.value,'$.reviewerUserId')<>json_extract(c.document,'$.requesterUserId'))>=json_extract(c.document,'$.requiredReviews'))))
))
)
 BEGIN SELECT RAISE(ABORT,'positive complete Control recovery proof required'); END;
-- statement
CREATE TRIGGER operation_recovery_update BEFORE UPDATE ON control_operation_recoveries
 BEGIN SELECT RAISE(ABORT,'operation recovery is immutable'); END;
-- statement
CREATE TRIGGER original_deadline_guard BEFORE UPDATE ON control_enterprise_invocations WHEN NEW.expires_at<>OLD.expires_at AND
 (EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=OLD.tenant_id AND a.operation_id=OLD.invocation_id)
 OR EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id))
 BEGIN SELECT RAISE(ABORT,'admitted operation deadline is immutable');END;
-- statement
CREATE TRIGGER reconciliation_delete_guard BEFORE DELETE ON control_outcome_reconciliations
 BEGIN SELECT RAISE(ABORT,'reconciliation history is retained'); END;
-- statement
CREATE TRIGGER reconciliation_insert_guard BEFORE INSERT ON control_outcome_reconciliations WHEN NOT(COALESCE(json_extract(NEW.document,'$.id')=NEW.reconciliation_id AND json_extract(NEW.document,'$.revision')=NEW.revision AND NEW.revision BETWEEN 1 AND 9007199254740991 AND length(json_extract(NEW.document,'$.requesterUserId')) BETWEEN 1 AND 128 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=json_extract(NEW.document,'$.invocationId')) AND NEW.revision=1 AND json_extract(NEW.document,'$.state')='PENDING' AND json_extract(NEW.document,'$.reviewerUserId') IS NULL,0))
 BEGIN SELECT RAISE(ABORT,'reconciliation must begin pending with valid identity'); END;
-- statement
CREATE TRIGGER reconciliation_update_guard BEFORE UPDATE ON control_outcome_reconciliations WHEN NOT(COALESCE(json_extract(NEW.document,'$.id')=NEW.reconciliation_id AND json_extract(NEW.document,'$.revision')=NEW.revision AND NEW.revision BETWEEN 1 AND 9007199254740991 AND length(json_extract(NEW.document,'$.requesterUserId')) BETWEEN 1 AND 128 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=json_extract(NEW.document,'$.invocationId')) AND NEW.tenant_id=OLD.tenant_id AND NEW.reconciliation_id=OLD.reconciliation_id AND NEW.revision=OLD.revision+1 AND json_extract(OLD.document,'$.state')='PENDING' AND json_extract(NEW.document,'$.state') IN('APPLIED','DENIED') AND length(json_extract(NEW.document,'$.reviewerUserId')) BETWEEN 1 AND 128 AND json_extract(NEW.document,'$.id') IS json_extract(OLD.document,'$.id') AND json_extract(NEW.document,'$.invocationId') IS json_extract(OLD.document,'$.invocationId') AND json_extract(NEW.document,'$.requesterUserId') IS json_extract(OLD.document,'$.requesterUserId') AND json_extract(NEW.document,'$.requestDigest') IS json_extract(OLD.document,'$.requestDigest') AND json_extract(NEW.document,'$.request') IS json_extract(OLD.document,'$.request'),0))
 BEGIN SELECT RAISE(ABORT,'reconciliation proposal and decision are immutable'); END;
-- statement
CREATE TRIGGER recovery_scope_capacity AFTER INSERT ON control_attempt_recovery_scopes BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,
 stored_bytes=stored_bytes+length(CAST(NEW.device_id||NEW.pool_id||NEW.image_digest||NEW.runtime_digest||NEW.descriptor_digest||NEW.profile_digest AS BLOB))
 WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END;
END;
-- statement
CREATE TRIGGER recovery_scope_delete BEFORE DELETE ON control_attempt_recovery_scopes
 BEGIN SELECT RAISE(ABORT,'recovery scope is retained'); END;
-- statement
CREATE TRIGGER recovery_scope_insert BEFORE INSERT ON control_attempt_recovery_scopes
 WHEN NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n JOIN control_enterprise_invocations i
 ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id
 WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.operation_id AND n.nonce=NEW.attempt_id
 AND n.purpose='EXECUTION' AND n.consumed_at IS NULL AND i.state='RESERVED'
 AND json_extract(i.document,'$.reservedNonce')=NEW.attempt_id
 AND json_extract(i.document,'$.evaluation.context.deviceId')=NEW.device_id)
 BEGIN SELECT RAISE(ABORT,'recovery scope must bind fresh Control admission'); END;
-- statement
CREATE TRIGGER recovery_scope_update BEFORE UPDATE ON control_attempt_recovery_scopes
 BEGIN SELECT RAISE(ABORT,'recovery scope is immutable'); END;
-- statement
CREATE TRIGGER review_owners_capacity AFTER INSERT ON control_operation_review_owners BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER review_owners_delete_immutable BEFORE DELETE ON control_operation_review_owners BEGIN SELECT RAISE(ABORT,'review owners are immutable'); END;
-- statement
CREATE TRIGGER review_owners_guard BEFORE INSERT ON control_operation_review_owners
 WHEN NOT EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_tenant_authority t ON t.tenant_id=i.tenant_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.revision=1 AND t.revision=NEW.directory_revision AND json_extract(i.document,'$.evaluation.authorityRevision')=NEW.directory_revision
 AND json_extract(NEW.document,'$.format')='toolgate-review-owners-v1' AND json_extract(NEW.document,'$.operationId')=NEW.operation_id
 AND json_type(NEW.document,'$.owners')='array' AND json_array_length(NEW.document,'$.owners') BETWEEN 1 AND 66 AND NOT EXISTS(SELECT 1 FROM json_each(NEW.document,'$.owners') j WHERE j.type<>'text') AND NOT EXISTS(SELECT 1 FROM (SELECT json_extract(i.document,'$.evaluation.context.userId') AS principal WHERE json_extract(i.document,'$.evaluation.context.userId') IS NOT NULL UNION SELECT json_extract(d.document,'$.ownerUserId') FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT json_extract(i.document,'$.evaluation.context.agentId') UNION SELECT json_extract(h.value,'$.agentId') FROM json_each(i.document,'$.evaluation.context.chain') h)) owners WHERE owners.principal IS NULL) AND json_array_length(NEW.document,'$.owners')=(SELECT count(*) FROM (SELECT json_extract(i.document,'$.evaluation.context.userId') AS principal WHERE json_extract(i.document,'$.evaluation.context.userId') IS NOT NULL UNION SELECT json_extract(d.document,'$.ownerUserId') FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT json_extract(i.document,'$.evaluation.context.agentId') UNION SELECT json_extract(h.value,'$.agentId') FROM json_each(i.document,'$.evaluation.context.chain') h)) owners) AND NOT EXISTS(SELECT 1 FROM (SELECT json_extract(i.document,'$.evaluation.context.userId') AS principal WHERE json_extract(i.document,'$.evaluation.context.userId') IS NOT NULL UNION SELECT json_extract(d.document,'$.ownerUserId') FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT json_extract(i.document,'$.evaluation.context.agentId') UNION SELECT json_extract(h.value,'$.agentId') FROM json_each(i.document,'$.evaluation.context.chain') h)) owners WHERE owners.principal NOT IN(SELECT value FROM json_each(NEW.document,'$.owners'))))
 BEGIN SELECT RAISE(ABORT,'review owners require original admission'); END;
-- statement
CREATE TRIGGER review_owners_update_immutable BEFORE UPDATE ON control_operation_review_owners BEGIN SELECT RAISE(ABORT,'review owners are immutable'); END;

-- statement
CREATE TRIGGER lookup_witness_guard BEFORE INSERT ON control_effect_evidence
WHEN substr(NEW.source,1,14)='TARGET_LOOKUP:' AND (
 length(NEW.source)<>78 OR substr(NEW.source,15) GLOB '*[^a-f0-9]*'
 OR NOT EXISTS(SELECT 1 FROM control_effect_generations g
 JOIN control_effect_target_bindings b ON b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id
 JOIN control_enterprise_invocations i ON i.tenant_id=g.tenant_id AND i.invocation_id=g.operation_id
 WHERE g.tenant_id=NEW.tenant_id AND g.effect_id=NEW.effect_id AND g.generation=NEW.generation AND g.state='AMBIGUOUS'
 AND i.state='OUTCOME_UNKNOWN' AND json_extract(b.document,'$.statusRouteId') IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations active WHERE active.tenant_id=g.tenant_id AND active.effect_id=g.effect_id
 AND active.state NOT IN('AMBIGUOUS','RELEASED')))
 OR ((SELECT count(*) FROM control_effect_evidence w WHERE w.tenant_id=NEW.tenant_id AND w.effect_id=NEW.effect_id
 AND w.generation=NEW.generation AND substr(w.source,1,14)='TARGET_LOOKUP:')>=8
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence w WHERE w.tenant_id=NEW.tenant_id AND w.effect_id=NEW.effect_id
 AND w.generation=NEW.generation AND w.evidence_digest=NEW.evidence_digest))
) BEGIN SELECT RAISE(ABORT,'invalid target lookup witness'); END;
-- statement
CREATE TRIGGER lookup_resend_fence BEFORE UPDATE ON control_effects
WHEN NEW.current_generation<>OLD.current_generation AND EXISTS(
 SELECT 1 FROM control_effect_evidence w WHERE w.tenant_id=OLD.tenant_id AND w.effect_id=OLD.effect_id AND substr(w.source,1,14)='TARGET_LOOKUP:')
BEGIN SELECT RAISE(ABORT,'witnessed target outcome fences resend'); END;
-- statement
CREATE TRIGGER lookup_recovery_fence BEFORE INSERT ON control_operation_recoveries
WHEN NEW.proof='TARGET_DEDUPLICATION' AND EXISTS(
 SELECT 1 FROM control_effect_generations g JOIN control_effect_evidence w ON w.tenant_id=g.tenant_id AND w.effect_id=g.effect_id
 WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.operation_id AND substr(w.source,1,14)='TARGET_LOOKUP:')
BEGIN SELECT RAISE(ABORT,'witnessed target outcome fences recovery'); END;

-- statement
-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Current fresh-schema kill storage, mirrored in initial V1.
CREATE TABLE control_kill_events(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),event_id TEXT NOT NULL,issuer TEXT NOT NULL CHECK(issuer IN('control','control-breakglass')),issuer_counter INTEGER NOT NULL CHECK(issuer_counter BETWEEN 1 AND 9999999999999999),event_kind TEXT NOT NULL CHECK(event_kind IN('KILL','RECOVERY')),scope_kind TEXT CHECK(scope_kind IN('PACKAGE','PACKAGE_DIGEST','PUBLISHER','TOOL','POOL','RUNTIME_IMAGE','TENANT')),scope_value TEXT CHECK(length(scope_value) BETWEEN 1 AND 256),mode TEXT CHECK(mode IN('STOP_NEW','TERMINATE')),authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch BETWEEN 1 AND 9007199254740991),issued_at INTEGER NOT NULL CHECK(issued_at BETWEEN 0 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=65536),PRIMARY KEY(tenant_id,event_id),UNIQUE(tenant_id,issuer,issuer_counter),UNIQUE(tenant_id,authorization_epoch),CHECK(event_id=issuer||':'||issuer_counter),CHECK(event_kind='KILL' OR issuer='control'),CHECK((event_kind='KILL')=(scope_kind IS NOT NULL)),CHECK((event_kind='KILL')=(mode IS NOT NULL)),CHECK(scope_kind IS NULL OR (scope_kind='TENANT')=(scope_value IS NULL)));
-- statement
CREATE INDEX control_kill_event_epoch ON control_kill_events(tenant_id,authorization_epoch);
-- statement
CREATE TABLE control_kill_supersessions(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),kill_event_id TEXT NOT NULL,recovery_event_id TEXT NOT NULL,PRIMARY KEY(tenant_id,kill_event_id,recovery_event_id),FOREIGN KEY(tenant_id,kill_event_id) REFERENCES control_kill_events(tenant_id,event_id),FOREIGN KEY(tenant_id,recovery_event_id) REFERENCES control_kill_events(tenant_id,event_id));
-- statement
CREATE TABLE control_kill_archive(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),event_id TEXT NOT NULL,archived_at INTEGER NOT NULL CHECK(archived_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,event_id),FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id));
-- statement
CREATE TABLE control_kill_acks(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),component_kind TEXT NOT NULL CHECK(component_kind IN('TOOL_HOST','ENDPOINT','GATEWAY')),component_id TEXT NOT NULL CHECK(length(component_id) BETWEEN 1 AND 256),acked_epoch INTEGER NOT NULL CHECK(acked_epoch BETWEEN 0 AND 9007199254740991),acked_at INTEGER NOT NULL CHECK(acked_at BETWEEN 0 AND 9007199254740991),delivery_path TEXT NOT NULL CHECK(delivery_path IN('PUSH','CHECK_IN','POLL')),PRIMARY KEY(tenant_id,component_kind,component_id));
-- statement
CREATE TRIGGER control_kill_events_update BEFORE UPDATE ON control_kill_events BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_events_delete BEFORE DELETE ON control_kill_events BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_supersessions_update BEFORE UPDATE ON control_kill_supersessions BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_supersessions_delete BEFORE DELETE ON control_kill_supersessions BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_archive_update BEFORE UPDATE ON control_kill_archive BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_archive_delete BEFORE DELETE ON control_kill_archive BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_supersession_guard BEFORE INSERT ON control_kill_supersessions WHEN NOT EXISTS(
 SELECT 1 FROM control_kill_events k JOIN control_kill_events r ON r.tenant_id=k.tenant_id
 WHERE k.tenant_id=NEW.tenant_id AND k.event_id=NEW.kill_event_id AND k.event_kind='KILL'
 AND r.event_id=NEW.recovery_event_id AND r.event_kind='RECOVERY' AND r.authorization_epoch>k.authorization_epoch
 AND EXISTS(SELECT 1 FROM json_each(r.document,'$.supersedes') s WHERE s.type='text' AND s.value=k.event_id))
 BEGIN SELECT RAISE(ABORT,'a signed later recovery must name its superseded kill'); END;
-- statement
CREATE TRIGGER control_kill_archive_guard BEFORE INSERT ON control_kill_archive WHEN NOT EXISTS(SELECT 1 FROM control_kill_supersessions WHERE tenant_id=NEW.tenant_id AND (kill_event_id=NEW.event_id OR recovery_event_id=NEW.event_id)) BEGIN SELECT RAISE(ABORT,'only superseded kills and applied recoveries are archived'); END;
-- statement
CREATE TRIGGER control_kill_ack_update BEFORE UPDATE ON control_kill_acks WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.component_kind<>OLD.component_kind OR NEW.component_id<>OLD.component_id OR NEW.acked_epoch<OLD.acked_epoch OR NEW.acked_at<OLD.acked_at BEGIN SELECT RAISE(ABORT,'kill acknowledgements are monotonic'); END;
-- statement
CREATE TRIGGER control_kill_ack_delete BEFORE DELETE ON control_kill_acks BEGIN SELECT RAISE(ABORT,'kill acknowledgements are monotonic'); END;
-- statement

CREATE TABLE control_invocation_kill_scopes(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,attempt_id TEXT NOT NULL,device_id TEXT NOT NULL,
 package_id TEXT,package_digest TEXT NOT NULL,tool_id TEXT NOT NULL,
 publisher_id TEXT,pool_id TEXT,image_digest TEXT,
 PRIMARY KEY(tenant_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK(length(operation_id) BETWEEN 1 AND 128),CHECK(length(attempt_id) BETWEEN 1 AND 128),
 CHECK(length(device_id) BETWEEN 1 AND 128),CHECK(length(tool_id) BETWEEN 1 AND 128),
 CHECK(length(package_digest)=71),CHECK(package_id IS NULL OR length(package_id) BETWEEN 1 AND 128),
 CHECK(publisher_id IS NULL OR length(publisher_id) BETWEEN 1 AND 128),
 CHECK(pool_id IS NULL OR length(pool_id)<=128),CHECK(image_digest IS NULL OR length(image_digest) IN(0,71))
);
-- statement
CREATE TRIGGER control_invocation_kill_scopes_update BEFORE UPDATE ON control_invocation_kill_scopes BEGIN SELECT RAISE(ABORT,'execution kill scope is immutable'); END;
-- statement
CREATE TRIGGER control_invocation_kill_scopes_delete BEFORE DELETE ON control_invocation_kill_scopes BEGIN SELECT RAISE(ABORT,'execution kill scope is immutable'); END;
-- statement

CREATE TABLE control_kill_proposals(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,requester_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('KILL','RECOVERY')),authorization_epoch BIGINT NOT NULL CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
 request_digest TEXT NOT NULL CHECK(length(request_digest)=64),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=65536 AND json_valid(document) AND json_type(document)='object'),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 expires_at BIGINT NOT NULL CHECK(expires_at>created_at AND expires_at<=created_at+600000),
 PRIMARY KEY(tenant_id,proposal_id),CHECK(length(proposal_id) BETWEEN 1 AND 128),CHECK(length(requester_id) BETWEEN 1 AND 128)
);
-- statement
CREATE TABLE control_kill_reviews(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,reviewer_id TEXT NOT NULL,
 approved BOOLEAN NOT NULL CHECK(approved IN(0,1)),reviewed_at BIGINT NOT NULL CHECK(reviewed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id,reviewer_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id)
);
-- statement
CREATE TABLE control_kill_closures(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('APPLIED','REJECTED')),
 event_id TEXT,closed_at BIGINT NOT NULL CHECK(closed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id),
 CHECK((state='APPLIED')=(event_id IS NOT NULL))
);
-- statement
CREATE TRIGGER control_kill_proposals_update BEFORE UPDATE ON control_kill_proposals BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_proposals_delete BEFORE DELETE ON control_kill_proposals BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_reviews_update BEFORE UPDATE ON control_kill_reviews BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_reviews_delete BEFORE DELETE ON control_kill_reviews BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_closures_update BEFORE UPDATE ON control_kill_closures BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_closures_delete BEFORE DELETE ON control_kill_closures BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_review_guard BEFORE INSERT ON control_kill_reviews WHEN NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.requester_id<>NEW.reviewer_id AND p.created_at<=NEW.reviewed_at AND p.expires_at>NEW.reviewed_at)
 OR EXISTS(SELECT 1 FROM control_kill_closures c WHERE c.tenant_id=NEW.tenant_id AND c.proposal_id=NEW.proposal_id)
 OR (SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id)>=2 BEGIN SELECT RAISE(ABORT,'independent current kill review required'); END;
-- statement
CREATE TRIGGER control_kill_closure_guard BEFORE INSERT ON control_kill_closures WHEN NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.created_at<=NEW.closed_at AND p.expires_at>NEW.closed_at)
 OR (NEW.state='APPLIED' AND ((SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=true)<>2
 OR NOT EXISTS(SELECT 1 FROM control_kill_events e JOIN control_kill_proposals p ON p.tenant_id=e.tenant_id AND p.kind=e.event_kind
 WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND p.proposal_id=NEW.proposal_id AND e.issuer='control')))
 OR (NEW.state='REJECTED' AND NOT EXISTS(SELECT 1 FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=false)) BEGIN SELECT RAISE(ABORT,'independent current kill review required'); END;
-- statement
CREATE TABLE control_kill_breakglass_requests(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),request_id TEXT NOT NULL,
 request_digest TEXT NOT NULL CHECK(length(request_digest)=64),event_id TEXT NOT NULL,
 certificate_sha256 TEXT NOT NULL CHECK(length(certificate_sha256)=64),
 not_after BIGINT NOT NULL CHECK(not_after BETWEEN 1 AND 9007199254740991),
 applied_at BIGINT NOT NULL CHECK(applied_at>=0 AND applied_at<not_after),
 PRIMARY KEY(tenant_id,request_id),UNIQUE(tenant_id,event_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id),
 CHECK(length(request_id) BETWEEN 1 AND 128)
);
-- statement
CREATE TRIGGER control_kill_breakglass_requests_update BEFORE UPDATE ON control_kill_breakglass_requests BEGIN SELECT RAISE(ABORT,'operator request is immutable'); END;
-- statement
CREATE TRIGGER control_kill_breakglass_requests_delete BEFORE DELETE ON control_kill_breakglass_requests BEGIN SELECT RAISE(ABORT,'operator request is immutable'); END;
-- statement
CREATE TRIGGER control_kill_breakglass_request_guard BEFORE INSERT ON control_kill_breakglass_requests WHEN NOT EXISTS(
 SELECT 1 FROM control_kill_events e WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND e.event_kind='KILL'
 AND e.issuer='control-breakglass' AND e.issued_at=NEW.applied_at)
 BEGIN SELECT RAISE(ABORT,'operator request requires its committed emergency kill'); END;

-- statement
CREATE TABLE control_kill_gateways(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),gateway_id TEXT NOT NULL,
 registered_at BIGINT NOT NULL CHECK(registered_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,gateway_id),CHECK(length(gateway_id)=64)
);
-- statement
CREATE TRIGGER control_kill_gateways_update BEFORE UPDATE ON control_kill_gateways BEGIN SELECT RAISE(ABORT,'gateway revocation identity is immutable'); END;
-- statement
CREATE TRIGGER control_kill_gateways_delete BEFORE DELETE ON control_kill_gateways BEGIN SELECT RAISE(ABORT,'gateway revocation identity is immutable'); END;
-- statement
-- BEGIN TRIGGERS (issue #274, ADR 058): the same tenant-scoped document table as PostgreSQL. Only retention purges delete, and only event, delivery, cursor and ticket kinds.
CREATE TABLE control_triggers(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,kind TEXT NOT NULL CHECK(kind IN('ASSIGNMENT','INSTANCE','DESIRED','LEASE','EVENT','KEY','EPOCH','CHECKPOINT','OUTBOX','ROUTER','LOG','DELIVERY','DUE','EXPIRY','SETTINGS','SUBSCRIPTION','CURSOR','TICKET')),record_id TEXT NOT NULL CHECK(length(record_id) BETWEEN 1 AND 256),revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=131072),PRIMARY KEY(tenant_id,kind,record_id));
-- statement
CREATE TRIGGER control_triggers_delete BEFORE DELETE ON control_triggers WHEN OLD.kind NOT IN('OUTBOX','LOG','EVENT','KEY','DELIVERY','DUE','EXPIRY','CURSOR','TICKET') BEGIN SELECT RAISE(ABORT,'local retention violation'); END;
-- statement
CREATE TRIGGER control_triggers_tenant_immutable BEFORE UPDATE OF tenant_id ON control_triggers WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_triggers_update BEFORE UPDATE ON control_triggers WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.record_id<>OLD.record_id OR NEW.revision<>OLD.revision+1 BEGIN SELECT RAISE(ABORT,'immutable local version'); END;
-- END TRIGGERS

-- statement
CREATE TABLE control_workflow_profile_revisions(tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),profile_id varchar(128) NOT NULL,digest varchar(71) NOT NULL CHECK(length(digest)=71 AND substr(digest,1,7)='sha256:'),document text NOT NULL,key_version bigint NOT NULL CHECK(key_version>=1),state varchar(16) NOT NULL CHECK(state IN('DRAFT','REVIEWED')),reviewed_by varchar(128),CHECK((state='DRAFT' AND reviewed_by IS NULL) OR (state='REVIEWED' AND reviewed_by IS NOT NULL)),CHECK(length(CAST(document AS BLOB))<=393216),FOREIGN KEY(tenant_id,key_version) REFERENCES control_tenant_vault_keys(tenant_id,version),PRIMARY KEY(tenant_id,profile_id,digest));
-- statement
CREATE TRIGGER control_workflow_profile_revisions_tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_profile_revisions WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TABLE control_workflow_profile_heads(tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),profile_id varchar(128) NOT NULL,digest varchar(71) NOT NULL,generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),FOREIGN KEY(tenant_id,profile_id,digest) REFERENCES control_workflow_profile_revisions(tenant_id,profile_id,digest),PRIMARY KEY(tenant_id,profile_id));
-- statement
CREATE TRIGGER control_workflow_profile_heads_tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_profile_heads WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TABLE control_workflow_profile_owners(tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),resource_kind varchar(32) NOT NULL CHECK(resource_kind IN('WORKFLOW_GROUP','MODEL_GROUP','AGENT_DEFINITION','WORKFLOW','DISPATCH','MODEL_DEPLOYMENT','WORKFLOW_VERSION','DISPATCH_SOURCE')),resource_id varchar(128) NOT NULL,profile_id varchar(128) NOT NULL,definition_digest varchar(71),PRIMARY KEY(tenant_id,resource_kind,resource_id));
-- statement
CREATE TRIGGER control_workflow_profile_owners_tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_profile_owners WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER workflow_revision_immutable BEFORE UPDATE ON control_workflow_profile_revisions
WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.profile_id<>OLD.profile_id OR NEW.digest<>OLD.digest OR NEW.document<>OLD.document OR NEW.key_version<>OLD.key_version OR OLD.state<>'DRAFT' OR NEW.state<>'REVIEWED' OR OLD.reviewed_by IS NOT NULL OR NEW.reviewed_by IS NULL
BEGIN SELECT RAISE(ABORT,'immutable workflow profile revision'); END;

-- statement
CREATE TABLE control_workflow_runs(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 run_id varchar(128) NOT NULL, profile_id varchar(128) NOT NULL, profile_digest varchar(71) NOT NULL,
 workflow_id varchar(128) NOT NULL, workflow_version_id varchar(128) NOT NULL,
 service_agent_id varchar(128) NOT NULL, service_agent_group_id varchar(128) NOT NULL,
 remediation_mode varchar(16) NOT NULL CHECK(remediation_mode IN('PREPARE','APPLY')),
 state varchar(24) NOT NULL CHECK(state IN('QUEUED','RUNNING','SUCCEEDED','FAILED','AWAITING_APPROVAL','CANCELLED','EXPIRED')),
 document text NOT NULL CHECK(length(CAST(document AS BLOB))<=65536), created_at bigint NOT NULL CHECK(created_at>=0),
 key_version bigint NOT NULL,
 lease_id varchar(128), lease_owner varchar(128), lease_token_digest varchar(64), lease_expires_at bigint,
 step_kind varchar(16) CHECK(step_kind IN('WORKFLOW','AGENT')), step_subject varchar(128), step_model_group varchar(128), step_token_digest varchar(64),
 CHECK((step_kind IS NULL AND step_subject IS NULL AND step_model_group IS NULL AND step_token_digest IS NULL)
    OR (state='RUNNING' AND step_kind IS NOT NULL AND step_subject IS NOT NULL AND step_model_group IS NOT NULL AND step_token_digest IS NOT NULL)),
 CHECK((state='RUNNING' AND lease_id IS NOT NULL AND lease_owner IS NOT NULL AND lease_token_digest IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR (state<>'RUNNING' AND lease_id IS NULL AND lease_owner IS NULL AND lease_token_digest IS NULL AND lease_expires_at IS NULL)),
 FOREIGN KEY(tenant_id,profile_id,profile_digest) REFERENCES control_workflow_profile_revisions(tenant_id,profile_id,digest),
 FOREIGN KEY(tenant_id,key_version) REFERENCES control_tenant_vault_keys(tenant_id,version),
 PRIMARY KEY(tenant_id,run_id));
-- statement
CREATE INDEX control_workflow_runs_queue ON control_workflow_runs(tenant_id,state,created_at,run_id);
-- statement
CREATE INDEX control_workflow_runs_expiry ON control_workflow_runs(tenant_id,state,lease_expires_at);
-- statement
CREATE TABLE control_workflow_admissions(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 subscription_uid varchar(160) NOT NULL, cursor varchar(16) NOT NULL CHECK(length(cursor)=16),
 owner_id varchar(128) NOT NULL, admission_id varchar(128) NOT NULL,
 disposition varchar(16) NOT NULL CHECK(disposition IN('ADMITTED','FILTERED','REJECTED')),
 run_id varchar(128),
 CHECK((disposition='ADMITTED' AND run_id IS NOT NULL) OR (disposition<>'ADMITTED' AND run_id IS NULL)),
 FOREIGN KEY(tenant_id,run_id) REFERENCES control_workflow_runs(tenant_id,run_id),
 PRIMARY KEY(tenant_id,subscription_uid,cursor), UNIQUE(tenant_id,admission_id), UNIQUE(tenant_id,run_id));
-- statement
CREATE TRIGGER control_workflow_runs_tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_runs WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
CREATE TRIGGER control_workflow_admissions_tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_admissions WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;

-- statement
-- BEGIN TENANT CAPACITY
CREATE TRIGGER control_capacity_seed AFTER INSERT ON control_tenants BEGIN INSERT INTO control_tenant_capacity(tenant_id) VALUES(NEW.tenant_id); END;
-- statement
CREATE TRIGGER control_workflow_runs_capacity_insert AFTER INSERT ON control_workflow_runs BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(CASE WHEN NEW.state IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_runs_capacity_delete AFTER DELETE ON control_workflow_runs BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(CASE WHEN OLD.state IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_runs_capacity_update AFTER UPDATE ON control_workflow_runs BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+((CASE WHEN NEW.state IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END)-(CASE WHEN OLD.state IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_admissions_capacity_insert AFTER INSERT ON control_workflow_admissions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.subscription_uid AS BLOB)),0)+COALESCE(length(CAST(NEW.cursor AS BLOB)),0)+COALESCE(length(CAST(NEW.owner_id AS BLOB)),0)+COALESCE(length(CAST(NEW.admission_id AS BLOB)),0)+COALESCE(length(CAST(NEW.disposition AS BLOB)),0)+COALESCE(length(CAST(NEW.run_id AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_admissions_capacity_delete AFTER DELETE ON control_workflow_admissions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.subscription_uid AS BLOB)),0)+COALESCE(length(CAST(OLD.cursor AS BLOB)),0)+COALESCE(length(CAST(OLD.owner_id AS BLOB)),0)+COALESCE(length(CAST(OLD.admission_id AS BLOB)),0)+COALESCE(length(CAST(OLD.disposition AS BLOB)),0)+COALESCE(length(CAST(OLD.run_id AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_admissions_capacity_update AFTER UPDATE ON control_workflow_admissions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.subscription_uid AS BLOB)),0)+COALESCE(length(CAST(NEW.cursor AS BLOB)),0)+COALESCE(length(CAST(NEW.owner_id AS BLOB)),0)+COALESCE(length(CAST(NEW.admission_id AS BLOB)),0)+COALESCE(length(CAST(NEW.disposition AS BLOB)),0)+COALESCE(length(CAST(NEW.run_id AS BLOB)),0))-(COALESCE(length(CAST(OLD.subscription_uid AS BLOB)),0)+COALESCE(length(CAST(OLD.cursor AS BLOB)),0)+COALESCE(length(CAST(OLD.owner_id AS BLOB)),0)+COALESCE(length(CAST(OLD.admission_id AS BLOB)),0)+COALESCE(length(CAST(OLD.disposition AS BLOB)),0)+COALESCE(length(CAST(OLD.run_id AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_revisions_capacity_insert AFTER INSERT ON control_workflow_profile_revisions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_revisions_capacity_delete AFTER DELETE ON control_workflow_profile_revisions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_revisions_capacity_update AFTER UPDATE ON control_workflow_profile_revisions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_heads_capacity_insert AFTER INSERT ON control_workflow_profile_heads BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.profile_id AS BLOB)),0)+COALESCE(length(CAST(NEW.digest AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_heads_capacity_delete AFTER DELETE ON control_workflow_profile_heads BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.profile_id AS BLOB)),0)+COALESCE(length(CAST(OLD.digest AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_heads_capacity_update AFTER UPDATE ON control_workflow_profile_heads BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.profile_id AS BLOB)),0)+COALESCE(length(CAST(NEW.digest AS BLOB)),0))-(COALESCE(length(CAST(OLD.profile_id AS BLOB)),0)+COALESCE(length(CAST(OLD.digest AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_owners_capacity_insert AFTER INSERT ON control_workflow_profile_owners BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.resource_kind AS BLOB)),0)+COALESCE(length(CAST(NEW.resource_id AS BLOB)),0)+COALESCE(length(CAST(NEW.profile_id AS BLOB)),0)+COALESCE(length(CAST(NEW.definition_digest AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_owners_capacity_delete AFTER DELETE ON control_workflow_profile_owners BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.resource_kind AS BLOB)),0)+COALESCE(length(CAST(OLD.resource_id AS BLOB)),0)+COALESCE(length(CAST(OLD.profile_id AS BLOB)),0)+COALESCE(length(CAST(OLD.definition_digest AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_workflow_profile_owners_capacity_update AFTER UPDATE ON control_workflow_profile_owners BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.resource_kind AS BLOB)),0)+COALESCE(length(CAST(NEW.resource_id AS BLOB)),0)+COALESCE(length(CAST(NEW.profile_id AS BLOB)),0)+COALESCE(length(CAST(NEW.definition_digest AS BLOB)),0))-(COALESCE(length(CAST(OLD.resource_kind AS BLOB)),0)+COALESCE(length(CAST(OLD.resource_id AS BLOB)),0)+COALESCE(length(CAST(OLD.profile_id AS BLOB)),0)+COALESCE(length(CAST(OLD.definition_digest AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_gateways_capacity_insert AFTER INSERT ON control_kill_gateways BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.gateway_id AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_gateways_capacity_delete AFTER DELETE ON control_kill_gateways BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.gateway_id AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_gateways_capacity_update AFTER UPDATE ON control_kill_gateways BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.gateway_id AS BLOB)),0))-(COALESCE(length(CAST(OLD.gateway_id AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_breakglass_requests_capacity_insert AFTER INSERT ON control_kill_breakglass_requests BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.request_id AS BLOB)),0)+COALESCE(length(CAST(NEW.request_digest AS BLOB)),0)+COALESCE(length(CAST(NEW.event_id AS BLOB)),0)+COALESCE(length(CAST(NEW.certificate_sha256 AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_breakglass_requests_capacity_delete AFTER DELETE ON control_kill_breakglass_requests BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.request_id AS BLOB)),0)+COALESCE(length(CAST(OLD.request_digest AS BLOB)),0)+COALESCE(length(CAST(OLD.event_id AS BLOB)),0)+COALESCE(length(CAST(OLD.certificate_sha256 AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_breakglass_requests_capacity_update AFTER UPDATE ON control_kill_breakglass_requests BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.request_id AS BLOB)),0)+COALESCE(length(CAST(NEW.request_digest AS BLOB)),0)+COALESCE(length(CAST(NEW.event_id AS BLOB)),0)+COALESCE(length(CAST(NEW.certificate_sha256 AS BLOB)),0))-(COALESCE(length(CAST(OLD.request_id AS BLOB)),0)+COALESCE(length(CAST(OLD.request_digest AS BLOB)),0)+COALESCE(length(CAST(OLD.event_id AS BLOB)),0)+COALESCE(length(CAST(OLD.certificate_sha256 AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_proposals_capacity_insert AFTER INSERT ON control_kill_proposals BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_proposals_capacity_delete AFTER DELETE ON control_kill_proposals BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_proposals_capacity_update AFTER UPDATE ON control_kill_proposals BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_reviews_capacity_insert AFTER INSERT ON control_kill_reviews BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.reviewer_id AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_reviews_capacity_delete AFTER DELETE ON control_kill_reviews BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.reviewer_id AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_reviews_capacity_update AFTER UPDATE ON control_kill_reviews BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.reviewer_id AS BLOB)),0))-(COALESCE(length(CAST(OLD.reviewer_id AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_closures_capacity_insert AFTER INSERT ON control_kill_closures BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.event_id AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_closures_capacity_delete AFTER DELETE ON control_kill_closures BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.event_id AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_closures_capacity_update AFTER UPDATE ON control_kill_closures BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.event_id AS BLOB)),0))-(COALESCE(length(CAST(OLD.event_id AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_kill_scopes_capacity_insert AFTER INSERT ON control_invocation_kill_scopes BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.tool_id AS BLOB)),0)+COALESCE(length(CAST(NEW.package_digest AS BLOB)),0)+COALESCE(length(CAST(NEW.publisher_id AS BLOB)),0)+COALESCE(length(CAST(NEW.pool_id AS BLOB)),0)+COALESCE(length(CAST(NEW.image_digest AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_kill_scopes_capacity_delete AFTER DELETE ON control_invocation_kill_scopes BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.tool_id AS BLOB)),0)+COALESCE(length(CAST(OLD.package_digest AS BLOB)),0)+COALESCE(length(CAST(OLD.publisher_id AS BLOB)),0)+COALESCE(length(CAST(OLD.pool_id AS BLOB)),0)+COALESCE(length(CAST(OLD.image_digest AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_invocation_kill_scopes_capacity_update AFTER UPDATE ON control_invocation_kill_scopes BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.tool_id AS BLOB)),0)+COALESCE(length(CAST(NEW.package_digest AS BLOB)),0)+COALESCE(length(CAST(NEW.publisher_id AS BLOB)),0)+COALESCE(length(CAST(NEW.pool_id AS BLOB)),0)+COALESCE(length(CAST(NEW.image_digest AS BLOB)),0))-(COALESCE(length(CAST(OLD.tool_id AS BLOB)),0)+COALESCE(length(CAST(OLD.package_digest AS BLOB)),0)+COALESCE(length(CAST(OLD.publisher_id AS BLOB)),0)+COALESCE(length(CAST(OLD.pool_id AS BLOB)),0)+COALESCE(length(CAST(OLD.image_digest AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_events_capacity_insert AFTER INSERT ON control_kill_events BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_events_capacity_delete AFTER DELETE ON control_kill_events BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_events_capacity_update AFTER UPDATE ON control_kill_events BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_supersessions_capacity_insert AFTER INSERT ON control_kill_supersessions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.kill_event_id AS BLOB)),0)+COALESCE(length(CAST(NEW.recovery_event_id AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_supersessions_capacity_delete AFTER DELETE ON control_kill_supersessions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.kill_event_id AS BLOB)),0)+COALESCE(length(CAST(OLD.recovery_event_id AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_supersessions_capacity_update AFTER UPDATE ON control_kill_supersessions BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.kill_event_id AS BLOB)),0)+COALESCE(length(CAST(NEW.recovery_event_id AS BLOB)),0))-(COALESCE(length(CAST(OLD.kill_event_id AS BLOB)),0)+COALESCE(length(CAST(OLD.recovery_event_id AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_archive_capacity_insert AFTER INSERT ON control_kill_archive BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.event_id AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_archive_capacity_delete AFTER DELETE ON control_kill_archive BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.event_id AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_archive_capacity_update AFTER UPDATE ON control_kill_archive BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.event_id AS BLOB)),0))-(COALESCE(length(CAST(OLD.event_id AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_acks_capacity_insert AFTER INSERT ON control_kill_acks BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.component_id AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_acks_capacity_delete AFTER DELETE ON control_kill_acks BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.component_id AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_kill_acks_capacity_update AFTER UPDATE ON control_kill_acks BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.component_id AS BLOB)),0))-(COALESCE(length(CAST(OLD.component_id AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.content AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.content AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.cipher AS BLOB)),0))-(COALESCE(length(CAST(OLD.cipher AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.payload AS BLOB)),0))-(COALESCE(length(CAST(OLD.payload AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
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
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.policy AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.policy AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_triggers_capacity_insert AFTER INSERT ON control_triggers BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_triggers_capacity_delete AFTER DELETE ON control_triggers BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_triggers_capacity_update AFTER UPDATE ON control_triggers BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_audit_anchors_capacity_insert AFTER INSERT ON control_audit_anchors BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.signature AS BLOB)),0)),stored_rows=stored_rows+(1),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_audit_anchors_capacity_delete AFTER DELETE ON control_audit_anchors BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+(-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.signature AS BLOB)),0))),stored_rows=stored_rows+(-1),queued_rows=queued_rows+(-(0)) WHERE tenant_id=OLD.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- statement
CREATE TRIGGER control_audit_anchors_capacity_update AFTER UPDATE ON control_audit_anchors BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+((COALESCE(length(CAST(NEW.document AS BLOB)),0)+COALESCE(length(CAST(NEW.signature AS BLOB)),0))-(COALESCE(length(CAST(OLD.document AS BLOB)),0)+COALESCE(length(CAST(OLD.signature AS BLOB)),0))),stored_rows=stored_rows+(0),queued_rows=queued_rows+(0) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;
-- END TENANT CAPACITY
