-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0

-- Rebuild only the business-key outcome CHECK; all values and existing indexes/guards are retained.
CREATE TEMP TABLE control_business_keys_v7_copy AS SELECT * FROM control_business_keys;
-- statement
DROP TABLE control_business_keys;
-- statement
CREATE TABLE control_business_keys(tenant_id TEXT REFERENCES control_tenants(tenant_id) NOT NULL,tool_id TEXT NOT NULL CHECK(length(tool_id) BETWEEN 1 AND 256),namespace TEXT NOT NULL CHECK(namespace IN('AGENT','DELEGATED_USER','TENANT')),namespace_identity TEXT NOT NULL CHECK(length(namespace_identity)<=128),business_key_hash TEXT NOT NULL CHECK(length(business_key_hash)=64 AND business_key_hash NOT GLOB '*[^0-9a-f]*'),source TEXT NOT NULL CHECK(source IN('HEADER','ARGUMENT','DEDUPE_WINDOW')),fingerprint TEXT NOT NULL CHECK(length(fingerprint)=71 AND substr(fingerprint,1,7)='sha256:' AND substr(fingerprint,8) NOT GLOB '*[^0-9a-f]*'),caller_agent_id TEXT NOT NULL,caller_user_id TEXT,invocation_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),dedupe_expires_at INTEGER CHECK(dedupe_expires_at BETWEEN 0 AND 9007199254740991),retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),outcome TEXT CHECK(outcome IN('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED','OUTCOME_UNKNOWN','PARTIAL')),tombstoned_at INTEGER CHECK(tombstoned_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,tool_id,namespace,namespace_identity,business_key_hash),CHECK((namespace='TENANT')=(namespace_identity='')),CHECK((source='DEDUPE_WINDOW')=(dedupe_expires_at IS NOT NULL)),CHECK(dedupe_expires_at IS NULL OR dedupe_expires_at>created_at),CHECK(retain_until>=created_at),CHECK(tombstoned_at IS NULL OR outcome IS NOT NULL));
-- statement
INSERT INTO control_business_keys SELECT * FROM control_business_keys_v7_copy;
-- statement
DROP TABLE control_business_keys_v7_copy;
-- statement
CREATE INDEX control_business_key_invocation ON control_business_keys(tenant_id,invocation_id);
-- statement
CREATE INDEX control_business_key_retention ON control_business_keys(tenant_id,retain_until) WHERE tombstoned_at IS NULL;
-- statement
CREATE TRIGGER control_business_key_delete BEFORE DELETE ON control_business_keys BEGIN SELECT RAISE(ABORT,'business keys are tombstoned, never deleted'); END;
-- statement
CREATE TRIGGER control_business_key_update BEFORE UPDATE ON control_business_keys WHEN ((NEW.tenant_id IS NOT OLD.tenant_id OR NEW.tool_id IS NOT OLD.tool_id OR NEW.namespace IS NOT OLD.namespace OR NEW.namespace_identity IS NOT OLD.namespace_identity OR NEW.business_key_hash IS NOT OLD.business_key_hash) OR NEW.source<>OLD.source OR
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
 AND o.state IN('FAILED','SUCCEEDED')))) OR (NEW.invocation_id=OLD.invocation_id AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS NOT OLD.fingerprint OR NEW.caller_agent_id IS NOT OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id OR NEW.dedupe_expires_at IS NOT OLD.dedupe_expires_at OR NEW.retain_until IS NOT OLD.retain_until) OR NEW.created_at<>OLD.created_at
 OR (NEW.outcome IS NOT OLD.outcome AND OLD.outcome IS NOT NULL
 AND NOT(OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED'))) OR (NEW.tombstoned_at IS NOT OLD.tombstoned_at AND
 (NEW.tombstoned_at IS NULL OR NEW.tombstoned_at<OLD.retain_until OR
 OLD.outcome IS NULL OR OLD.outcome='OUTCOME_UNKNOWN' OR
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=OLD.tenant_id
 AND o.operation_id=OLD.invocation_id AND o.legacy=0 AND o.state IN('FAILED','SUCCEEDED'))))))) AND NOT(NEW.tenant_id IS OLD.tenant_id AND NEW.tool_id IS OLD.tool_id AND NEW.namespace IS OLD.namespace AND NEW.namespace_identity IS OLD.namespace_identity AND NEW.business_key_hash IS OLD.business_key_hash AND NEW.source IS OLD.source AND NEW.fingerprint IS OLD.fingerprint AND NEW.caller_agent_id IS OLD.caller_agent_id AND NEW.caller_user_id IS OLD.caller_user_id AND NEW.invocation_id IS OLD.invocation_id AND NEW.created_at IS OLD.created_at AND NEW.dedupe_expires_at IS OLD.dedupe_expires_at AND NEW.retain_until IS OLD.retain_until AND NEW.tombstoned_at IS OLD.tombstoned_at
 AND NEW.outcome IS NULL AND OLD.outcome IN('FAILED','EXPIRED','OUTCOME_UNKNOWN')
 AND OLD.tombstoned_at IS NULL
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision=i.revision AND i.state='QUEUED' AND o.state='RUNNING'
 AND OLD.retain_until>r.created_at)) BEGIN SELECT RAISE(ABORT,'business key identity, retention or recovery proof is immutable'); END;
