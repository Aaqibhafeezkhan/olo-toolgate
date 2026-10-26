-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Initial release schema. Initialize an empty database; pre-release schemas are unsupported.
-- Provision the NOLOGIN roles toolgate_control_runtime and toolgate_control_platform before initialization.

CREATE TABLE control_records (
    tenant_id varchar(128) NOT NULL,
    kind varchar(32) NOT NULL CHECK (kind IN ('USER','TEAM','AGENT','TOOL','POLICY','DEVICE','ROLE','DEVICE_GROUP','AGENT_GROUP','TOOL_GROUP','GRANT','DELEGATION','AGENT_DELEGATION','BINDING','EXTRACTOR','WORKLOAD_BINDING','IDENTITY_BINDING','DEVICE_EVIDENCE')),
    record_id varchar(128) NOT NULL,
    revision bigint NOT NULL CHECK (revision BETWEEN 1 AND 9007199254740991),
    document jsonb NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    PRIMARY KEY (tenant_id, kind, record_id),
    CHECK (document->>'id' = record_id AND (document->>'revision')::bigint = revision)
);

CREATE TABLE control_audit (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    actor_id char(64) NOT NULL,
    operation varchar(40) NOT NULL CHECK(operation ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    target varchar(256) NOT NULL,
    revision bigint NOT NULL,
    request_id varchar(128) NOT NULL,
    request_digest char(64) NOT NULL,
    evidence text CHECK (evidence IS NULL OR octet_length(evidence) <= 4096),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX control_audit_tenant_cursor ON control_audit (tenant_id, sequence);

GRANT USAGE ON SCHEMA public TO toolgate_control_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON control_records TO toolgate_control_runtime;

GRANT SELECT, INSERT ON control_audit TO toolgate_control_runtime;

GRANT USAGE ON SEQUENCE control_audit_sequence_seq TO toolgate_control_runtime;

CREATE TABLE control_tenants (
    tenant_id varchar(128) PRIMARY KEY CHECK(tenant_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    alias varchar(63) NOT NULL UNIQUE CHECK(alias ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    display_name text NOT NULL CHECK(length(display_name) BETWEEN 1 AND 256),
    status varchar(16) NOT NULL CHECK(status IN ('PROVISIONING','ACTIVE','SUSPENDED')),
    created_at bigint NOT NULL CHECK(created_at>=0),
    updated_at bigint NOT NULL CHECK(updated_at>=created_at),
    initialization_failure_code varchar(32) CHECK(initialization_failure_code='INITIALIZATION_FAILED'),
    initialization_failed_at bigint CHECK(initialization_failed_at>=0),
    CHECK((initialization_failure_code IS NULL)=(initialization_failed_at IS NULL)),
    CHECK(status='PROVISIONING' OR initialization_failure_code IS NULL)
);

CREATE TABLE control_tenant_authority (
    tenant_id varchar(128) PRIMARY KEY REFERENCES control_tenants(tenant_id),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
    authorization_epoch bigint NOT NULL DEFAULT 0 CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
    directory_cache_generation bigint NOT NULL DEFAULT 0 CHECK(directory_cache_generation>=0)
);

CREATE TABLE control_tenant_hosts (
    hostname varchar(253) PRIMARY KEY CHECK(hostname=lower(hostname) AND length(hostname)>0),
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id)
);
CREATE INDEX control_tenant_hosts_owner ON control_tenant_hosts(tenant_id);
CREATE TABLE control_tenant_identity_keys(
    tenant_id varchar(128) NOT NULL,
    purpose varchar(16) NOT NULL CHECK(purpose IN ('DISCOVERY','DEVICE_CA')),
    key_id varchar(128) NOT NULL CHECK(length(key_id) BETWEEN 1 AND 128),
    state varchar(16) NOT NULL CHECK(state IN ('ACTIVE','READ_ONLY','RETIRED')),
    provider_reference varchar(512) NOT NULL,
    public_material text NOT NULL CHECK(octet_length(public_material)<=16384),
    key_fingerprint varchar(64) NOT NULL UNIQUE CHECK(key_fingerprint ~ '^[a-f0-9]{64}$'),
    generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),
    PRIMARY KEY(tenant_id,purpose,key_id)
);
CREATE UNIQUE INDEX control_tenant_identity_keys_active ON control_tenant_identity_keys(tenant_id,purpose) WHERE state='ACTIVE';
CREATE FUNCTION control_tenant_identity_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'identity key retention requires explicit offboarding'; END IF;
    IF NEW.tenant_id<>OLD.tenant_id OR NEW.purpose<>OLD.purpose OR NEW.key_id<>OLD.key_id OR
       NEW.provider_reference<>OLD.provider_reference OR NEW.public_material<>OLD.public_material OR NEW.key_fingerprint<>OLD.key_fingerprint OR
       NEW.generation<OLD.generation OR (OLD.state='RETIRED' AND NEW.state<>'RETIRED') THEN RAISE EXCEPTION 'invalid identity key transition'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_tenant_identity_key_guard BEFORE UPDATE OR DELETE ON control_tenant_identity_keys FOR EACH ROW EXECUTE FUNCTION control_tenant_identity_key_guard();
GRANT SELECT ON control_tenant_identity_keys TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON control_tenant_identity_keys TO toolgate_control_platform;

CREATE TABLE control_tenant_runtime_profiles(
    tenant_id varchar(128) NOT NULL PRIMARY KEY,
    server_id varchar(128) NOT NULL,
    organization text NOT NULL CHECK(length(organization) BETWEEN 1 AND 256),
    control_url text NOT NULL CHECK(octet_length(control_url)<=2048),
    gateway_url text NOT NULL CHECK(octet_length(gateway_url)<=2048)
);
GRANT SELECT ON control_tenant_runtime_profiles TO toolgate_control_runtime;
GRANT SELECT,INSERT ON control_tenant_runtime_profiles TO toolgate_control_platform;
GRANT SELECT,INSERT,UPDATE,DELETE ON control_tenant_hosts TO toolgate_control_platform;

CREATE TABLE control_idempotency (
    tenant_id varchar(128) NOT NULL,
    actor_id char(64) NOT NULL,
    key varchar(128) NOT NULL,
    request_digest char(64) NOT NULL,
    status integer NOT NULL,
    body text NOT NULL,
    revision bigint NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '7 days'),
    PRIMARY KEY (tenant_id, actor_id, key)
);

CREATE INDEX control_idempotency_expiry ON control_idempotency (tenant_id, expires_at);

CREATE TABLE control_record_ids (
    tenant_id varchar(128) NOT NULL,
    kind varchar(32) NOT NULL,
    record_id varchar(128) NOT NULL,
    PRIMARY KEY (tenant_id, kind, record_id)
);

GRANT SELECT, INSERT ON control_record_ids TO toolgate_control_runtime;

GRANT USAGE ON SCHEMA public TO toolgate_control_platform;
GRANT SELECT,INSERT,UPDATE ON control_tenants TO toolgate_control_platform;
GRANT SELECT,UPDATE ON control_tenant_authority TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON control_tenant_authority TO toolgate_control_platform;

GRANT SELECT, INSERT, DELETE ON control_idempotency TO toolgate_control_runtime;

CREATE TABLE control_policy_bundles (
    tenant_id varchar(128) NOT NULL,
    sequence bigint NOT NULL CHECK (sequence BETWEEN 1 AND 9007199254740991),
    directory_revision bigint NOT NULL CHECK (directory_revision BETWEEN 0 AND 9007199254740991),
    document text NOT NULL CHECK (octet_length(document) <= 2000100),
    policy text NOT NULL CHECK (octet_length(policy) <= 1048576),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, sequence)
);

