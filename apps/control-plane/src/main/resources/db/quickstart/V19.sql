-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Deployment-time package provisioning; see db/migration/V26__package_provisioning.sql.
CREATE TABLE control_package_provisioning(tenant_id TEXT NOT NULL,profile_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71 AND substr(profile_digest,1,7)='sha256:'),
    document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=262144),PRIMARY KEY(tenant_id,profile_id));