-- statement
CREATE TRIGGER control_business_keys_tenant_immutable BEFORE UPDATE OF tenant_id ON control_business_keys WHEN NEW.tenant_id<>OLD.tenant_id BEGIN SELECT RAISE(ABORT,'tenant identity is immutable'); END;
-- statement
-- Dynamic attempt manifests: membership follows authorization; closure grants no execution authority.
CREATE TABLE control_attempt_effects (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL, attempt_id TEXT NOT NULL, effect_id TEXT NOT NULL,
 PRIMARY KEY(tenant_id,operation_id,attempt_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,effect_id) REFERENCES control_effects(tenant_id,operation_id,effect_id)
);
-- statement
INSERT INTO control_attempt_effects(tenant_id,operation_id,attempt_id,effect_id)
 SELECT DISTINCT tenant_id,operation_id,attempt_id,effect_id FROM control_effect_generations;
-- statement
UPDATE control_tenant_capacity SET stored_rows=stored_rows+(SELECT count(*) FROM control_attempt_effects m WHERE m.tenant_id=control_tenant_capacity.tenant_id),
 stored_bytes=stored_bytes+COALESCE((SELECT sum(length(m.effect_id)) FROM control_attempt_effects m WHERE m.tenant_id=control_tenant_capacity.tenant_id),0);
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
CREATE TRIGGER effect_manifest_guard BEFORE INSERT ON control_effect_generations
 WHEN NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND json_extract(i.document,'$.reservedNonce')=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),0)) BEGIN SELECT RAISE(ABORT,'EFFECT_MANIFEST_SEALED or inactive execution authority'); END;
-- statement
CREATE TRIGGER attempt_effect_guard BEFORE INSERT ON control_attempt_effects
 WHEN NOT(COALESCE(EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.operation_id AND g.attempt_id=NEW.attempt_id AND g.effect_id=NEW.effect_id)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),0)) BEGIN SELECT RAISE(ABORT,'manifest membership requires open admitted generation'); END;
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
CREATE TRIGGER effect_manifest_member AFTER INSERT ON control_effect_generations BEGIN
 INSERT INTO control_attempt_effects(tenant_id,operation_id,attempt_id,effect_id)
 VALUES(NEW.tenant_id,NEW.operation_id,NEW.attempt_id,NEW.effect_id) ON CONFLICT DO NOTHING; END;
-- statement
CREATE TRIGGER attempt_effects_update_immutable BEFORE UPDATE ON control_attempt_effects BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_effects_delete_immutable BEFORE DELETE ON control_attempt_effects BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_effects_capacity AFTER INSERT ON control_attempt_effects BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(NEW.effect_id) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER attempt_manifest_seals_update_immutable BEFORE UPDATE ON control_attempt_manifest_seals BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_manifest_seals_delete_immutable BEFORE DELETE ON control_attempt_manifest_seals BEGIN SELECT RAISE(ABORT,'manifest history is immutable'); END;
-- statement
CREATE TRIGGER attempt_manifest_seals_capacity AFTER INSERT ON control_attempt_manifest_seals BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;