GRANT SELECT, INSERT ON control_policy_bundles TO toolgate_control_runtime;

CREATE TABLE control_approval_clocks (
    tenant_id varchar(128) PRIMARY KEY,
    observed_at bigint NOT NULL CHECK (observed_at BETWEEN 0 AND 9007199254740991)
);

GRANT SELECT, INSERT, UPDATE ON control_approval_clocks TO toolgate_control_runtime;

CREATE TABLE control_enrollments (
    tenant_id varchar(128) NOT NULL,
    enrollment_id varchar(128) NOT NULL,
    code_digest char(64) NOT NULL,
    device_digest char(64) NOT NULL,
    document text NOT NULL CHECK (octet_length(document)<=16384),
    csr text NOT NULL CHECK (octet_length(csr)<=16384),
    user_id varchar(128),
    certificate text CHECK (octet_length(certificate)<=32768),
    expires_at bigint NOT NULL CHECK (expires_at>=0),
    last_poll bigint NOT NULL DEFAULT 0 CHECK (last_poll>=0),
    PRIMARY KEY (tenant_id,enrollment_id),
    UNIQUE (tenant_id,code_digest)
);

CREATE INDEX control_enrollment_expiry ON control_enrollments (tenant_id,expires_at);

CREATE TABLE control_endpoints (
    tenant_id varchar(128) NOT NULL,
    device_id varchar(128) NOT NULL,
    key_fingerprint char(64) NOT NULL,
    document text NOT NULL CHECK (octet_length(document)<=1048576),
    csr text NOT NULL CHECK (octet_length(csr)<=16384),
    report_digest char(64) NOT NULL,
    acknowledgment text NOT NULL CHECK (octet_length(acknowledgment)<=131072),
    PRIMARY KEY (tenant_id,device_id),
    UNIQUE (tenant_id,key_fingerprint),
    CHECK (document::jsonb->>'deviceId'=device_id AND document::jsonb->>'tenantId'=tenant_id)
);

GRANT SELECT,INSERT,UPDATE,DELETE ON control_enrollments TO toolgate_control_runtime;

GRANT SELECT,INSERT,UPDATE ON control_endpoints TO toolgate_control_runtime;

CREATE TABLE control_fleet (
    tenant_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('RELEASE','DESIRED','ROLLOUT')),
    record_id TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0 AND revision <= 9007199254740991),
    document TEXT NOT NULL CHECK (octet_length(document) <= 131072),
    PRIMARY KEY (tenant_id, kind, record_id)
);

CREATE FUNCTION control_fleet_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR OLD.kind = 'RELEASE' OR NEW.tenant_id <> OLD.tenant_id
      OR NEW.kind <> OLD.kind OR NEW.record_id <> OLD.record_id OR NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'fleet immutable identity or generation violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER control_fleet_guard BEFORE UPDATE OR DELETE ON control_fleet
  FOR EACH ROW EXECUTE FUNCTION control_fleet_guard();

GRANT SELECT, INSERT, UPDATE ON control_fleet TO toolgate_control_runtime;

REVOKE DELETE ON control_fleet FROM toolgate_control_runtime;

CREATE TABLE control_builder (
 tenant_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK (kind IN ('DRAFT','VERSION','TEST')),
 record_id TEXT NOT NULL,
 revision BIGINT NOT NULL CHECK (revision > 0 AND revision <= 9007199254740991),
 document TEXT NOT NULL CHECK (octet_length(document) <= 65536),
 PRIMARY KEY (tenant_id,kind,record_id)
);

CREATE FUNCTION control_builder_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.kind='TEST' AND (OLD.document::jsonb->>'expiresAtUnixMs')::bigint <
    (extract(epoch from clock_timestamp())*1000)::bigint-604800000 THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'authoring retention violation';
 END IF;
 IF OLD.kind='VERSION' OR (OLD.kind='DRAFT' AND (OLD.document::jsonb->>'sealed')::boolean)
  OR NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.record_id<>OLD.record_id
  OR NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'authoring immutable identity violation'; END IF;
 RETURN NEW;
END;
$$;

CREATE TRIGGER control_builder_guard BEFORE UPDATE OR DELETE ON control_builder FOR EACH ROW EXECUTE FUNCTION control_builder_guard();

GRANT SELECT,INSERT,UPDATE,DELETE ON control_builder TO toolgate_control_runtime;

CREATE TABLE control_endpoint_configurations (
    tenant_id varchar(128) NOT NULL,
    device_id varchar(128) NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision>=0),
    document text NOT NULL CHECK (octet_length(document)<=98304),
    acknowledged_digest char(64),
    local_tools text NOT NULL CHECK (octet_length(local_tools)<=49152),
    PRIMARY KEY (tenant_id,device_id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES control_endpoints(tenant_id,device_id)
);

GRANT SELECT,INSERT,UPDATE ON control_endpoint_configurations TO toolgate_control_runtime;

