// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import io.ololabs.toolgate.sdk.annotations.ClientTrigger;
import io.ololabs.toolgate.sdk.annotations.OverflowPolicy;
import io.ololabs.toolgate.sdk.annotations.RuntimeTier;
import io.ololabs.toolgate.sdk.annotations.ServerTrigger;
import io.ololabs.toolgate.sdk.annotations.SourceReplay;
import io.ololabs.toolgate.sdk.annotations.TerminalRejectionPolicy;
import io.ololabs.toolgate.sdk.annotations.TriggerExample;
import io.ololabs.toolgate.sdk.annotations.TriggerMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Trigger exports (ADR 058, plan 5.8 and 5.9): the rules the processor enforces as compile errors, and the trigger
 * descriptor each {@code @ServerTrigger} or {@code @ClientTrigger} becomes. The descriptor matches
 * {@code TriggerDescriptor} in {@code packages/contracts/schemas/v2/trigger.schema.json}; its canonical bytes go to
 * {@code META-INF/toolgate/triggers/<name>.json}, so their sha256 is the {@code triggerDescriptorDigest}.
 */
final class Triggers {
    static final String POLL_CONTEXT = "io.ololabs.toolgate.sdk.trigger.PollContext";
    static final String POLL_RESULT = "io.ololabs.toolgate.sdk.trigger.PollResult";
    /** The local-name part of {@code TriggerId}: no dots, unlike tool names. */
    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,47}$");
    /** {@code TriggerEventType}. */
    static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+\\.v[1-9][0-9]{0,3}$");
    static final Pattern EXAMPLE_NAME = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    static final int MAX_SCHEMA_BYTES = 65_536;
    static final int MAX_EXAMPLES = 32;

    private final ProcessingEnvironment env;
    private final Types types;

    Triggers(ProcessingEnvironment env) {
        this.env = env;
        this.types = env.getTypeUtils();
    }

    /** One trigger export: its descriptor and what the package index records for the runtime and the harness. */
    record Export(String name, Map<String, Object> descriptor, Map<String, Object> index) {}

    /** The members ServerTrigger and ClientTrigger share. */
    private record Declared(boolean client, String name, TriggerMode mode, String description, Supplier<Class<?>> config,
            Supplier<Class<?>> event, String eventType, SourceReplay sourceReplay, long intervalMs, long resyncIntervalMs,
            int maxEventsPerHour, int maxEventBytes, int maxCheckpointBytes, int memoryMiB, int maxBrokerCallsPerMinute,
            int stopGraceMs, OverflowPolicy overflow, boolean lossTolerant, TerminalRejectionPolicy onTerminalRejection,
            int checkpointSchemaVersion, RuntimeTier[] tiers) {

        static Declared of(ServerTrigger t) {
            return new Declared(false, t.name(), t.mode(), t.description(), t::config, t::event, t.eventType(), t.sourceReplay(),
                    t.intervalMs(), t.resyncIntervalMs(), t.maxEventsPerHour(), t.maxEventBytes(), t.maxCheckpointBytes(), t.memoryMiB(),
                    t.maxBrokerCallsPerMinute(), t.stopGraceMs(), t.overflow(), t.lossTolerant(), t.onTerminalRejection(),
                    t.checkpointSchemaVersion(), new RuntimeTier[] {RuntimeTier.OCI_SANDBOX});
        }

        static Declared of(ClientTrigger t) {
            return new Declared(true, t.name(), t.mode(), t.description(), t::config, t::event, t.eventType(), t.sourceReplay(),
                    t.intervalMs(), t.resyncIntervalMs(), t.maxEventsPerHour(), t.maxEventBytes(), t.maxCheckpointBytes(), t.memoryMiB(),
                    t.maxBrokerCallsPerMinute(), t.stopGraceMs(), t.overflow(), t.lossTolerant(), t.onTerminalRejection(),
                    t.checkpointSchemaVersion(), t.tiers());
        }
    }

    /** The trigger name an annotated method declares, or null. */
    static String name(ExecutableElement method) {
        ServerTrigger s = method.getAnnotation(ServerTrigger.class);
        ClientTrigger c = method.getAnnotation(ClientTrigger.class);
        return s != null ? s.name() : c != null ? c.name() : null;
    }

    Export export(String packageId, ExecutableElement method) {
        ServerTrigger server = method.getAnnotation(ServerTrigger.class);
        ClientTrigger client = method.getAnnotation(ClientTrigger.class);
        if (server != null && client != null) throw new Problem(method, "a trigger is either @ServerTrigger or @ClientTrigger");
        Declared t = server != null ? Declared.of(server) : Declared.of(client);
        String what = "trigger " + t.name();
        if (!NAME.matcher(t.name()).matches()) throw new Problem(method, "trigger name " + t.name() + " does not match " + NAME.pattern());
        if (t.name().startsWith("toolgate")) throw new Problem(method, "trigger name " + t.name() + " uses the reserved prefix toolgate");
        for (String tool : List.of("ServerTool", "ClientTool", "Example", "Reconcile", "Training", "ModelValidation", "Authorization", "ToolVariables", "Idempotency")) {
            if (has(method, "io.ololabs.toolgate.sdk.annotations." + tool)) {
                throw new Problem(method, "@" + tool + " belongs on a tool handler, not on " + what + (tool.equals("Example") ? "; use @TriggerExample" : ""));
            }
        }
        String triggerId = packageId + "/" + t.name();
        if (triggerId.length() > 128) throw new Problem(method, "triggerId " + triggerId + " is longer than 128 characters");
        DescriptorProcessor.length(t.description(), 1, 1024, what + " description", method);
        if (!EVENT_TYPE.matcher(t.eventType()).matches() || t.eventType().length() > 128) {
            throw new Problem(method, what + " eventType " + t.eventType() + " must be reverse-DNS and end with a major version, such as examples.files.observed.v1");
        }
        if (t.eventType().startsWith("io.ololabs.toolgate.")) throw new Problem(method, what + " eventType uses the reserved prefix io.ololabs.toolgate.");

        // Mode and schedule (plan 5.1, 5.8). PUSH needs PushTrigger, PushContext and the emitter, which this SDK does not have yet.
        if (t.mode() == TriggerMode.PUSH) throw new Problem(method, what + ": mode = PUSH is not supported by this SDK version; use POLL");
        if (t.intervalMs() == -1) throw new Problem(method, "POLL " + what + " needs intervalMs");
        DescriptorProcessor.range(t.intervalMs(), 5_000, 86_400_000, "POLL intervalMs", method);
        if (t.resyncIntervalMs() != -1) throw new Problem(method, "resyncIntervalMs applies to PUSH triggers; POLL " + what + " re-reads its source every intervalMs");

        // Limits and overflow (plan 5.8, 6.6, 6.7).
        DescriptorProcessor.range(t.maxEventsPerHour(), 1, 3_600, "maxEventsPerHour", method);
        DescriptorProcessor.range(t.maxEventBytes(), 1, 16_384, "maxEventBytes (event data must fit 16 KiB serialized)", method);
        if (t.maxCheckpointBytes() > 16_384) {
            throw new Problem(method, "maxCheckpointBytes above 16384 needs a STATE_SNAPSHOT source using SnapshotState, which this SDK version does not have");
        }
        DescriptorProcessor.range(t.maxCheckpointBytes(), 1, 16_384, "maxCheckpointBytes", method);
        DescriptorProcessor.range(t.memoryMiB(), 32, 4_096, "memoryMiB", method);
        DescriptorProcessor.range(t.maxBrokerCallsPerMinute(), 0, 6_000, "maxBrokerCallsPerMinute", method);
        DescriptorProcessor.range(t.stopGraceMs(), 0, 30_000, "stopGraceMs", method);
        DescriptorProcessor.range(t.checkpointSchemaVersion(), 1, 65_535, "checkpointSchemaVersion", method);
        if (t.overflow() != OverflowPolicy.BLOCK) {
            if (!t.lossTolerant()) throw new Problem(method, "overflow = " + t.overflow() + " is allowed only for a lossTolerant event type");
            if (t.onTerminalRejection() != TerminalRejectionPolicy.SKIP_AND_RECORD) {
                throw new Problem(method, "overflow = " + t.overflow() + " needs onTerminalRejection = SKIP_AND_RECORD, so every drop leaves a loss record");
            }
        }

        // Capabilities (plan 5.7, 5.8). Route calls, TCP destinations and secrets are refused for now: the trigger
        // descriptor does not carry their definitions, and the lease-bound broker that would serve them does not exist.
        for (String secret : List.of("Secret", "Secrets", "JwtClaim", "JwtClaims", "Global", "Globals")) {
            if (has(method, "io.ololabs.toolgate.sdk.annotations." + secret)) {
                throw new Problem(method, what + " cannot declare @" + secret
                        + (secret.startsWith("Secret") ? "; triggers in this SDK version use no secrets" : secret.startsWith("Jwt") ? "; a trigger runs as no user" : "; globals are declared on the package"));
            }
        }
        io.ololabs.toolgate.sdk.annotations.DeviceFileAccess files = method.getAnnotation(io.ololabs.toolgate.sdk.annotations.DeviceFileAccess.class);
        Set<String> permissions = new LinkedHashSet<>();
        if (files != null) {
            if (!t.client()) throw new Problem(method, "@DeviceFileAccess is only for a @ClientTrigger; Tool Hosts have no device files");
            if (files.write()) throw new Problem(method, "a trigger's device file access is read-only; remove write = true");
            permissions.add("DEVICE_FILES_READ");
        }

        // The handler (plan 5.8): PollResult<E> check(C config, PollContext ctx) on a public instance.
        handler(method, what);
        TypeMirror config = mirror(t.config());
        TypeMirror event = mirror(t.event());
        record(config, "config", what, method);
        record(event, "event", what, method);
        List<? extends VariableElement> params = method.getParameters();
        if (params.size() != 2 || !types.isSameType(types.erasure(params.get(0).asType()), types.erasure(config))
                || !qualified(params.get(1).asType()).equals(POLL_CONTEXT)) {
            throw new Problem(method, "a POLL trigger is PollResult<" + simple(event) + "> " + method.getSimpleName() + "(" + simple(config)
                    + " config, PollContext ctx)");
        }
        TypeMirror returned = method.getReturnType();
        if (!qualified(returned).equals(POLL_RESULT) || ((DeclaredType) returned).getTypeArguments().size() != 1
                || !types.isSameType(((DeclaredType) returned).getTypeArguments().get(0), event)) {
            throw new Problem(method, "a POLL trigger returns PollResult<" + simple(event) + ">, the declared event type");
        }
        Map<String, Object> configSchema = new Schemas(env).output(config, method);
        Map<String, Object> eventSchema = new Schemas(env).output(event, method);
        if (Jcs.bytes(configSchema).length > MAX_SCHEMA_BYTES) throw new Problem(method, what + " configSchema is over " + MAX_SCHEMA_BYTES + " bytes");
        if (Jcs.bytes(eventSchema).length > MAX_SCHEMA_BYTES) throw new Problem(method, what + " eventSchema is over " + MAX_SCHEMA_BYTES + " bytes");

        List<Object> examples = new ArrayList<>();
        Set<String> selfTests = new LinkedHashSet<>();
        TriggerExample[] declared = method.getAnnotationsByType(TriggerExample.class);
        if (declared.length > MAX_EXAMPLES) throw new Problem(method, what + " declares " + declared.length + " examples; at most " + MAX_EXAMPLES + " are allowed");
        for (TriggerExample e : declared) {
            if (!EXAMPLE_NAME.matcher(e.name()).matches()) throw new Problem(method, "trigger example name " + e.name() + " does not match " + EXAMPLE_NAME.pattern());
            if (!selfTests.add(e.name())) throw new Problem(method, "trigger example " + e.name() + " is declared twice");
            if (e.config().isBlank()) throw new Problem(method, "trigger example " + e.name() + " needs a config");
            if (e.expectedEvents().isBlank()) throw new Problem(method, "trigger example " + e.name() + " needs expectedEvents, [] for none");
            if (!e.files().isEmpty() && !t.client()) throw new Problem(method, "trigger example " + e.name() + " sets files, which only a @ClientTrigger reads");
            Map<String, Object> entry = new TreeMap<>();
            entry.put("name", e.name());
            entry.put("config", e.config());
            entry.put("expectedEvents", e.expectedEvents());
            if (!e.checkpoint().isEmpty()) entry.put("checkpoint", e.checkpoint());
            if (!e.files().isEmpty()) entry.put("files", e.files());
            if (!e.expectedCheckpoint().isEmpty()) entry.put("expectedCheckpoint", e.expectedCheckpoint());
            examples.add(entry);
        }

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", t.client() ? "CLIENT_TRIGGER" : "SERVER_TRIGGER");
        d.put("triggerId", triggerId);
        d.put("mode", t.mode().name());
        // plan 5.7: a server trigger that uses no route, TCP destination or secret is eligible on devices too.
        d.put("executors", t.client() ? List.of("CLIENT") : List.of("CLIENT", "SERVER"));
        d.put("eventType", t.eventType());
        d.put("lossTolerant", t.lossTolerant());
        d.put("configSchema", configSchema);
        d.put("eventSchema", eventSchema);
        d.put("checkpointSchemaVersion", (long) t.checkpointSchemaVersion());
        d.put("sourceReplay", t.sourceReplay().name());
        d.put("schedule", Map.of("intervalMs", t.intervalMs()));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxEventsPerHour", (long) t.maxEventsPerHour());
        limits.put("maxEventBytes", (long) t.maxEventBytes());
        limits.put("maxCheckpointBytes", (long) t.maxCheckpointBytes());
        limits.put("memoryMiB", (long) t.memoryMiB());
        limits.put("maxBrokerCallsPerMinute", (long) t.maxBrokerCallsPerMinute());
        limits.put("stopGraceMs", (long) t.stopGraceMs());
        d.put("limits", limits);
        d.put("overflow", t.overflow().name());
        d.put("onTerminalRejection", t.onTerminalRejection().name());
        d.put("routes", List.of());
        d.put("tcpDestinations", List.of());
        d.put("secrets", List.of());
        d.put("permissions", Jcs.sorted(permissions));
        if (t.tiers().length == 0) throw new Problem(method, "a client trigger needs at least one tier");
        List<String> tiers = new ArrayList<>();
        for (RuntimeTier tier : t.tiers()) tiers.add(tier.name());
        d.put("tiers", Jcs.sorted(DescriptorProcessor.unique(tiers, "tier", method)));
        d.put("selfTests", Jcs.sorted(selfTests));

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("name", t.name());
        index.put("description", t.description());
        index.put("handlerClass", ((TypeElement) method.getEnclosingElement()).getQualifiedName().toString());
        index.put("handlerMethod", method.getSimpleName().toString());
        index.put("configClass", binaryName(config));
        index.put("eventClass", binaryName(event));
        if (!examples.isEmpty()) index.put("examples", examples);
        return new Export(t.name(), d, index);
    }

    private void handler(ExecutableElement method, String what) {
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)) {
            throw new Problem(method, what + " must be a public instance method");
        }
        if (!owner.getModifiers().contains(Modifier.PUBLIC) || owner.getKind() != ElementKind.CLASS || owner.getModifiers().contains(Modifier.ABSTRACT)
                || (owner.getNestingKind() == NestingKind.MEMBER && !owner.getModifiers().contains(Modifier.STATIC))
                || (owner.getNestingKind() != NestingKind.TOP_LEVEL && owner.getNestingKind() != NestingKind.MEMBER)) {
            throw new Problem(method, what + " must live in a public, concrete, top-level or static class");
        }
        boolean noArg = ElementFilter.constructorsIn(owner.getEnclosedElements()).stream()
                .anyMatch(c -> c.getParameters().isEmpty() && c.getModifiers().contains(Modifier.PUBLIC));
        if (!noArg) throw new Problem(method, owner.getSimpleName() + " needs a public no-argument constructor: the trigger runtime creates it");
        if (!method.getTypeParameters().isEmpty()) throw new Problem(method, what + " may not be generic");
    }

    /** Config and event types are records the descriptor generator supports (plan 5.8). */
    private void record(TypeMirror type, String role, String what, ExecutableElement at) {
        if (type.getKind() != TypeKind.DECLARED || types.asElement(type).getKind() != ElementKind.RECORD) {
            throw new Problem(at, what + " " + role + " type " + type + " must be a record");
        }
        TypeElement element = (TypeElement) types.asElement(type);
        if (!element.getModifiers().contains(Modifier.PUBLIC)
                || (element.getNestingKind() != NestingKind.TOP_LEVEL && element.getNestingKind() != NestingKind.MEMBER)) {
            throw new Problem(at, what + " " + role + " record " + element.getSimpleName() + " must be public and top-level or a member");
        }
        if (!element.getTypeParameters().isEmpty()) throw new Problem(at, what + " " + role + " record " + element.getSimpleName() + " may not be generic");
    }

    private TypeMirror mirror(Supplier<Class<?>> value) {
        try {
            Class<?> c = value.get();
            return env.getElementUtils().getTypeElement(c.getCanonicalName()).asType();
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }

    private String binaryName(TypeMirror type) {
        return env.getElementUtils().getBinaryName((TypeElement) types.asElement(type)).toString();
    }

    private String qualified(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED ? ((TypeElement) types.asElement(type)).getQualifiedName().toString() : type.toString();
    }

    private String simple(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED ? types.asElement(type).getSimpleName().toString() : type.toString();
    }

    private static boolean has(ExecutableElement method, String annotation) {
        return method.getAnnotationMirrors().stream()
                .anyMatch(a -> ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation));
    }
}
