-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
ALTER TABLE control_enterprise_nonces ADD COLUMN purpose TEXT NOT NULL DEFAULT 'LEGACY' CHECK(purpose IN('LEGACY','EXECUTION','SERVICE'));
-- statement
CREATE INDEX control_nonce_operation_purpose ON control_enterprise_nonces(tenant_id,invocation_id,purpose);
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
INSERT INTO control_operations
 SELECT tenant_id,invocation_id,request_digest,
 CASE WHEN state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 'RUNNING' ELSE 'OUTCOME_UNKNOWN' END,
 1,revision,created_at FROM control_enterprise_invocations;
-- statement
INSERT INTO control_operation_attempts
 SELECT n.tenant_id,n.nonce,n.invocation_id,n.evaluation_digest,n.authorization_epoch,n.issued_at,n.expires_at
 FROM control_enterprise_nonces n JOIN control_operations o ON o.tenant_id=n.tenant_id AND o.operation_id=n.invocation_id WHERE n.purpose<>'SERVICE';
-- statement
INSERT INTO control_attempt_snapshots SELECT n.tenant_id,n.invocation_id,n.nonce,i.revision,(CASE WHEN n.nonce=json_extract(i.document,'$.reservedNonce') THEN i.state WHEN n.consumed_at IS NOT NULL THEN 'OUTCOME_UNKNOWN' ELSE 'EXPIRED' END),json_set(i.document,'$.id',i.invocation_id,'$.reservedNonce',n.nonce,'$.state',(CASE WHEN n.nonce=json_extract(i.document,'$.reservedNonce') THEN i.state WHEN n.consumed_at IS NOT NULL THEN 'OUTCOME_UNKNOWN' ELSE 'EXPIRED' END),'$.revision',i.revision) FROM control_enterprise_nonces n JOIN control_enterprise_invocations i ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id;
-- statement
CREATE TRIGGER control_operation_attempts_immutable_update BEFORE UPDATE ON control_operation_attempts BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_operation_attempts_immutable_delete BEFORE DELETE ON control_operation_attempts BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_attempt_snapshots_immutable_update BEFORE UPDATE ON control_attempt_snapshots BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_attempt_snapshots_immutable_delete BEFORE DELETE ON control_attempt_snapshots BEGIN SELECT RAISE(ABORT,'attempt journal is immutable'); END;
-- statement
CREATE TRIGGER control_operations_delete BEFORE DELETE ON control_operations BEGIN SELECT RAISE(ABORT,'operation is retained'); END;
-- statement
CREATE TRIGGER control_operations_guard BEFORE UPDATE ON control_operations WHEN
 NEW.tenant_id<>OLD.tenant_id OR NEW.operation_id<>OLD.operation_id OR NEW.request_digest<>OLD.request_digest
 OR NEW.created_at<>OLD.created_at OR NEW.legacy<>OLD.legacy OR NEW.revision<>OLD.revision+1
 OR (OLD.state='FAILED' AND NEW.state<>'FAILED')
 BEGIN SELECT RAISE(ABORT,'operation identity or finality is immutable'); END;
-- statement
CREATE TRIGGER control_attempt_reservation_guard BEFORE INSERT ON control_enterprise_nonces WHEN NEW.purpose<>'SERVICE' AND (
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id)
 OR EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.purpose<>'SERVICE'
 AND (n.consumed_at IS NOT NULL OR n.expires_at>NEW.issued_at OR NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce)))
 OR EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED'))
 BEGIN SELECT RAISE(ABORT,'predecessor is not proven expired and unconsumed'); END;
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
CREATE TRIGGER control_attempt_binding_guard BEFORE INSERT ON control_operation_attempts WHEN
 NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.nonce=NEW.attempt_id
 AND n.purpose<>'SERVICE' AND n.invocation_id=NEW.operation_id AND n.evaluation_digest=NEW.evaluation_digest AND n.authorization_epoch=NEW.authorization_epoch
 AND n.issued_at=NEW.issued_at AND n.expires_at=NEW.expires_at)
 BEGIN SELECT RAISE(ABORT,'attempt must bind its original permit'); END;
-- statement
CREATE TRIGGER control_snapshot_transition_guard BEFORE INSERT ON control_attempt_snapshots WHEN
 EXISTS(SELECT 1 FROM control_attempt_snapshots s WHERE s.tenant_id=NEW.tenant_id AND s.attempt_id=NEW.attempt_id
 AND (s.revision>=NEW.revision OR (s.state IN('SUCCEEDED','FAILED','PARTIAL','CANCELLED','EXPIRED') AND s.state<>NEW.state)
 OR (s.state='OUTCOME_UNKNOWN' AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING'))))
 BEGIN SELECT RAISE(ABORT,'attempt cannot reopen or rewrite history'); END;