CREATE TABLE control_mcp_requests (
    tenant_id varchar(128) NOT NULL,
    request_id varchar(128) NOT NULL,
    device_id varchar(128) NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED','RESPONSE_RECEIVED','DONE','FAILED','EXPIRED')),
    received_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    record text NOT NULL CHECK (octet_length(record)<=8192),
    task text NOT NULL CHECK (octet_length(task)<=16384),
    result text CHECK (octet_length(result)<=65536),
    PRIMARY KEY (tenant_id,request_id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES control_endpoints(tenant_id,device_id)
);

CREATE INDEX control_mcp_pending ON control_mcp_requests(tenant_id,device_id,state,received_at);

GRANT SELECT,INSERT,UPDATE,DELETE ON control_mcp_requests TO toolgate_control_runtime;

CREATE TABLE control_authorization_outbox(tenant_id varchar(128) NOT NULL,revision bigint NOT NULL,authorization_epoch bigint NOT NULL,occurred_at bigint NOT NULL,published_at bigint,PRIMARY KEY(tenant_id,revision));

CREATE TABLE control_enterprise_invocations(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,request_digest char(64) NOT NULL,state varchar(32) NOT NULL,authorization_epoch bigint NOT NULL,created_at bigint NOT NULL,expires_at bigint NOT NULL,revision bigint NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,invocation_id),
    tool_id varchar(256),
    package_id varchar(128),
    package_digest char(71) CHECK (package_digest ~ '^sha256:[a-f0-9]{64}$'),
    pool_id varchar(128),
    runtime_digest char(71) CHECK (runtime_digest ~ '^sha256:[a-f0-9]{64}$'),
    effect_safety varchar(16) CHECK (effect_safety IN ('READ_ONLY','IDEMPOTENT','NON_IDEMPOTENT','UNKNOWN')),
    requires_execution_ledger boolean,
    lease_holder varchar(256),
    lease_expires_at bigint CHECK (lease_expires_at BETWEEN 0 AND 9007199254740991),
    cancel_requested_at bigint CHECK (cancel_requested_at BETWEEN 0 AND 9007199254740991),
    not_started boolean NOT NULL DEFAULT false,
    task_id varchar(64) CHECK (task_id ~ '^[A-Za-z0-9_-]{22,64}$'),
    task_state varchar(32),
    task_owner_digest char(64) CHECK (task_owner_digest ~ '^[a-f0-9]{64}$'),
    task_retain_until bigint CHECK (task_retain_until BETWEEN 0 AND 9007199254740991),
    task_input_deadline_at bigint CHECK (task_input_deadline_at BETWEEN 0 AND 9007199254740991),
    task_last_polled_at bigint CHECK (task_last_polled_at BETWEEN 0 AND 9007199254740991),
    task_document text CHECK (octet_length(task_document) <= 32768),
    insights_fact_seq bigint NOT NULL DEFAULT 0 CHECK (insights_fact_seq >= 0),
    CONSTRAINT control_invocation_v2_class CHECK (
    requires_execution_ledger IS NULL
    OR (tool_id IS NOT NULL AND package_digest IS NOT NULL AND effect_safety IS NOT NULL
        AND requires_execution_ledger = (effect_safety IN ('NON_IDEMPOTENT','UNKNOWN')))),
    CONSTRAINT control_invocation_task_shape CHECK (
    (task_id IS NULL) = (task_state IS NULL)
    AND (task_id IS NULL) = (task_owner_digest IS NULL)
    AND (task_id IS NULL) = (task_retain_until IS NULL)
    AND (task_id IS NULL) = (task_document IS NULL)
    AND (task_id IS NULL OR requires_execution_ledger IS NOT NULL)),
    CONSTRAINT control_invocation_task_projection CHECK (
    task_state IS NULL
    OR (state = 'PENDING_APPROVAL' AND task_state = 'AWAITING_APPROVAL')
    OR (state = 'QUEUED' AND task_state = 'QUEUED')
    OR (state IN ('RESERVED','DISPATCHED') AND task_state IN ('DISPATCHED','CANCEL_REQUESTED'))
    OR (state = 'EXECUTING' AND task_state IN ('RUNNING','INPUT_REQUIRED','CANCEL_REQUESTED'))
    OR (state = 'SUCCEEDED' AND task_state = 'COMPLETED')
    OR (state IN ('FAILED','PARTIAL') AND task_state = 'FAILED')
    OR (state = 'CANCELLED' AND task_state IN ('CANCELLED','REJECTED'))
    OR (state = 'EXPIRED' AND task_state = 'EXPIRED')
    OR (state = 'OUTCOME_UNKNOWN' AND task_state = 'OUTCOME_UNKNOWN')),
    CONSTRAINT control_invocation_lease_shape CHECK (
    (lease_holder IS NULL) = (lease_expires_at IS NULL)
    AND (lease_holder IS NULL OR state NOT IN ('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED'))),
    CONSTRAINT control_invocation_cancel_shape CHECK (
    (task_state IS DISTINCT FROM 'CANCEL_REQUESTED' OR cancel_requested_at IS NOT NULL)
    AND (NOT not_started OR state = 'FAILED'))
);

CREATE INDEX control_enterprise_invocation_window ON control_enterprise_invocations(tenant_id,created_at);

CREATE TABLE control_enterprise_approvals(tenant_id varchar(128) NOT NULL,approval_id varchar(128) NOT NULL,approval_type varchar(16) NOT NULL CHECK(approval_type IN('OPERATION','CONFIGURATION')),invocation_id varchar(128) NOT NULL,request_digest char(64) NOT NULL,authorization_epoch bigint NOT NULL,expires_at bigint NOT NULL,revision bigint NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,approval_id));

CREATE TABLE control_enterprise_nonces(tenant_id varchar(128) NOT NULL,nonce varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,evaluation_digest char(64) NOT NULL,authorization_epoch bigint NOT NULL,issued_at bigint NOT NULL,expires_at bigint NOT NULL,consumed_at bigint,PRIMARY KEY(tenant_id,nonce));

CREATE TABLE control_enterprise_adoptions(tenant_id varchar(128) NOT NULL,actor_type varchar(16) NOT NULL,actor_id varchar(128) NOT NULL,directory_revision bigint NOT NULL,snapshot_sequence bigint NOT NULL,graph_digest char(64) NOT NULL,observed_at bigint NOT NULL,PRIMARY KEY(tenant_id,actor_type,actor_id),authorization_epoch bigint NOT NULL DEFAULT 0);

CREATE FUNCTION control_epoch_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.revision<OLD.revision OR NEW.authorization_epoch<OLD.authorization_epoch OR NEW.directory_cache_generation<OLD.directory_cache_generation THEN RAISE EXCEPTION 'authorization cannot move backwards or change ownership'; END IF; RETURN NEW; END $$;

CREATE TRIGGER control_epoch_guard BEFORE UPDATE ON control_tenant_authority FOR EACH ROW EXECUTE FUNCTION control_epoch_guard();

GRANT SELECT,INSERT,UPDATE ON control_authorization_outbox,control_enterprise_invocations,control_enterprise_approvals,control_enterprise_nonces,control_enterprise_adoptions TO toolgate_control_runtime;

CREATE TABLE control_invocation_authority(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,expires_at bigint NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
GRANT SELECT,INSERT,UPDATE ON control_invocation_authority TO toolgate_control_runtime;
CREATE TABLE control_operation_cancellations(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,PRIMARY KEY(tenant_id,invocation_id));
GRANT SELECT,INSERT ON control_operation_cancellations TO toolgate_control_runtime;
CREATE TABLE control_credential_history(tenant_id varchar(128) NOT NULL,credential_sha256 char(64) NOT NULL,PRIMARY KEY(tenant_id,credential_sha256));

GRANT SELECT,INSERT ON control_credential_history TO toolgate_control_runtime;

CREATE FUNCTION control_credential_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable credential history'; END $$;

CREATE TRIGGER control_credential_history_immutable BEFORE UPDATE OR DELETE ON control_credential_history FOR EACH ROW EXECUTE FUNCTION control_credential_immutable();
CREATE TRIGGER control_operation_cancellations_immutable BEFORE UPDATE OR DELETE ON control_operation_cancellations FOR EACH ROW EXECUTE FUNCTION control_credential_immutable();

CREATE TABLE control_reviewed_recovery(tenant_id varchar(128) NOT NULL,review_digest char(64) NOT NULL,PRIMARY KEY(tenant_id,review_digest));

GRANT SELECT,INSERT ON control_reviewed_recovery TO toolgate_control_runtime;

CREATE TRIGGER control_reviewed_recovery_immutable BEFORE UPDATE OR DELETE ON control_reviewed_recovery FOR EACH ROW EXECUTE FUNCTION control_credential_immutable();

CREATE TABLE control_configuration_changes(tenant_id varchar(128) NOT NULL,change_id varchar(128) NOT NULL,revision bigint NOT NULL,request_digest char(64) NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,change_id));

GRANT SELECT,INSERT,UPDATE ON control_configuration_changes TO toolgate_control_runtime;

