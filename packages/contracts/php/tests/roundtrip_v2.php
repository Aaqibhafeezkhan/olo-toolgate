<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

spl_autoload_register(static function (string $class): void {
    $prefix = 'OloLabs\\ToolGate\\Contracts\\';
    if (str_starts_with($class, $prefix)) {
        require __DIR__ . '/../src/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
    }
});

use OloLabs\ToolGate\Contracts\V2;

$fixtures = json_decode(file_get_contents(__DIR__ . '/../../../../tests/fixtures/contracts/v2/valid.json'), true, 512, JSON_THROW_ON_ERROR);
$models = [
    'ActorHop',
    'AdmissionKind',
    'AdmissionResponse',
    'ApiChange',
    'AuthType',
    'BreakGlassRequest',
    'BusinessKeyReservationRequest',
    'BusinessKeyReservationResult',
    'BusinessKeySettings',
    'BusinessKeySource',
    'BusinessKeyTombstone',
    'CallerIdentity',
    'CatalogScope',
    'CatalogScopeKind',
    'ClaimDeclaration',
    'ClientCheckIn',
    'CompatibilityProfile',
    'CompositionMapping',
    'CompositionRecord',
    'ConfigLayer',
    'ConfigurationSettings',
    'ConnectionRequirement',
    'ContractSet',
    'ControlServerSettings',
    'ControlServerSettingsPatch',
    'CredentialKind',
    'DeploymentBinding',
    'DeploymentGrant',
    'DeploymentTarget',
    'DestinationDeclaration',
    'DestinationScheme',
    'DeviceApproval',
    'Dispatch',
    'EffectApproval',
    'EffectSafety',
    'EffectiveDeployment',
    'EffectiveValue',
    'EgressMode',
    'ErrorDeclaration',
    'ExecutionSettings',
    'FrameKind',
    'GatewaySettings',
    'GlobalDeclaration',
    'HttpAuthDeclaration',
    'HttpMethod',
    'IdempotencyScope',
    'InputRequestFrame',
    'InvocationAdmission',
    'InvocationOutcome',
    'InvocationPermitClaims',
    'InvocationResultReport',
    'InvocationSubmission',
    'InvocationTask',
    'KeyNamespace',
    'KillEvent',
    'KillEventPage',
    'KillIssuer',
    'KillMode',
    'KillPush',
    'KillRequest',
    'KillScope',
    'KillScopeKind',
    'LeaseHeartbeat',
    'LeaseRenewal',
    'LimitOverride',
    'LogFrame',
    'LogLevel',
    'MappingKind',
    'MarketplaceImportRequest',
    'MarketplaceKey',
    'MarketplaceStatus',
    'McpTaskStatus',
    'MissingKeyPolicy',
    'PackageCompatibility',
    'PackageDeployment',
    'PackageDeploymentPage',
    'PackageDeploymentRequest',
    'PackageEnableRequest',
    'PackageKind',
    'PackageLimits',
    'PackageManifest',
    'PackageReview',
    'PackageVersion',
    'PackageVersionPage',
    'PackageVersionState',
    'PackagedArtifact',
    'Permission',
    'PermitConsumed',
    'PermitConsumption',
    'PermitHeader',
    'PermitIdentity',
    'PermitReservationRequest',
    'PermitTarget',
    'PrivateRangeApproval',
    'ProgressFrame',
    'ProtocolRange',
    'RecoveryEvent',
    'RecoveryRequest',
    'RefusalReason',
    'RequestContext',
    'RequestMode',
    'ReservationOutcome',
    'ResultFrame',
    'ResultMessage',
    'ResultType',
    'RolloutPlan',
    'RolloutStep',
    'RolloutStrategy',
    'RouteDeclaration',
    'RuntimeDeclaration',
    'RuntimeTier',
    'SdkInfo',
    'SdkLanguage',
    'SecretDeclaration',
    'SecretHandle',
    'SecretScope',
    'SecurityDeltaItem',
    'SecurityDeltaKind',
    'ServiceDestination',
    'ServiceProfile',
    'SignedInvocationPermit',
    'Streaming',
    'TargetKind',
    'TaskCapability',
    'TaskInputSubmission',
    'TaskProgress',
    'TaskState',
    'TaskTransition',
    'TaskView',
    'TcpDeclaration',
    'TlsSettings',
    'ToolDefinition',
    'ToolDescriptor',
    'ToolEffect',
    'ToolErrorBody',
    'ToolHostAssignment',
    'ToolHostAssignments',
    'ToolHostDispatchState',
    'ToolHostDispatchStatus',
    'ToolHostRegistration',
    'ToolInput',
    'ToolKind',
    'ToolLimits',
    'ToolVariables',
    'TransitionActor',
    'ValueSource',
    'ValueType'
];
$count = 0;
foreach ($fixtures as $name => $fixture) {
    if (!in_array($name, $models, true)) {
        continue;
    }
    $class = 'OloLabs\\ToolGate\\Contracts\\V2\\' . $name;
    $model = is_array($fixture) ? $class::fromArray($fixture) : $class::from($fixture);
    $wire = json_decode(json_encode($model, JSON_THROW_ON_ERROR | JSON_PRESERVE_ZERO_FRACTION), true, 512, JSON_THROW_ON_ERROR);
    if ($wire != $fixture) {
        throw new RuntimeException('Wire drift: ' . $name);
    }
    $count++;
}
if ($count !== count($models)) { throw new RuntimeException('Incomplete fixture coverage'); }
foreach ([['PackageManifest', 'schemaVersion', 3], ['ProgressFrame', 'kind', 'RESULT']] as [$name, $field, $value]) {
    try {
        $class = 'OloLabs\\ToolGate\\Contracts\\V2\\' . $name;
        $class::fromArray(array_replace($fixtures[$name], [$field => $value]));
    } catch (\InvalidArgumentException $expected) {
        continue;
    }
    throw new RuntimeException('Constant not enforced: ' . $name . '.' . $field);
}
if (V2\KillIssuer::from('control-breakglass') !== V2\KillIssuer::control_breakglass) {
    throw new RuntimeException('Renamed enum value lost');
}
echo "PHP: {$count} v2 model round trips and constant negatives passed\n";
