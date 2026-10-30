// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
/**
 * PENDING CONTRACTS: console-side declarations for the configuration-file, review-policy,
 * enrollment-lookup and reauthorization interfaces that the control plane is adding.
 * Every declaration here moves to the generated `@olo-labs/toolgate-contracts` package
 * (and the operations to `operations.generated.ts` via tools/ui/generate.py) once those
 * schemas and OpenAPI paths are published; delete each one as its generated twin lands.
 * Nothing here is authoritative: Control validates every field.
 */
import type {ClientPlatform,ControlSnapshot,ControlWorkloadBinding,EnterpriseManagementRule} from '@olo-labs/toolgate-contracts';
import type {ControlSettingsView,ControlServerSettingsPatch,DeviceApproval,GatewaySettings} from '@olo-labs/toolgate-contracts/v2';

/** Operations not yet in control-v1.yaml (Control) or the Quickstart supervisor API. */
export const pendingOperations={
  getReviewPolicy:{method:'GET',path:'/api/control/v1/review-policy'},
  lookupEndpointEnrollment:{method:'POST',path:'/api/control/v1/endpoint/enrollments/lookup'},
  reauthorizeQuickstartWorkload:{method:'POST',path:'/api/quickstart/v1/workloads/{id}/reauthorize'},
  quickstartLoginHints:{method:'GET',path:'/api/quickstart/v1/login-hints'},
} as const;

export type RequiredReviews=0|1|2;
/** GET /api/control/v1/review-policy, readable by every signed-in console user. */
export interface ReviewPolicy {readonly requiredReviews:RequiredReviews}
/** The settings group that holds the configuration review threshold. */
export interface ConfigurationSettings {readonly requiredReviews:RequiredReviews}
export type PendingServerSettings=ControlSettingsView&{readonly configuration?:ConfigurationSettings};
export type PendingServerSettingsPatch=ControlServerSettingsPatch&{readonly configuration?:ConfigurationSettings};

/** A user id referenced by exported records, with the team ids that user belonged to. IDs only. */
export interface RequiredIdentity {readonly id:string;readonly teamIds:readonly string[]}
export type IdentityAction='EXISTING'|'MAP'|'PLACEHOLDER';
export interface IdentityResolution {readonly action:IdentityAction;readonly targetUserId?:string;readonly teamIds:readonly string[]}
/** The configuration file: the snapshot without users and runtime-only kinds, plus settings and required identities. */
export type ConfigurationFile=Omit<ControlSnapshot,'users'|'identityBindings'|'deviceEvidence'>&Partial<Pick<ControlSnapshot,'users'|'identityBindings'|'deviceEvidence'>>&{
  readonly settings?:{readonly gateway?:GatewaySettings;readonly deviceApproval?:DeviceApproval;readonly configuration?:ConfigurationSettings};
  readonly requiredIdentities?:readonly RequiredIdentity[];
};
/** Extra import envelope fields next to snapshot, mode and dryRun. */
export interface ImportIdentityOptions {
  readonly identityResolution:Readonly<Record<string,IdentityResolution>>;
  readonly removeUnlistedMemberships:boolean;
}
/** 409 body when any required identity is unresolved (dry runs return the same list). */
export const UNRESOLVED_IDENTITIES='UNRESOLVED_IDENTITIES';

/** POST /api/control/v1/endpoint/enrollments/lookup {userCode}: one pending enrollment the caller may approve. */
export interface EnrollmentLookup {readonly enrollmentId:string;readonly deviceId:string;readonly platform:ClientPlatform;readonly keyFingerprint:string;readonly expiresAtUnixMs:number}

/** Workload binding fields for claims and reauthorization after an identity-mapped import. */
export type PendingWorkloadBinding=ControlWorkloadBinding&{readonly needsReauthorization?:boolean;readonly passClaims?:Readonly<Record<string,readonly string[]>>};
/** Management rule scope: approve-device on devices the caller owns, placed in the rule's one device group. */
export type PendingManagementRule=EnterpriseManagementRule;

/** Quickstart: POST /api/quickstart/v1/workloads/{id}/reauthorize returns the new agent token once. */
export interface ReauthorizedWorkload {readonly token:string}
/** Quickstart: unauthenticated GET /api/quickstart/v1/login-hints, showOnLogin accounts only. */
export interface LoginHints {readonly accounts:readonly {readonly username:string;readonly password:string;readonly agentToken?:string}[];readonly refreshAt:string|null}
