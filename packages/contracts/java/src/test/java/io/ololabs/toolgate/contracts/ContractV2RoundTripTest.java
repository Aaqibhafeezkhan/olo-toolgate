// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.KillIssuer;
import io.ololabs.toolgate.contracts.v2.PackageManifest;
import io.ololabs.toolgate.contracts.v2.ProgressFrame;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the v2 wire corpus through the namespaced v2 record bindings. */
final class ContractV2RoundTripTest {
    private static final Set<String> MODELS = Set.of(
        "ActorHop",
        "AdmissionKind",
        "AdmissionResponse",
        "ApiChange",
        "AuthType",
        "BreakGlassRequest",
        "BusinessKeyReservationRequest",
        "BusinessKeyReservationResult",
        "BusinessKeySettings",
        "BusinessKeySource",
        "BusinessKeyTombstone",
        "CallerIdentity",
        "CatalogScope",
        "CatalogScopeKind",
        "ClaimDeclaration",
        "ClientCheckIn",
        "CompatibilityProfile",
        "CompositionMapping",
        "CompositionRecord",
        "ConfigLayer",
        "ConnectionRequirement",
        "ContractSet",
        "ControlServerSettings",
        "ControlServerSettingsPatch",
        "CredentialKind",
        "DeploymentBinding",
        "DeploymentGrant",
        "DeploymentTarget",
        "DestinationDeclaration",
        "DestinationScheme",
        "DeviceApproval",
        "Dispatch",
        "EffectApproval",
        "EffectSafety",
        "EffectiveDeployment",
        "EffectiveValue",
        "EgressMode",
        "ErrorDeclaration",
        "ExecutionSettings",
        "FrameKind",
        "GatewaySettings",
        "GlobalDeclaration",
        "HttpAuthDeclaration",
        "HttpMethod",
        "IdempotencyScope",
        "InputRequestFrame",
        "InvocationAdmission",
        "InvocationOutcome",
        "InvocationPermitClaims",
        "InvocationResultReport",
        "InvocationSubmission",
        "InvocationTask",
        "KeyNamespace",
        "KillEvent",
        "KillEventPage",
        "KillIssuer",
        "KillMode",
        "KillPush",
        "KillRequest",
        "KillScope",
        "KillScopeKind",
        "LeaseHeartbeat",
        "LeaseRenewal",
        "LimitOverride",
        "LogFrame",
        "LogLevel",
        "MappingKind",
        "McpTaskStatus",
        "MissingKeyPolicy",
        "PackageCompatibility",
        "PackageEnableRequest",
        "PackageKind",
        "PackageLimits",
        "PackageManifest",
        "PackageReview",
        "PackageVersion",
        "PackageVersionPage",
        "PackageVersionState",
        "PackagedArtifact",
        "Permission",
        "PermitConsumed",
        "PermitConsumption",
        "PermitHeader",
        "PermitIdentity",
        "PermitReservationRequest",
        "PermitTarget",
        "PrivateRangeApproval",
        "ProgressFrame",
        "ProtocolRange",
        "RecoveryEvent",
        "RecoveryRequest",
        "RefusalReason",
        "RequestContext",
        "RequestMode",
        "ReservationOutcome",
        "ResultFrame",
        "ResultMessage",
        "ResultType",
        "RolloutPlan",
        "RolloutStep",
        "RolloutStrategy",
        "RouteDeclaration",
        "RuntimeDeclaration",
        "RuntimeTier",
        "SdkInfo",
        "SdkLanguage",
        "SecretDeclaration",
        "SecretHandle",
        "SecretScope",
        "SecurityDeltaItem",
        "SecurityDeltaKind",
        "ServiceDestination",
        "ServiceProfile",
        "SignedInvocationPermit",
        "Streaming",
        "TargetKind",
        "TaskCapability",
        "TaskInputSubmission",
        "TaskProgress",
        "TaskState",
        "TaskTransition",
        "TaskView",
        "TcpDeclaration",
        "TlsSettings",
        "ToolDefinition",
        "ToolDescriptor",
        "ToolEffect",
        "ToolErrorBody",
        "ToolHostRegistration",
        "ToolInput",
        "ToolKind",
        "ToolLimits",
        "ToolVariables",
        "TransitionActor",
        "ValueSource",
        "ValueType");
    private final ObjectMapper mapper = JsonMapper.builder()
        .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    @Test void everyV2ModelRoundTrips() throws Exception {
        var fixtures = mapper.readTree(Path.of(System.getProperty("toolgate.fixturesV2")).toFile());
        int checked = 0;
        for (var name : MODELS) {
            var type = Class.forName("io.ololabs.toolgate.contracts.v2." + name);
            var fixture = fixtures.get(name);
            var model = mapper.treeToValue(fixture, type);
            assertEquals(fixture, mapper.readTree(mapper.writeValueAsString(model)), name);
            checked++;
        }
        assertEquals(MODELS.size(), checked);
    }

    @Test void constantsAndRenamedValuesAreEnforced() throws Exception {
        var fixtures = mapper.readTree(Path.of(System.getProperty("toolgate.fixturesV2")).toFile());
        var manifest = (ObjectNode) fixtures.get("PackageManifest").deepCopy();
        manifest.put("schemaVersion", 3);
        assertThrows(Exception.class, () -> mapper.treeToValue(manifest, PackageManifest.class));
        var frame = (ObjectNode) fixtures.get("ProgressFrame").deepCopy();
        frame.put("kind", "RESULT");
        assertThrows(Exception.class, () -> mapper.treeToValue(frame, ProgressFrame.class));
        assertEquals(KillIssuer.control_breakglass, mapper.readValue("\"control-breakglass\"", KillIssuer.class));
        assertEquals("\"control-breakglass\"", mapper.writeValueAsString(KillIssuer.control_breakglass));
    }
}