CREATE TABLE control_tenant_vault_keys(
    tenant_id varchar(128) NOT NULL,
    version bigint NOT NULL CHECK(version BETWEEN 1 AND 1024),
    state varchar(16) NOT NULL CHECK(state IN('ACTIVE','READ_ONLY','RETIRED')),
    provider_reference varchar(128) NOT NULL,
    wrapped_encryption_key text NOT NULL CHECK(octet_length(wrapped_encryption_key)<=1024),
    wrapped_retry_key text NOT NULL CHECK(octet_length(wrapped_retry_key)<=1024),
    PRIMARY KEY(tenant_id,version)
);
CREATE UNIQUE INDEX control_tenant_vault_keys_current ON control_tenant_vault_keys(tenant_id) WHERE state='ACTIVE';
GRANT SELECT ON control_tenant_vault_keys TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON control_tenant_vault_keys TO toolgate_control_platform;
CREATE FUNCTION control_vault_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'vault key retention requires explicit offboarding'; END IF;
    IF NEW.version<>OLD.version OR NEW.tenant_id<>OLD.tenant_id OR OLD.state='RETIRED' OR
       (NEW.state<>OLD.state AND NOT ((OLD.state='ACTIVE' AND NEW.state='READ_ONLY') OR (OLD.state='READ_ONLY' AND NEW.state='RETIRED'))) OR
       (NEW.state='RETIRED' AND EXISTS(SELECT 1 FROM control_secrets WHERE tenant_id=OLD.tenant_id AND key_version=OLD.version))
       THEN RAISE EXCEPTION 'invalid vault key transition'; END IF;
    RETURN NEW;
END $$;
CREATE TABLE control_secrets(tenant_id varchar(128) NOT NULL,name varchar(128) NOT NULL,tool_group_id varchar(128) NOT NULL,device_group_id varchar(128) NOT NULL,version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),key_version bigint NOT NULL,cipher text NOT NULL CHECK(octet_length(cipher)<=65536),PRIMARY KEY(tenant_id,name),FOREIGN KEY(tenant_id,key_version) REFERENCES control_tenant_vault_keys(tenant_id,version));
CREATE TRIGGER control_vault_key_guard BEFORE UPDATE OR DELETE ON control_tenant_vault_keys FOR EACH ROW EXECUTE FUNCTION control_vault_key_guard();
CREATE FUNCTION control_secret_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.name<>OLD.name OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'invalid secret version transition'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_secret_version_guard BEFORE UPDATE ON control_secrets FOR EACH ROW EXECUTE FUNCTION control_secret_version_guard();

GRANT SELECT,INSERT,UPDATE ON control_secrets TO toolgate_control_runtime;

CREATE TABLE control_queued_authority(tenant_id varchar(128) NOT NULL,kind varchar(32) NOT NULL,record_id varchar(128) NOT NULL,creator_user_id varchar(128) NOT NULL,PRIMARY KEY(tenant_id,kind,record_id));

GRANT SELECT,INSERT,UPDATE ON control_queued_authority TO toolgate_control_runtime;

CREATE TABLE control_execution_budgets(tenant_id varchar(128) NOT NULL,budget_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,created_at bigint NOT NULL,PRIMARY KEY(tenant_id,budget_id,invocation_id));

CREATE INDEX control_budget_window ON control_execution_budgets(tenant_id,budget_id,created_at);

GRANT SELECT,INSERT ON control_execution_budgets TO toolgate_control_runtime;

CREATE TABLE control_outcome_reconciliations(tenant_id varchar(128) NOT NULL,reconciliation_id varchar(128) NOT NULL,revision bigint NOT NULL,document text NOT NULL,PRIMARY KEY(tenant_id,reconciliation_id));

CREATE TABLE control_recovery_floors(tenant_id varchar(128) PRIMARY KEY,snapshot_sequence bigint NOT NULL CHECK(snapshot_sequence>=0));

GRANT SELECT,INSERT,UPDATE ON control_outcome_reconciliations TO toolgate_control_runtime;

GRANT SELECT ON control_recovery_floors TO toolgate_control_runtime;

CREATE TABLE control_server_settings(tenant_id varchar(128) PRIMARY KEY,revision bigint NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),document text NOT NULL CHECK(octet_length(document)<=16384));

GRANT SELECT,INSERT,UPDATE ON control_server_settings TO toolgate_control_runtime;

CREATE UNIQUE INDEX control_invocation_task ON control_enterprise_invocations (tenant_id, task_id)
    WHERE task_id IS NOT NULL;

CREATE INDEX control_invocation_lease ON control_enterprise_invocations (tenant_id, lease_expires_at)
    WHERE state = 'EXECUTING';

CREATE INDEX control_invocation_task_retention ON control_enterprise_invocations (tenant_id, task_retain_until)
    WHERE task_id IS NOT NULL;

CREATE INDEX control_invocation_input_deadline ON control_enterprise_invocations (tenant_id, task_input_deadline_at)
    WHERE task_state IN ('AWAITING_APPROVAL','INPUT_REQUIRED');

CREATE INDEX control_invocation_active_package ON control_enterprise_invocations (tenant_id, package_digest)
    WHERE state IN ('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');

CREATE INDEX control_invocation_active_tool ON control_enterprise_invocations (tenant_id, tool_id)
    WHERE state IN ('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');

