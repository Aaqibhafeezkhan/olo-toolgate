-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Keep installation ownership outside replacement-imported directory records.
CREATE TABLE control_bootstrap_admins (
    tenant_id varchar(128) PRIMARY KEY,
    user_id varchar(128) NOT NULL
);
-- statement
-- Existing Quickstart installations retain their account/email/roles unchanged.
-- Only an active, reviewed setup identity can be identified safely on upgrade.
INSERT INTO control_bootstrap_admins(tenant_id,user_id)
SELECT b.tenant_id,b.document->>'userId'
FROM control_records b JOIN control_records u ON u.tenant_id=b.tenant_id
    AND u.kind='USER' AND u.record_id=b.document->>'userId'
WHERE b.kind='IDENTITY_BINDING' AND b.record_id='installed-admin'
    AND b.document->>'registrationReason'='REVIEWED_INSTALLATION'
    AND b.document->>'subject'='admin' AND (u.document->>'enabled')::boolean;
-- statement
GRANT SELECT,INSERT ON control_bootstrap_admins TO toolgate_control_runtime;
