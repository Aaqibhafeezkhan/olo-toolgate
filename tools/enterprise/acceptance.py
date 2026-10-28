# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Trace all 96 acceptance cases to named executable evidence, without hiding gaps.

Passing representative automated boundaries are recorded separately from a full
integrated release run. This report is not an enterprise capacity certification.
Optional nesting/offline execution are deliberately unsupported and fail closed.
"""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]

# Each method name identifies an actual test, not a substring or whole-suite claim.
# Multiple entries are conjunctive: all referenced tests must pass without skips.
CASES = {
 'UC01': ['humanExecutionRequiresOnlyHumanGrantAndBinding', 'actionRulesDoNotPromoteReadToWrite'],
 'UC02': ['disabledIdentityCannotBeApproved', 'roleInheritanceRetainsIndependentTeamWitnessesAndRevokesLive'],
 'UC03': ['unrelatedTeamsCannotShareGrantAndDelegation', 'unrelatedToolDeviceGrantsAreNotMultiplied'],
 'UC04': ['roleInheritanceRetainsIndependentTeamWitnessesAndRevokesLive'],
 'UC05': ['roleInheritanceRetainsIndependentTeamWitnessesAndRevokesLive', 'blockOverridesEveryGrant'],
 'UC06': ['lastMembershipCannotBeRemovedByDirectGroupEditing', 'roleInheritanceRetainsIndependentTeamWitnessesAndRevokesLive'],
 'UC07': ['roleInheritanceRetainsIndependentTeamWitnessesAndRevokesLive'],
 'UC08': ['jitGrantValidityHasExactExpiryAndNoImplicitExtension', 'resourcePrefixRespectsSegmentBoundary'],
 'UC09': ['managementRoleCannotBeRuntimeGrantSource', 'portalAdministratorNeedsExplicitSecretPurposeManagement'],
 'UC10': ['managementRoleCannotBeRuntimeGrantSource', 'externalRoleClaimWithoutBoundManagementMembershipDoesNotAuthorize'],
 'UC11': ['actionRulesDoNotPromoteReadToWrite'],
 'UC12': ['blockOverridesEveryGrant', 'approvedOperationNeverManufacturesMissingGrant'],
 'UC13': ['eachRequiredAuthorityDimensionIndependentlyDenies', 'humanExecutionRequiresOnlyHumanGrantAndBinding'],
 'UC14': ['everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership', 'makerCheckerPreviewAndAtomicApply'],
 'UC15': ['toolMoveAndDeviceRemovalCannotRetainOriginalBinding'],
 'UC16': ['eachRequiredAuthorityDimensionIndependentlyDenies', 'legacyIndividualAssignmentsAreRejected'],
 'UC17': ['multipleExplicitBindingsRemainIndependent'],
 'UC18': ['actionRulesDoNotPromoteReadToWrite', 'everyAffectedResourceNeedsCompletePermission'],
 'UC19': ['sourceAndDestinationAndAllBatchMembersAreExtracted', 'filesystemAndUrlEscapesRejectBeforePermissionEvaluation'],
 'UC20': ['schemasSecretsPermissionsResourcesAndCredentialBoundary'],
 'UC21': ['signedPermitBindsTargetVersionsArgumentsAndFullResourceSet', 'schemasSecretsPermissionsResourcesAndCredentialBoundary'],
 'UC22': ['eachRequiredAuthorityDimensionIndependentlyDenies', 'directoryRevocationBetweenReservationAndEffectDenies'],
 'UC23': ['enrollmentBindsOwnerKeyAndDurableSequenceAndRevocation', 'everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership'],
 'UC24': ['concurrentMembershipChangesHaveOneWinnerAndNoOrphan', 'makerCheckerPreviewAndAtomicApply'],
 'UC25': ['overlappingDeviceGroupsDoNotBridgeBinding'],
 'UC26': ['completeDelegatedPathAllowsWithoutOwnerEquality', 'wrongCertificateDeviceAndTenantCannotConsume'],
 'UC27': ['eachRequiredAuthorityDimensionIndependentlyDenies'],
 'UC28': ['unrelatedToolDeviceGrantsAreNotMultiplied'],
 'UC29': ['completeDelegatedPathAllowsWithoutOwnerEquality', 'trustedNetworkPostureRegionAndHoursAreConjunctive'],
 'UC30': ['toolMoveAndDeviceRemovalCannotRetainOriginalBinding', 'queuedBuildAndDeploymentCannotOutliveTheirCreatorsGroupAuthority'],
 'UC31': ['deniedExpiredWrongCodeInvalidCsrAndDisabledOwnersFailClosed', 'enrollmentBindsOwnerKeyAndDurableSequenceAndRevocation'],
 'UC32': ['lastMembershipCannotBeRemovedByDirectGroupEditing', 'membershipReplacementRetainsExactlyOnePrimaryToolGroup'],
 'UC33': ['eachRequiredAuthorityDimensionIndependentlyDenies', 'overlappingDeviceGroupsDoNotBridgeBinding'],
 'UC34': ['signedPermitBindsTargetVersionsArgumentsAndFullResourceSet', 'schemasSecretsPermissionsResourcesAndCredentialBoundary'],
 'UC35': ['eachRequiredAuthorityDimensionIndependentlyDenies'],
 'UC36': ['eachRequiredAuthorityDimensionIndependentlyDenies', 'humanExecutionRequiresOnlyHumanGrantAndBinding'],
 'UC37': ['defaultMembershipCreatesNoGrant', 'emptySelectorsDoNotMeanWildcard'],
 'UC38': ['unrelatedToolDeviceGrantsAreNotMultiplied', 'actionRulesDoNotPromoteReadToWrite'],
 'UC39': ['unrelatedTeamsCannotShareGrantAndDelegation'],
 'UC40': ['administrativeOwnerSuppliesNoRightsAndDependencyIsExplicit'],
 'UC41': ['serviceModeNeedsExplicitServiceGrantAndCapability'],
 'UC42': ['serviceIdentityCannotReuseDelegatedCredential', 'crossTenantCannotUseSameIdentifiers', 'delegatedCredentialCannotAttachToNewHumanSession'],
 'UC43': ['downstreamAgentCannotExceedAnyUpstreamCeilingOrReuseIdentity'],
 'UC44': ['directoryRevocationBetweenReservationAndEffectDenies', 'olderTokenWithoutEpochCannotSurviveUserReenablement'],
 'UC45': ['exactInvocationIsDurableAndIdempotentWithoutStoringArguments', 'permitCannotBeConsumedTwiceOrReservedAgainAfterConsumption'],
 'UC46': ['everyAskObligationNeedsIndependentReview', 'requesterCannotReviewOwnOperationEvenWithManagementRole'],
 'UC47': ['approvalRevocationAfterReservationStopsConsumption', 'approvedOperationNeverManufacturesMissingGrant'],
 'UC48': ['jitGrantValidityHasExactExpiryAndNoImplicitExtension', 'managementCeilingRejectsAccessExpansionAndDirectRuntimeRoles'],
 'UC49': ['independentlySignedReviewAppliesOnceAndNeverResurrectsExpiredAuthority', 'recoveryCannotAddRuntimePermissionsEvenWithGenuineReviewSignatures'],
 'UC50': ['trustedNetworkPostureRegionAndHoursAreConjunctive', 'missingRequiredTrustEvidenceDeniesButUnconditionalGrantNeedsNoUnusedAttestation'],
 'UC51': ['trustedNetworkPostureRegionAndHoursAreConjunctive', 'multipleExplicitBindingsRemainIndependent'],
 'UC52': ['highRiskRequiresOnlineAndAuthoritativeAmountAndQuota', 'durableGroupBudgetHasOneWinnerAcrossConcurrentSubmissions'],
 'UC53': ['makerCheckerPreviewAndAtomicApply', 'requesterCannotReviewOwnOperationEvenWithManagementRole'],
 'UC54': ['directoryRevocationBetweenReservationAndEffectDenies', 'compilerIsDeterministicAndGroupSnapshotDoesNotFlattenIndividuals'],
 'UC55': ['concurrentGraphChangeInvalidatesReview', 'concurrentMembershipChangesHaveOneWinnerAndNoOrphan'],
 'UC56': ['crossTenantCannotUseSameIdentifiers', 'wrongCertificateDeviceAndTenantCannotConsume'],
 'UC57': ['highRiskRequiresOnlineAndAuthoritativeAmountAndQuota'],
 'UC58': ['highRiskRequiresOnlineAndAuthoritativeAmountAndQuota'],
 'UC59': ['watchdogCommitsUnknownEvenWhenSubsequentAuthorityIsRevoked', 'queuedBuildAndDeploymentCannotOutliveTheirCreatorsGroupAuthority'],
 'UC60': ['independentlySignedReviewAppliesOnceAndNeverResurrectsExpiredAuthority', 'identifiersRemainRetiredAfterDeletion'],
 'UC61': ['individualProvenanceReportsCompleteTuplesWithoutCreatingDirectPermissions'],
 'UC62': ['shadowComparesCapturedLegacyDecisionWithoutApplyingGrantsOrConsumingAuthority'],
 'UC63': ['unknownMissingDuplicateAndMalformedFieldsReject', 'mandatoryMembershipAndPrimaryToolGroupAreValidated'],
 'UC64': ['identifiersRemainRetiredAfterDeletion', 'everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership'],
 'UC65': ['concurrentVerifiedIntakeCreatesOneDisabledIdentityWithMembership'],
 'UC66': ['olderTokenWithoutEpochCannotSurviveUserReenablement', 'defaultMembershipCreatesNoGrant'],
 'UC67': ['repeatedVerifiedLoginMetadataDoesNotRevokeUnrelatedApprovals', 'concurrentVerifiedIntakeCreatesOneDisabledIdentityWithMembership'],
 'UC68': ['invalidAuthenticationDoesNotCreateUsers'],
 'UC69': ['everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership', 'completeExportCanEnterReviewedImportWithoutRotatingUnchangedCredentials'],
 'UC70': ['concurrentVerifiedIntakeCreatesOneDisabledIdentityWithMembership'],
 'UC71': ['everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership', 'defaultMembershipCreatesNoGrant'],
 'UC72': ['groupRoleTypeCannotBeMisassigned', 'completeDelegatedPathAllowsWithoutOwnerEquality'],
 'UC73': ['unrelatedAgentGroupsCannotShareCapabilityAndDelegation', 'disabledAgentGroupRemovesItsPathWhileIndependentPathsSurvive'],
 'UC74': ['unrelatedAgentGroupsCannotShareCapabilityAndDelegation'],
 'UC75': ['disabledAgentGroupRemovesItsPathWhileIndependentPathsSurvive'],
 'UC76': ['everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership', 'lastMembershipCannotBeRemovedByDirectGroupEditing'],
 'UC77': ['legacyIndividualAssignmentsAreRejected', 'completeDelegatedPathAllowsWithoutOwnerEquality'],
 'UC78': ['serviceModeNeedsExplicitServiceGrantAndCapability', 'serviceIdentityCannotReuseDelegatedCredential'],
 'UC79': ['serviceIdentityCannotReuseDelegatedCredential', 'downstreamAgentCannotExceedAnyUpstreamCeilingOrReuseIdentity'],
 'UC80': ['legacyIndividualAssignmentsAreRejected'],
 'AC01': ['eachRequiredAuthorityDimensionIndependentlyDenies'],
 'AC02': ['overlappingDeviceGroupsDoNotBridgeBinding', 'unrelatedToolDeviceGrantsAreNotMultiplied'],
 'AC03': ['humanExecutionRequiresOnlyHumanGrantAndBinding', 'serviceModeNeedsExplicitServiceGrantAndCapability', 'completeDelegatedPathAllowsWithoutOwnerEquality'],
 'AC04': ['askObligationsAccumulateAcrossAllowPaths', 'approvedOperationNeverManufacturesMissingGrant'],
 'AC05': ['blockOverridesEveryGrant', 'everyAffectedResourceNeedsCompletePermission'],
 'AC06': ['directoryRevocationBetweenReservationAndEffectDenies', 'queuedBuildAndDeploymentCannotOutliveTheirCreatorsGroupAuthority'],
 'AC07': ['concurrentMembershipChangesHaveOneWinnerAndNoOrphan', 'realTransactionsAuditReplayRestartAndImmutableTriggers'],
 'AC08': ['publicationRollbackConcurrencyAuditAndSigningFailureAreAtomic'],
 'AC09': ['completeDelegatedPathAllowsWithoutOwnerEquality', 'wrongCertificateDeviceAndTenantCannotConsume'],
 'AC10': ['signedPermitBindsTargetVersionsArgumentsAndFullResourceSet', 'parallelReplicasOnlyConsumeOneNonce', 'unknownExternalOutcomeCannotTriggerBlindRetry'],
 'AC11': ['individualProvenanceReportsCompleteTuplesWithoutCreatingDirectPermissions', 'makerCheckerPreviewAndAtomicApply'],
 'AC12': ['exportedSpansExcludeRawUrlsEventsAndExceptionText', 'exactInvocationIsDurableAndIdempotentWithoutStoringArguments'],
 'AC13': ['everyIndividualCreationAndGroupDeletionKeepsMandatoryMembership', 'completeExportCanEnterReviewedImportWithoutRotatingUnchangedCredentials', 'concurrentMembershipChangesHaveOneWinnerAndNoOrphan'],
 'AC14': ['concurrentVerifiedIntakeCreatesOneDisabledIdentityWithMembership'],
 'AC15': ['legacyIndividualAssignmentsAreRejected', 'managementRoleCannotBeRuntimeGrantSource', 'groupRoleTypeCannotBeMisassigned'],
 'AC16': ['unrelatedAgentGroupsCannotShareCapabilityAndDelegation', 'downstreamAgentCannotExceedAnyUpstreamCeilingOrReuseIdentity'],
}

BROWSER = {'UC01', 'UC14', 'UC24', 'UC61', 'UC66', 'UC71', 'UC77', 'UC80', 'AC11'}
GATEWAY = {'UC26', 'UC35', 'UC36', 'UC39', 'UC41', 'UC42', 'UC44', 'UC45',
           'UC46', 'UC47', 'UC57', 'UC58', 'AC03', 'AC04', 'AC06', 'AC08', 'AC09', 'AC10'}


def evidence():
    tests = {}
    for folder in ('packages/contracts/java/build/test-results', 'apps/control-plane/build/test-results'):
        for file in sorted((ROOT / folder).rglob('TEST-*.xml')):
            for case in ET.parse(file).getroot().findall('testcase'):
                name = case.attrib['name'].removesuffix('()')
                if name in tests:
                    raise ValueError('Ambiguous acceptance test name: ' + name)
                tests[name] = dict(passed=not any(case.find(tag) is not None for tag in
                    ('failure', 'error', 'skipped')), suite=case.attrib['classname'],
                    report=file.relative_to(ROOT).as_posix())
    return tests


def runtime_gate(file, gate):
    path = ROOT / file
    return path.is_file() and gate in json.loads(path.read_text(encoding='utf-8')).get('gates', [])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--require-complete', action='store_true')
    args = parser.parse_args()
    expected = {f'UC{i:02}' for i in range(1, 81)} | {f'AC{i:02}' for i in range(1, 17)}
    if set(CASES) != expected:
        raise SystemExit('Acceptance mapping must contain exactly UC01–UC80 and AC01–AC16')
    tests = evidence()
    rows = {}
    browser = runtime_gate('build/quickstart/browser-smoke.json', 'real accessible enterprise administration browser')
    gateway = runtime_gate('build/gateway/container-smoke.json',
        'real DELEGATED identity/same Team and Agent Group/native effect/current delegation revocation')
    outage = runtime_gate('build/gateway/container-smoke.json',
        'Core outage fails Gateway closed/recovers without restart/graceful production Gateway shutdown/redacted logs')
    for case, names in CASES.items():
        missing = [name for name in names if not tests.get(name, {}).get('passed')]
        if case in BROWSER and not browser:
            missing.append('fresh accessible browser workflows')
        if case in GATEWAY and not (gateway and outage):
            missing.append('production Gateway delegated/native/outage gate')
        rows[case] = dict(passed=not missing, tests=[dict(name=name, **tests.get(name, {}))
                         for name in names], missing=missing)
    count = sum(row['passed'] for row in rows.values())
    report = dict(mapped=len(rows), verified=count, total=96, cases=rows,
        interpretation='Representative automated acceptance evidence; integrated release and image gates are separate.',
        optionalScope='Nested/dynamic groups and offline effects unsupported; canonical reviewed import is the synchronization path.')
    output = ROOT / 'build/enterprise/acceptance.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'Acceptance: {len(rows)}/96 mapped; {count}/96 verified automated ({count / 96:.1%})')
    if args.require_complete and count != 96:
        raise SystemExit('Acceptance evidence remains incomplete; see ' + str(output))


if __name__ == '__main__':
    main()