CREATE TABLE control_business_keys (
    tenant_id varchar(128) NOT NULL,
    tool_id varchar(256) NOT NULL,
    namespace varchar(16) NOT NULL CHECK (namespace IN ('AGENT','DELEGATED_USER','TENANT')),
    namespace_identity varchar(128) NOT NULL,
    business_key_hash char(64) NOT NULL CHECK (business_key_hash ~ '^[a-f0-9]{64}$'),
    source varchar(16) NOT NULL CHECK (source IN ('HEADER','ARGUMENT','DEDUPE_WINDOW')),
    fingerprint char(71) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    caller_agent_id varchar(128) NOT NULL,
    caller_user_id varchar(128),
    invocation_id varchar(128) NOT NULL,
    created_at bigint NOT NULL CHECK (created_at BETWEEN 0 AND 9007199254740991),
    dedupe_expires_at bigint CHECK (dedupe_expires_at BETWEEN 0 AND 9007199254740991),
    retain_until bigint NOT NULL CHECK (retain_until BETWEEN 0 AND 9007199254740991),
    outcome varchar(32) CHECK (outcome IN ('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED','OUTCOME_UNKNOWN')),
    tombstoned_at bigint CHECK (tombstoned_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (tenant_id, tool_id, namespace, namespace_identity, business_key_hash),
    CHECK ((namespace = 'TENANT') = (namespace_identity = '')),
    CHECK ((source = 'DEDUPE_WINDOW') = (dedupe_expires_at IS NOT NULL)),
    CHECK (dedupe_expires_at IS NULL OR dedupe_expires_at > created_at),
    CHECK (retain_until >= created_at),
    CHECK (tombstoned_at IS NULL OR outcome IS NOT NULL)
);

CREATE INDEX control_business_key_invocation ON control_business_keys (tenant_id, invocation_id);

CREATE INDEX control_business_key_retention ON control_business_keys (tenant_id, retain_until)
    WHERE tombstoned_at IS NULL;

CREATE TABLE control_invocation_results (
    tenant_id varchar(128) NOT NULL,
    invocation_id varchar(128) NOT NULL,
    fencing_nonce varchar(128) NOT NULL,
    result_digest char(71) NOT NULL CHECK (result_digest ~ '^sha256:[a-f0-9]{64}$'),
    byte_length integer NOT NULL CHECK (byte_length BETWEEN 0 AND 4194304),
    payload bytea,
    created_at bigint NOT NULL CHECK (created_at BETWEEN 0 AND 9007199254740991),
    retain_until bigint NOT NULL CHECK (retain_until BETWEEN 0 AND 9007199254740991),
    purged_at bigint CHECK (purged_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (tenant_id, invocation_id),
    FOREIGN KEY (tenant_id, invocation_id) REFERENCES control_enterprise_invocations (tenant_id, invocation_id),
    CHECK ((payload IS NULL) = (purged_at IS NOT NULL)),
    CHECK (payload IS NULL OR octet_length(payload) = byte_length),
    CHECK (retain_until >= created_at)
);

CREATE INDEX control_invocation_result_retention ON control_invocation_results (tenant_id, retain_until)
    WHERE purged_at IS NULL;

CREATE FUNCTION control_invocation_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'invocation results are purged, never deleted'; END IF;
    IF OLD.purged_at IS NOT NULL OR NEW.payload IS NOT NULL OR NEW.purged_at IS NULL
       OR NEW.tenant_id <> OLD.tenant_id OR NEW.invocation_id <> OLD.invocation_id
       OR NEW.fencing_nonce <> OLD.fencing_nonce OR NEW.result_digest <> OLD.result_digest
       OR NEW.byte_length <> OLD.byte_length OR NEW.created_at <> OLD.created_at
       OR NEW.retain_until <> OLD.retain_until THEN
        RAISE EXCEPTION 'invocation result is immutable except for its purge';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER control_invocation_result_guard BEFORE UPDATE OR DELETE ON control_invocation_results
    FOR EACH ROW EXECUTE FUNCTION control_invocation_result_guard();

CREATE FUNCTION control_business_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'business keys are tombstoned, never deleted'; END IF;
    IF OLD.tombstoned_at IS NOT NULL OR NEW.source <> OLD.source THEN
        RAISE EXCEPTION 'business key reservation is immutable';
    END IF;
    IF OLD.source = 'DEDUPE_WINDOW' THEN
        IF NEW.invocation_id <> OLD.invocation_id AND OLD.dedupe_expires_at > NEW.created_at THEN
            RAISE EXCEPTION 'dedupe window still open';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.fingerprint <> OLD.fingerprint OR NEW.caller_agent_id <> OLD.caller_agent_id
       OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id THEN
        RAISE EXCEPTION 'business key reservation is immutable';
    END IF;
    IF (NEW.invocation_id <> OLD.invocation_id OR NEW.created_at <> OLD.created_at)
       AND NOT (COALESCE(OLD.outcome,'') IN ('EXPIRED','FAILED') AND NEW.outcome IS NULL) THEN
        RAISE EXCEPTION 'business key already bound to an invocation';
    END IF;
    IF NEW.invocation_id = OLD.invocation_id AND OLD.outcome IS NOT NULL
       AND NEW.outcome IS DISTINCT FROM OLD.outcome
       AND NOT (OLD.outcome = 'OUTCOME_UNKNOWN' AND NEW.outcome IN ('COMPLETED','FAILED')) THEN
        RAISE EXCEPTION 'business key outcome is final';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER control_business_key_guard BEFORE UPDATE OR DELETE ON control_business_keys
    FOR EACH ROW EXECUTE FUNCTION control_business_key_guard();

GRANT SELECT, INSERT, UPDATE ON control_business_keys, control_invocation_results TO toolgate_control_runtime;

CREATE TABLE control_bootstrap_admins (
    tenant_id varchar(128) PRIMARY KEY,
    user_id varchar(128) NOT NULL
);

GRANT SELECT,INSERT ON control_bootstrap_admins TO toolgate_control_runtime;

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

GRANT SELECT,INSERT,UPDATE ON control_tool_hosts TO toolgate_control_runtime;

GRANT SELECT,INSERT,UPDATE,DELETE ON control_server_deployments TO toolgate_control_runtime;

CREATE TABLE control_tool_host_dispatches(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,host_id varchar(128) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('QUEUED','CLAIMED','ADMITTED','REFUSED','FINISHED','EXPIRED')),
    expires_at bigint NOT NULL CHECK(expires_at BETWEEN 0 AND 9007199254740991),created_at bigint NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
    document text NOT NULL CHECK(octet_length(document)<=16777216),PRIMARY KEY(tenant_id,invocation_id));

CREATE INDEX control_tool_host_dispatches_queue ON control_tool_host_dispatches(tenant_id,host_id,state,created_at);

GRANT SELECT,INSERT,UPDATE,DELETE ON control_tool_host_dispatches TO toolgate_control_runtime;

CREATE TABLE control_mcp_servers(tenant_id varchar(128) NOT NULL,slug varchar(31) NOT NULL CHECK(slug ~ '^[a-z][a-z0-9-]{1,30}$'),revision bigint NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),
    document text NOT NULL CHECK(octet_length(document)<=65536),PRIMARY KEY(tenant_id,slug));

CREATE TABLE control_mcp_snapshots(tenant_id varchar(128) NOT NULL,slug varchar(31) NOT NULL CHECK(slug ~ '^[a-z][a-z0-9-]{1,30}$'),revision bigint NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),
    document text NOT NULL CHECK(octet_length(document)<=8388608),PRIMARY KEY(tenant_id,slug,revision));

CREATE TABLE control_mcp_approvals(tenant_id varchar(128) NOT NULL,slug varchar(31) NOT NULL CHECK(slug ~ '^[a-z][a-z0-9-]{1,30}$'),sequence bigint NOT NULL CHECK(sequence BETWEEN 1 AND 9007199254740991),
    document text NOT NULL CHECK(octet_length(document)<=1048576),PRIMARY KEY(tenant_id,slug,sequence));

CREATE TABLE control_mcp_scans(tenant_id varchar(128) NOT NULL,scan_id varchar(128) NOT NULL,slug varchar(31) NOT NULL CHECK(slug ~ '^[a-z][a-z0-9-]{1,30}$'),host_id varchar(128) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('QUEUED','CLAIMED','ADMITTED','FINISHED','FAILED','EXPIRED')),
    deadline bigint NOT NULL CHECK(deadline BETWEEN 0 AND 9007199254740991),created_at bigint NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
    document text NOT NULL CHECK(octet_length(document)<=65536),PRIMARY KEY(tenant_id,scan_id));

CREATE INDEX control_mcp_scans_queue ON control_mcp_scans(tenant_id,host_id,state,created_at);

CREATE INDEX control_mcp_scans_server ON control_mcp_scans(tenant_id,slug,state);

GRANT SELECT,INSERT,UPDATE,DELETE ON control_mcp_servers,control_mcp_scans TO toolgate_control_runtime;

GRANT SELECT,INSERT,DELETE ON control_mcp_snapshots TO toolgate_control_runtime;

GRANT SELECT,INSERT ON control_mcp_approvals TO toolgate_control_runtime;