-- statement
CREATE TRIGGER effect_manifest_transition_guard BEFORE UPDATE ON control_effect_generations
 WHEN OLD.state<>NEW.state AND OLD.state IN('AUTHORIZED','SENDING') AND NEW.state IN('SENDING','CONSUMED')
 AND NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND json_extract(i.document,'$.reservedNonce')=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),0))
 BEGIN SELECT RAISE(ABORT,'EFFECT_MANIFEST_SEALED or inactive execution authority'); END;

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
 AND (g.state NOT IN('RELEASED','COMPLETED','FAILED')
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
CREATE TRIGGER attempt_settlement_delete_immutable BEFORE DELETE ON control_attempt_settlements BEGIN SELECT RAISE(ABORT,'attempt settlement is immutable'); END;
-- statement
CREATE TRIGGER attempt_settlement_capacity AFTER INSERT ON control_attempt_settlements BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;

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
 (g.state NOT IN('RELEASED','COMPLETED','FAILED') OR (g.state IN('COMPLETED','FAILED') AND NOT EXISTS(
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
CREATE TRIGGER operation_outcome_delete_immutable BEFORE DELETE ON control_operation_outcomes BEGIN SELECT RAISE(ABORT,'derived outcome is immutable'); END;
-- statement
CREATE TRIGGER operation_outcome_capacity AFTER INSERT ON control_operation_outcomes BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;

-- statement
CREATE TRIGGER execution_reconciliation_insert_guard BEFORE INSERT ON control_enterprise_nonces
 WHEN NEW.purpose='EXECUTION' AND (EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED')
 OR EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.invocation_id AND g.state='CONFLICT'))
 BEGIN SELECT RAISE(ABORT,'reconciliation fences new execution authority'); END;
-- statement
CREATE TRIGGER execution_reconciliation_consume_guard BEFORE UPDATE ON control_enterprise_nonces
 WHEN NEW.purpose='EXECUTION' AND OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND (EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED')
 OR EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.invocation_id AND g.state='CONFLICT'))
 BEGIN SELECT RAISE(ABORT,'reconciliation fences new execution authority'); END;

-- statement
DROP TRIGGER control_business_key_update;
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
DROP TRIGGER control_business_key_history_insert;
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
ALTER TABLE control_execution_evidence ADD COLUMN qualified_report INTEGER NOT NULL DEFAULT 0 CHECK(qualified_report IN(0,1));
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
CREATE TABLE control_operation_review_owners(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),operation_id TEXT NOT NULL,
 directory_revision BIGINT NOT NULL CHECK(directory_revision>=0),
 source_digest TEXT NOT NULL CHECK(length(source_digest)=64 AND source_digest NOT GLOB '*[^a-f0-9]*'),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=16384 AND json_valid(document) AND json_type(document)='object'),
 PRIMARY KEY(tenant_id,operation_id),FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
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
CREATE TRIGGER review_owners_delete_immutable BEFORE DELETE ON control_operation_review_owners BEGIN SELECT RAISE(ABORT,'review owners are immutable'); END;
-- statement
CREATE TRIGGER review_owners_capacity AFTER INSERT ON control_operation_review_owners BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;

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
 AND (g.state NOT IN('RELEASED','COMPLETED','FAILED') OR (g.state IN('COMPLETED','FAILED') AND (g.consumer IS NULL OR g.claim_token IS NULL OR NOT EXISTS(
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
CREATE TRIGGER attempt_review_delete_immutable BEFORE DELETE ON control_attempt_reviews BEGIN SELECT RAISE(ABORT,'attempt review is immutable'); END;
-- statement
CREATE TRIGGER attempt_review_capacity AFTER INSERT ON control_attempt_reviews BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;

-- statement
CREATE TRIGGER reconciliation_insert_guard BEFORE INSERT ON control_outcome_reconciliations WHEN NOT(COALESCE(json_extract(NEW.document,'$.id')=NEW.reconciliation_id AND json_extract(NEW.document,'$.revision')=NEW.revision AND NEW.revision BETWEEN 1 AND 9007199254740991 AND length(json_extract(NEW.document,'$.requesterUserId')) BETWEEN 1 AND 128 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=json_extract(NEW.document,'$.invocationId')) AND NEW.revision=1 AND json_extract(NEW.document,'$.state')='PENDING' AND json_extract(NEW.document,'$.reviewerUserId') IS NULL,0))
 BEGIN SELECT RAISE(ABORT,'reconciliation must begin pending with valid identity'); END;
-- statement
CREATE TRIGGER reconciliation_update_guard BEFORE UPDATE ON control_outcome_reconciliations WHEN NOT(COALESCE(json_extract(NEW.document,'$.id')=NEW.reconciliation_id AND json_extract(NEW.document,'$.revision')=NEW.revision AND NEW.revision BETWEEN 1 AND 9007199254740991 AND length(json_extract(NEW.document,'$.requesterUserId')) BETWEEN 1 AND 128 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=json_extract(NEW.document,'$.invocationId')) AND NEW.tenant_id=OLD.tenant_id AND NEW.reconciliation_id=OLD.reconciliation_id AND NEW.revision=OLD.revision+1 AND json_extract(OLD.document,'$.state')='PENDING' AND json_extract(NEW.document,'$.state') IN('APPLIED','DENIED') AND length(json_extract(NEW.document,'$.reviewerUserId')) BETWEEN 1 AND 128 AND json_extract(NEW.document,'$.id') IS json_extract(OLD.document,'$.id') AND json_extract(NEW.document,'$.invocationId') IS json_extract(OLD.document,'$.invocationId') AND json_extract(NEW.document,'$.requesterUserId') IS json_extract(OLD.document,'$.requesterUserId') AND json_extract(NEW.document,'$.requestDigest') IS json_extract(OLD.document,'$.requestDigest') AND json_extract(NEW.document,'$.request') IS json_extract(OLD.document,'$.request'),0))
 BEGIN SELECT RAISE(ABORT,'reconciliation proposal and decision are immutable'); END;
-- statement
CREATE TRIGGER reconciliation_delete_guard BEFORE DELETE ON control_outcome_reconciliations
 BEGIN SELECT RAISE(ABORT,'reconciliation history is retained'); END;
