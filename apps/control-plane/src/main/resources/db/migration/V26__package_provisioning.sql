-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Deployment-time package provisioning (plan.md §7, §8): the latest ProvisioningStatus of each applied profile, with
-- the digest of the profile that produced it and a generation that grows on every change, so a repeated identical
-- deployment is a no-op and a changed profile is recorded against the one it replaced.
CREATE TABLE control_package_provisioning(tenant_id varchar(128) NOT NULL,profile_id varchar(128) NOT NULL,
    generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),profile_digest char(71) NOT NULL CHECK(profile_digest ~ '^sha256:[a-f0-9]{64}$'),
    document text NOT NULL CHECK(octet_length(document)<=262144),PRIMARY KEY(tenant_id,profile_id));

GRANT SELECT,INSERT,UPDATE ON control_package_provisioning TO toolgate_control_runtime;