CREATE FUNCTION control_invalidate_directory_cache() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        UPDATE public.control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=OLD.tenant_id;
        IF NOT FOUND THEN RAISE EXCEPTION 'tenant authority missing'; END IF;
    END IF;
    IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.tenant_id<>OLD.tenant_id) THEN
        UPDATE public.control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=NEW.tenant_id;
        IF NOT FOUND THEN RAISE EXCEPTION 'tenant authority missing'; END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER control_records_cache_generation AFTER INSERT OR UPDATE OR DELETE ON control_records
FOR EACH ROW EXECUTE FUNCTION control_invalidate_directory_cache();

CREATE TRIGGER control_settings_cache_generation AFTER INSERT OR UPDATE OR DELETE ON control_server_settings
FOR EACH ROW EXECUTE FUNCTION control_invalidate_directory_cache();

CREATE TABLE control_console_devices (
 tenant_id text NOT NULL, session_id text NOT NULL, user_id text NOT NULL,
 device_id text NOT NULL, nonce_digest text NOT NULL, code_digest text NOT NULL,
 csr text NOT NULL, fingerprint text NOT NULL,
 expires_at bigint NOT NULL, verified_at bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,session_id)
);

CREATE INDEX control_console_devices_expiry ON control_console_devices(tenant_id,expires_at);

GRANT SELECT,INSERT,UPDATE,DELETE ON control_console_devices TO toolgate_control_runtime;

CREATE TABLE control_invocation_event_streams(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    last_cursor bigint NOT NULL CHECK(last_cursor BETWEEN 0 AND 9007199254740991),
    event_count integer NOT NULL CHECK(event_count>=0),byte_count bigint NOT NULL CHECK(byte_count>=0),
    dropped_frames bigint NOT NULL CHECK(dropped_frames>=0),refused_frames bigint NOT NULL CHECK(refused_frames>=0),
    retain_until bigint NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),purged_at bigint CHECK(purged_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY(tenant_id,invocation_id),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id));

CREATE INDEX control_invocation_event_stream_retention ON control_invocation_event_streams(tenant_id,retain_until) WHERE purged_at IS NULL;

CREATE TABLE control_invocation_events(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    event_cursor bigint NOT NULL CHECK(event_cursor BETWEEN 1 AND 9007199254740991),
    source varchar(16) NOT NULL CHECK(source IN('EXECUTOR','PLATFORM')),kind varchar(16) NOT NULL CHECK(kind IN('LOG','PROGRESS','INPUT_REQUEST','STATE','RESULT')),
    attempt integer CHECK(attempt BETWEEN 1 AND 16),fencing_token varchar(128),sequence integer CHECK(sequence BETWEEN 1 AND 2147483647),
    recorded_at bigint NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),document text NOT NULL CHECK(octet_length(document)<=131072),
    PRIMARY KEY(tenant_id,invocation_id,event_cursor),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_invocation_event_streams(tenant_id,invocation_id),
    CHECK((source='EXECUTOR')=(fencing_token IS NOT NULL AND attempt IS NOT NULL)),
    CHECK(sequence IS NULL OR source='EXECUTOR'));

CREATE UNIQUE INDEX control_invocation_event_identity ON control_invocation_events(tenant_id,invocation_id,fencing_token,sequence) WHERE sequence IS NOT NULL;

GRANT SELECT,INSERT,UPDATE,DELETE ON control_invocation_event_streams,control_invocation_events TO toolgate_control_runtime;

-- Operator Insights (ADR 031). Metadata only, never tool arguments, outputs or secrets. Deliberately outside tenant
-- capacity accounting: Insights rows must never make a ledger write fail; Insights retention and quota bound them.
CREATE TABLE control_insights_facts(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    fact_seq bigint NOT NULL CHECK(fact_seq BETWEEN 1 AND 9007199254740991),revision bigint NOT NULL,
    from_state varchar(32),to_state varchar(32) NOT NULL,occurred_at bigint NOT NULL CHECK(occurred_at BETWEEN 0 AND 9007199254740991),
    execution_id char(35) CHECK(execution_id ~ '^ex-[0-9a-f]{32}$'),detail text NOT NULL CHECK(octet_length(detail)<=8192),
    projected_at bigint,PRIMARY KEY(tenant_id,invocation_id,fact_seq));
CREATE INDEX control_insights_facts_unprojected ON control_insights_facts(tenant_id,invocation_id,fact_seq) WHERE projected_at IS NULL;
CREATE TABLE control_insights_checkpoints(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    last_fact_seq bigint NOT NULL CHECK(last_fact_seq>=0),contribution text NOT NULL CHECK(octet_length(contribution)<=8192),
    final_at bigint,PRIMARY KEY(tenant_id,invocation_id));
CREATE TABLE control_insights_invocations(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    tool_id varchar(256) NOT NULL,action varchar(256) NOT NULL,client_id varchar(128),user_id varchar(128),agent_id varchar(128),
    tool_call_id varchar(128),session_id varchar(128),agent_run_id varchar(128),parent_invocation_id varchar(128),outcome varchar(32) NOT NULL,error_category varchar(32),
    attempts integer NOT NULL CHECK(attempts BETWEEN 0 AND 64),accepted_at bigint NOT NULL,approval_requested_at bigint,approval_resolved_at bigint,
    queued_at bigint,first_started_at bigint,terminal_at bigint,approval_ms bigint,queue_ms bigint,execution_ms bigint,end_to_end_ms bigint,
    partial_coverage boolean NOT NULL,span_state varchar(16) CHECK(span_state IN('RETAINED','SAMPLED_OUT','DROPPED')),PRIMARY KEY(tenant_id,invocation_id));
CREATE INDEX control_insights_invocations_accepted ON control_insights_invocations(tenant_id,accepted_at);
CREATE INDEX control_insights_invocations_tool_call ON control_insights_invocations(tenant_id,tool_call_id) WHERE tool_call_id IS NOT NULL;
CREATE INDEX control_insights_invocations_session ON control_insights_invocations(tenant_id,session_id) WHERE session_id IS NOT NULL;
CREATE INDEX control_insights_invocations_run ON control_insights_invocations(tenant_id,agent_run_id) WHERE agent_run_id IS NOT NULL;
CREATE INDEX control_insights_invocations_parent ON control_insights_invocations(tenant_id,parent_invocation_id) WHERE parent_invocation_id IS NOT NULL;
CREATE TABLE control_insights_attempts(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 1 AND 64),execution_id char(35) CHECK(execution_id ~ '^ex-[0-9a-f]{32}$'),
    executor_id varchar(128),started_at bigint,ended_at bigint,outcome varchar(32),duration_ms bigint,
    PRIMARY KEY(tenant_id,invocation_id,attempt_number));
CREATE INDEX control_insights_attempts_execution ON control_insights_attempts(tenant_id,execution_id) WHERE execution_id IS NOT NULL;
CREATE TABLE control_insights_cells(tenant_id varchar(128) NOT NULL,series varchar(8) NOT NULL CHECK(series IN('TOOL','CLIENT')),
    granularity varchar(8) NOT NULL CHECK(granularity IN('MINUTE','HOUR','DAY')),bucket_start bigint NOT NULL,dim varchar(256) NOT NULL,
    outcome varchar(32) NOT NULL,calls bigint NOT NULL CHECK(calls>=0),duration_count bigint NOT NULL CHECK(duration_count>=0),
    duration_sum bigint NOT NULL CHECK(duration_sum>=0),histogram text NOT NULL CHECK(octet_length(histogram)<=1024),
    PRIMARY KEY(tenant_id,series,granularity,bucket_start,dim,outcome));
CREATE TABLE control_insights_admissions(tenant_id varchar(128) NOT NULL,bucket_start bigint NOT NULL,tool_id varchar(256) NOT NULL,
    category varchar(32) NOT NULL,count bigint NOT NULL CHECK(count>=0),PRIMARY KEY(tenant_id,bucket_start,tool_id,category));
CREATE TABLE control_insights_gateway_epochs(tenant_id varchar(128) NOT NULL,gateway_id varchar(128) NOT NULL,epoch varchar(128) NOT NULL,
    last_sequence bigint NOT NULL CHECK(last_sequence>=0),missing bigint NOT NULL CHECK(missing>=0),dropped bigint NOT NULL CHECK(dropped>=0),
    final boolean NOT NULL,last_seen bigint NOT NULL,PRIMARY KEY(tenant_id,gateway_id,epoch));
CREATE TABLE control_insights_tenant_state(tenant_id varchar(128) NOT NULL,collecting_since bigint NOT NULL,
    summaries_suspended_since bigint,summary_rows bigint NOT NULL DEFAULT 0 CHECK(summary_rows>=0),metrics_exported_through bigint,PRIMARY KEY(tenant_id));
CREATE TABLE control_insights_spans(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,span_id char(16) NOT NULL CHECK(span_id ~ '^[0-9a-f]{16}$'),
    trace_id char(32) NOT NULL CHECK(trace_id ~ '^[0-9a-f]{32}$'),parent_span_id char(16) CHECK(parent_span_id ~ '^[0-9a-f]{16}$'),name varchar(32) NOT NULL,
    source varchar(16) NOT NULL CHECK(source IN('GATEWAY','TOOL_HOST','CLIENT')),execution_id char(35) CHECK(execution_id ~ '^ex-[0-9a-f]{32}$'),
    start_at bigint NOT NULL,duration_ms bigint NOT NULL CHECK(duration_ms BETWEEN 0 AND 86400000),status varchar(8) NOT NULL CHECK(status IN('OK','ERROR')),
    route_id varchar(128),http_status integer CHECK(http_status BETWEEN 100 AND 599),error_code varchar(64),stored_at bigint NOT NULL,
    PRIMARY KEY(tenant_id,invocation_id,span_id));
CREATE INDEX control_insights_spans_stored ON control_insights_spans(tenant_id,stored_at);
GRANT SELECT,INSERT,UPDATE,DELETE ON control_insights_facts,control_insights_checkpoints,control_insights_invocations,control_insights_attempts,
    control_insights_cells,control_insights_admissions,control_insights_gateway_epochs,control_insights_tenant_state,control_insights_spans TO toolgate_control_runtime;

CREATE TABLE control_package_provisioning(tenant_id varchar(128) NOT NULL,profile_id varchar(128) NOT NULL,
    generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),profile_digest char(71) NOT NULL CHECK(profile_digest ~ '^sha256:[a-f0-9]{64}$'),
    document text NOT NULL CHECK(octet_length(document)<=262144),PRIMARY KEY(tenant_id,profile_id));

GRANT SELECT,INSERT,UPDATE ON control_package_provisioning TO toolgate_control_runtime;

-- Registry access is available only through narrow helpers to tenant runtime.
CREATE TABLE control_tenant_initializations(
    tenant_id varchar(128) PRIMARY KEY REFERENCES control_tenants(tenant_id),
    version integer NOT NULL CHECK(version=1),effective_digest char(64) NOT NULL CHECK(effective_digest ~ '^[a-f0-9]{64}$'),
    provenance text NOT NULL CHECK(octet_length(provenance)<=65536),completed_at bigint NOT NULL CHECK(completed_at>=0)
);
GRANT SELECT ON control_tenant_initializations TO toolgate_control_runtime;
GRANT SELECT,INSERT ON control_tenant_initializations TO toolgate_control_platform;
CREATE TRIGGER control_tenant_initializations_immutable BEFORE UPDATE OR DELETE ON control_tenant_initializations
FOR EACH ROW EXECUTE FUNCTION control_credential_immutable();

-- Fixed search paths and qualified tables prevent caller-controlled object resolution.
CREATE FUNCTION control_tenant_admit(tenant text, write_lock boolean) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE state text;
BEGIN
    IF tenant IS DISTINCT FROM NULLIF(current_setting('app.tenant_id',true),'') THEN RETURN false; END IF;
    IF write_lock THEN
        SELECT status INTO state FROM public.control_tenants WHERE tenant_id=tenant FOR SHARE;
    ELSE
        SELECT status INTO state FROM public.control_tenants WHERE tenant_id=tenant;
    END IF;
    RETURN COALESCE(state='ACTIVE',false);
END;
$$;

CREATE FUNCTION control_tenant_access(write_lock boolean) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE state text;
BEGIN
    IF write_lock THEN
        SELECT status INTO state FROM public.control_tenants
          WHERE tenant_id=NULLIF(current_setting('app.tenant_id',true),'') FOR SHARE;
    ELSE
        SELECT status INTO state FROM public.control_tenants
          WHERE tenant_id=NULLIF(current_setting('app.tenant_id',true),'');
    END IF;
    RETURN COALESCE(state='ACTIVE' OR (state IS NOT NULL AND pg_has_role(session_user,'toolgate_control_platform','MEMBER')),false);
END;
$$;

REVOKE ALL ON FUNCTION control_tenant_admit(text,boolean),control_tenant_access(boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION control_tenant_admit(text,boolean),control_tenant_access(boolean) TO toolgate_control_runtime,toolgate_control_platform;

CREATE FUNCTION control_tenant_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.tenant_id<>OLD.tenant_id THEN RAISE EXCEPTION 'tenant identity is immutable'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER control_tenant_identity_guard BEFORE UPDATE OR DELETE ON control_tenants
FOR EACH ROW EXECUTE FUNCTION control_tenant_identity_guard();

CREATE FUNCTION control_tenant_lifecycle_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.created_at<>OLD.created_at OR NEW.updated_at<OLD.updated_at OR (NEW.status<>OLD.status AND NOT (
        (OLD.status='PROVISIONING' AND NEW.status='ACTIVE') OR (OLD.status='ACTIVE' AND NEW.status='SUSPENDED') OR
        (OLD.status='SUSPENDED' AND NEW.status='ACTIVE'))) THEN RAISE EXCEPTION 'invalid tenant lifecycle transition'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER control_tenant_lifecycle_guard BEFORE UPDATE ON control_tenants
FOR EACH ROW EXECUTE FUNCTION control_tenant_lifecycle_guard();

-- Customer data is denied without the transaction-local context and current lifecycle.
-- Keep this explicit list aligned with db/schema-ownership.json; CI rejects drift.
-- BEGIN TENANT CAPACITY
CREATE TABLE control_tenant_capacity(
 tenant_id varchar(128) NOT NULL PRIMARY KEY,
 stored_bytes bigint NOT NULL DEFAULT 0 CHECK(stored_bytes>=0),
 stored_rows bigint NOT NULL DEFAULT 0 CHECK(stored_rows>=0),
 queued_rows bigint NOT NULL DEFAULT 0 CHECK(queued_rows>=0),
 max_stored_bytes bigint NOT NULL DEFAULT 1073741824 CHECK(max_stored_bytes BETWEEN 4096 AND 1099511627776),
 max_stored_rows bigint NOT NULL DEFAULT 100000 CHECK(max_stored_rows BETWEEN 1 AND 1000000),
 max_queued_rows bigint NOT NULL DEFAULT 256 CHECK(max_queued_rows BETWEEN 1 AND 100000),
 CHECK(stored_rows<=max_stored_rows AND queued_rows<=max_queued_rows AND stored_bytes+queued_rows*16777216<=max_stored_bytes));

GRANT SELECT ON control_tenant_capacity TO toolgate_control_runtime;

GRANT SELECT,INSERT,UPDATE,DELETE ON control_tenant_capacity TO toolgate_control_platform;

CREATE FUNCTION control_capacity_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN INSERT INTO public.control_tenant_capacity(tenant_id) VALUES(NEW.tenant_id);RETURN NEW;END;$$;
REVOKE ALL ON FUNCTION control_capacity_seed() FROM PUBLIC;
CREATE TRIGGER control_capacity_seed AFTER INSERT ON control_tenants FOR EACH ROW EXECUTE FUNCTION control_capacity_seed();

CREATE FUNCTION control_builder_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.kind='TEST' AND (NEW.document::jsonb->>'state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.kind='TEST' AND (OLD.document::jsonb->>'state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_builder_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_builder FOR EACH ROW EXECUTE FUNCTION control_builder_capacity();

CREATE FUNCTION control_mcp_requests_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.record),0)+COALESCE(octet_length(NEW.task),0)+COALESCE(octet_length(NEW.result),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.record),0)+COALESCE(octet_length(OLD.task),0)+COALESCE(octet_length(OLD.result),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_requests_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_requests FOR EACH ROW EXECUTE FUNCTION control_mcp_requests_capacity();

CREATE FUNCTION control_enterprise_invocations_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_enterprise_invocations_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_enterprise_invocations FOR EACH ROW EXECUTE FUNCTION control_enterprise_invocations_capacity();

CREATE FUNCTION control_tool_host_dispatches_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_tool_host_dispatches_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_tool_host_dispatches FOR EACH ROW EXECUTE FUNCTION control_tool_host_dispatches_capacity();

CREATE FUNCTION control_mcp_scans_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_scans_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_scans FOR EACH ROW EXECUTE FUNCTION control_mcp_scans_capacity();

CREATE FUNCTION control_mcp_servers_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_servers_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_servers FOR EACH ROW EXECUTE FUNCTION control_mcp_servers_capacity();

CREATE FUNCTION control_mcp_snapshots_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_snapshots_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_snapshots FOR EACH ROW EXECUTE FUNCTION control_mcp_snapshots_capacity();

CREATE FUNCTION control_mcp_approvals_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_approvals_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_approvals FOR EACH ROW EXECUTE FUNCTION control_mcp_approvals_capacity();

CREATE FUNCTION control_server_packages_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0)+COALESCE(octet_length(NEW.content),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0)+COALESCE(octet_length(OLD.content),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_server_packages_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_server_packages FOR EACH ROW EXECUTE FUNCTION control_server_packages_capacity();

CREATE FUNCTION control_server_documents_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_server_documents_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_server_documents FOR EACH ROW EXECUTE FUNCTION control_server_documents_capacity();

CREATE FUNCTION control_secrets_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.cipher),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.cipher),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_secrets_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_secrets FOR EACH ROW EXECUTE FUNCTION control_secrets_capacity();

CREATE FUNCTION control_invocation_events_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_invocation_events_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_invocation_events FOR EACH ROW EXECUTE FUNCTION control_invocation_events_capacity();

CREATE FUNCTION control_invocation_results_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.payload),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.payload),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_invocation_results_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_invocation_results FOR EACH ROW EXECUTE FUNCTION control_invocation_results_capacity();

CREATE FUNCTION control_policy_bundles_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0)+COALESCE(octet_length(NEW.policy),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0)+COALESCE(octet_length(OLD.policy),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_policy_bundles_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_policy_bundles FOR EACH ROW EXECUTE FUNCTION control_policy_bundles_capacity();
-- END TENANT CAPACITY

DO $tenant_policies$
DECLARE
    tenant_table text;
    operation text;
BEGIN
    FOREACH tenant_table IN ARRAY ARRAY[
        'control_approval_clocks',
        'control_audit',
        'control_authorization_outbox',
        'control_bootstrap_admins',
        'control_builder',
        'control_business_keys',
        'control_configuration_changes',
        'control_console_devices',
        'control_credential_history',
        'control_operation_cancellations',
        'control_invocation_authority',
        'control_endpoint_configurations',
        'control_endpoints',
        'control_enrollments',
        'control_enterprise_adoptions',
        'control_enterprise_approvals',
        'control_enterprise_invocations',
        'control_enterprise_nonces',
        'control_execution_budgets',
        'control_fleet',
        'control_idempotency',
        'control_invocation_event_streams',
        'control_invocation_events',
        'control_insights_facts','control_insights_checkpoints','control_insights_invocations','control_insights_attempts',
        'control_insights_cells','control_insights_admissions','control_insights_gateway_epochs','control_insights_tenant_state','control_insights_spans',
        'control_invocation_results',
        'control_mcp_approvals',
        'control_mcp_requests',
        'control_mcp_scans',
        'control_mcp_servers',
        'control_mcp_snapshots',
        'control_outcome_reconciliations',
        'control_package_provisioning',
        'control_policy_bundles',
        'control_queued_authority',
        'control_record_ids',
        'control_records',
        'control_recovery_floors',
        'control_reviewed_recovery',
        'control_secrets',
        'control_server_deployments',
        'control_server_documents',
        'control_server_packages',
        'control_server_settings',
        'control_tool_host_dispatches',
        'control_tool_hosts'
        ,'control_tenant_authority'
        ,'control_tenant_initializations'
        ,'control_tenant_vault_keys'
        ,'control_tenant_runtime_profiles'
        ,'control_tenant_identity_keys'
        ,'control_tenant_capacity'
    ] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', tenant_table);
        IF tenant_table NOT IN ('control_tenant_authority','control_tenant_initializations') THEN
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id)', tenant_table,tenant_table || '_tenant_owner');
        END IF;
        EXECUTE format('CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.%I FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard()',tenant_table);
        -- Table GRANTs still control the available operations. The restrictive
        -- fence cannot be widened by an additional permissive policy.
        EXECUTE format('CREATE POLICY tenant_access ON public.%I AS PERMISSIVE FOR ALL TO PUBLIC USING (true) WITH CHECK (true)', tenant_table);
        EXECUTE format('CREATE POLICY tenant_fence ON public.%I AS RESTRICTIVE FOR ALL TO PUBLIC USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''') AND public.control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''') AND public.control_tenant_access(true))', tenant_table);
        FOREACH operation IN ARRAY ARRAY['SELECT','INSERT','UPDATE','DELETE'] LOOP
            IF has_table_privilege('toolgate_control_runtime',format('public.%I',tenant_table),operation) THEN
                EXECUTE format('GRANT %s ON public.%I TO toolgate_control_platform',operation,tenant_table);
            END IF;
        END LOOP;
    END LOOP;
END;
$tenant_policies$;
