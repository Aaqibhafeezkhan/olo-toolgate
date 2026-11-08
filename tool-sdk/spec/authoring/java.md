# Java API (Java 17+, Kotlin via KSP)

## Purpose

Exact Java surface of the [authoring contract](README.md). Package `io.ololabs.toolgate.sdk` (annotations in `.annotations`, runtime types in `.context`, errors in `.errors`).

## Annotations

```java
@Retention(SOURCE) @Target(TYPE)      @interface ToolPackage { String id(); String name(); String publisher(); String description() default ""; }
@Retention(RUNTIME) @Target(METHOD)   @interface ServerTool {
    String name(); String title() default ""; String text() default ""; String description(); String useWhen() default "";
    EffectSafety effect() default EffectSafety.UNKNOWN; boolean destructive() default false; OpenWorld openWorld() default OpenWorld.DEFAULT;
    boolean async() default false; long maxDurationMs() default -1; boolean stateless() default false; int maxConcurrency() default -1;
    String[] routes() default {}; String[] tcp() default {};
    BusinessKey requiresBusinessKey() default BusinessKey.DEFAULT; KeyNamespace businessKeyNamespace() default KeyNamespace.AGENT;
    String businessKeyArgument() default ""; boolean cancelIfAbandoned() default false;
    ErrorDecl[] errors() default {}; String[] toolsets() default {}; }
@Retention(RUNTIME) @Target(METHOD)   @interface ClientTool { /* same members as ServerTool except routes, tcp */ RuntimeTier[] tiers() default RuntimeTier.OCI_SANDBOX; }
@Target(PARAMETER)                    @interface Param { String name() default ""; String description(); ValueType type() default ValueType.INFER;
    boolean required() default true; String defaultValue() default ""; String min() default ""; String max() default "";
    int minLength() default -1; int maxLength() default -1; String pattern() default ""; String[] enumValues() default {};
    String format() default ""; boolean sensitive() default false; }
@Target(METHOD)                       @interface ToolVariables { Variable[] inputs() default {}; Global[] globals() default {}; JwtClaim[] jwt() default {}; Secret[] secrets() default {}; }
@Repeatable(Globals.class)  @Target({TYPE, METHOD}) @interface Global { String name(); ValueType type(); String defaultValue() default ""; boolean required() default true; String description() default ""; /* constraints as Param */ }
@Repeatable(Secrets.class)  @Target({TYPE, METHOD}) @interface Secret { String name(); ValueType type() default ValueType.STRING; SecretScope scope() default SecretScope.GROUP; String description() default ""; }
@Repeatable(JwtClaims.class)@Target({TYPE, METHOD}) @interface JwtClaim { String name(); String claim(); ValueType type() default ValueType.STRING; boolean required() default true; }
@Repeatable(Destinations.class) @Target(TYPE) @interface Destination { String id(); String hostSetting(); String defaultHost() default ""; int port() default 443; String[] spkiPins() default {}; String caBundle() default ""; }
@Repeatable(HttpAuths.class)    @Target(TYPE) @interface HttpAuth { String id(); AuthType type(); String header() default ""; String secret() default ""; String connection() default ""; String[] scopes() default {}; String audience() default ""; }
@Repeatable(HttpRoutes.class)   @Target(TYPE) @interface HttpRoute { String id(); String destination(); HttpMethod method(); String path();
    String[] query() default {}; String[] headers() default {}; String[] requestContentTypes() default "application/json";
    long maxRequestBytes() default 1_048_576; long maxResponseBytes() default 4_194_304; Streaming streaming() default Streaming.NONE;
    int timeoutMs() default 10_000; String auth() default ""; }
@Repeatable(TcpDestinations.class) @Target(TYPE) @interface TcpDestination { String id(); String hostSetting(); String defaultHost() default ""; int port(); }
@Target(METHOD) @interface Idempotency { String header(); IdempotencyScope scope() default IdempotencyScope.BUSINESS_KEY; }
@Target(METHOD) @interface Reconcile { String tool(); }
@Repeatable(Examples.class) @Target(METHOD) @interface Example { String arguments(); String expectedOutput() default ""; String expectedError() default ""; String exchanges() default ""; }
@interface ErrorDecl { String code(); String description(); boolean retryable() default false; String dataSchema() default ""; }
@Target(METHOD) @interface Authorization { String action(); String[] resources(); }
```

`reconciliation` (`Reconciliation.DOWNSTREAM_IDEMPOTENCY`, `STATUS_LOOKUP` or `MANUAL`) is required on a `@ServerTool` or
`@ClientTool` whose `effect` is `IDEMPOTENT` or `NON_IDEMPOTENT`: `DOWNSTREAM_IDEMPOTENCY` needs `@Idempotency` (or `IDEMPOTENT`),
`STATUS_LOOKUP` needs a `@Reconcile` tool, and a `READ_ONLY` tool declares none. It tells ToolGate how an unknown outcome is settled.

`@Authorization` gives the tool its single permission and per-call resources (descriptor `authorization`). Without it a tool
is one `invoke` action on `tool://<toolId>`. Each resource template starts `<scheme>://{tenant}/`; ToolGate fills `{tenant}` with
the verified tenant and every other `{name}` with the required string argument of that name, which must be an opaque id
(one path segment), for example `@Authorization(action = "memory.read", resources = "olo://{tenant}/conversation/{conversationId}")`.
Actions are lower-case dotted names without wildcards.

`title` and `text` are mutually exclusive; exactly one must be set. `BusinessKey.DEFAULT` resolves per [README.md](README.md) §3.

## Handler signature

```java
public R methodName(@Param(...) T1 a, @Param(...) T2 b, ToolContext context) throws ToolError
public CompletionStage<R> methodName(..., ToolContext context)   // also allowed
```

`R` is a record, POJO, `Map<String,?>` or `ToolResult` (explicit `content[]` plus `structuredContent`). Handlers live in public classes with a public no-argument constructor or are discovered through `ToolRegistry` providers (`ServiceLoader`).

## ToolContext

```java
public interface ToolContext {
  String getStringValue(String name); int getIntValue(String name); long getLongValue(String name);
  double getNumberValue(String name); boolean getBooleanValue(String name); LocalDate getDateValue(String name);
  OffsetDateTime getDateTimeValue(String name); Duration getDurationValue(String name);
  <T> T getObjectValue(String name, Class<T> type); <T> List<T> getListValue(String name, Class<T> type); byte[] getBinaryValue(String name);
  Values global(); Values secret(); Jwt jwt();
  HttpClient http(); TcpConnector tcp(); Invocation invocation();
  void progress(double fraction, String message); boolean isCancelled(); CancellationToken cancellation();
  <T> T requestInput(InputRequest<T> request); ToolLogger log();
}
public interface Values { /* same get*Value family as above */ }
public interface Jwt extends Values { String getUserName(); String getSubject(); String getIssuer(); }
public interface HttpClient { RouteCall route(String routeId); RouteCall get(String destinationId, String pathTemplate); /* post, put, patch, delete, head */ }
public interface RouteCall { RouteCall pathVar(String n, Object v); RouteCall query(String n, Object v); RouteCall header(String n, String v);
  RouteCall body(Object v); RouteCall timeout(Duration d); <T> T send(Class<T> type); RawResponse sendRaw(); }
public interface TcpConnector { Socket connect(String destinationId); javax.net.SocketFactory socketFactory(String destinationId); }
```

Generated, typed accessors (from the annotation processor) are added per tool: `OrderToolsVars.global(context).timeoutSeconds()`. Using an undeclared name in a `get*Value` call with a literal argument is a compile error emitted by the processor.

## Triggers (POLL)

A trigger observes a source and reports events ([ADR 058](../../../docs/adr/058-triggers.md), plan
[section 5](../../../docs/architecture/device-triggers-plan.md)). This SDK version builds POLL triggers; PUSH mode
(`PushTrigger`, the emitter and `SnapshotState`) is refused by the processor until it exists.

```java
@Retention(RUNTIME) @Target(METHOD) @interface ServerTrigger {
    String name(); TriggerMode mode(); String description(); Class<?> config(); Class<?> event(); String eventType();
    SourceReplay sourceReplay(); long intervalMs() default -1; long resyncIntervalMs() default -1; int maxEventsPerHour();
    int maxEventBytes() default 16_384; int maxCheckpointBytes() default 16_384; int memoryMiB() default 256;
    int maxBrokerCallsPerMinute() default 120; int stopGraceMs() default 10_000; OverflowPolicy overflow() default OverflowPolicy.BLOCK;
    boolean lossTolerant() default false; TerminalRejectionPolicy onTerminalRejection() default TerminalRejectionPolicy.HALT;
    int checkpointSchemaVersion() default 1; }
@Retention(RUNTIME) @Target(METHOD) @interface ClientTrigger { /* same members */ RuntimeTier[] tiers() default RuntimeTier.OCI_SANDBOX; }
@Repeatable(TriggerExamples.class) @Target(METHOD) @interface TriggerExample { String name(); String config(); String checkpoint() default "";
    String files() default ""; String expectedEvents(); String expectedCheckpoint() default ""; }

public PollResult<E> check(C config, PollContext ctx)        // the only POLL shape
```

Package `io.ololabs.toolgate.sdk.trigger`: `PollContext` (`triggerId()`, `instanceId()`, `leaseEpoch()`, `configRevision()`,
`globals()`, `checkpoint()`, `startedAt()`, `files()` (read-only, `@ClientTrigger` with `@DeviceFileAccess`), `log()`,
`stopping()`); `PollResult.of(events).checkpoint(Checkpoint)` and `PollResult.none()`; `TriggerEvent.of(data).id(...)
.subject(...).time(...)`; `Checkpoint`, an immutable JSON object (`of(...)`, `getString`, `getLong`, `getMap`, ...).

The processor refuses, as compile errors (plan 5.8): a trigger that is not `PollResult<E> check(C, PollContext)` on a
public instance of a public class with a public no-argument constructor; config or event types that are not public
records; `eventType` that is not reverse-DNS ending in `.vN` (or uses `io.ololabs.toolgate.`); `intervalMs` unset or
below 5000, `resyncIntervalMs` on a POLL trigger; `maxEventsPerHour` outside 1..3600, `maxEventBytes` above 16384,
`maxCheckpointBytes` above 16384; `DROP_*` overflow without `lossTolerant = true` and `SKIP_AND_RECORD`; `@DeviceFileAccess`
on a server trigger or with `write = true`; `@Secret`, `@JwtClaim` or `@Global` on a trigger; `@Example` and the other
tool annotations on a trigger; a trigger name that is not `[a-z][a-z0-9_-]{0,47}`, repeats, or equals a tool name
(tools and triggers share one namespace); `mode = PUSH`. Route calls, TCP destinations and secrets for server triggers
are not part of this version: the trigger descriptor lists them by id only and the lease-bound broker does not exist yet.

For each trigger the processor writes `META-INF/toolgate/triggers/<name>.json`, the `TriggerDescriptor` as canonical
bytes, and a `triggers` entry in `META-INF/toolgate/package.json` with its digest, handler, config and event classes and
examples. A `@ServerTrigger` without capabilities runs on both executors (`executors: ["CLIENT", "SERVER"]`, plan 5.7).

## Testing

Artifact `io.ololabs.toolgate:toolgate-sdk-testing`, package `io.ololabs.toolgate.testing`, as [testing.md](testing.md) defines it: `ToolHarness.builder()` with `global`, `secret`, `claim`, `destination`, `exchange(RecordedExchange...)` and `exchanges(resource)`; `call(tool, arguments)` returning `ToolResult`; `routeCalls()` returning `RouteCallRecord`s; `runExamples()` and `assertExamples()`. `RecordedExchange.on(routeId).variable(...).replyJson(status, body)` builds an exchange in code, and `RecordedExchange.load(stream)` reads the shared format.

`ToolHarness.trigger(name)` returns a `TriggerHarness.Builder` (`config`, `checkpoint`, `global`, `deviceFiles`, `target`,
`instance`); `start()` runs the real trigger runtime over the stdio frames against a fake supervisor. `firstPoll()`,
`poll()` and `poll(n)` return each check's emitted events and committed checkpoint; `rejectNext(TriggerEmitStatus...)`
scripts emit rejections and `failNextFilesCall(message)` fails a brokered files operation; `frames()`, `health()`,
`checkpoints()`, `checkpointPositions()` and `stop()` show the rest. `TriggerHarness.runExamples(loader)` runs every
`@TriggerExample`, as `toolgate check` does.

## Errors

`io.ololabs.toolgate.sdk.errors`: `ToolError extends RuntimeException`; subclasses as in [errors.md](errors.md).

## Build

Gradle plugin id `io.ololabs.toolgate.sdk`; tasks `toolgateDev`, `toolgatePackage`, `toolgateCheck`. Maven plugin `io.ololabs.toolgate:toolgate-maven-plugin` with goals `dev`, `package`, `check`; with `<extensions>true</extensions>` it adds the SDK dependencies and configures the compiler plugin as the Gradle plugin does. Both set `descriptorGeneration` (required, no default). A package holds one artifact, `artifacts/tool.jar`: a fat jar of the project jar and its runtime class path with `Main-Class: io.ololabs.toolgate.runtime.Main`, so a device needs only a JRE. Every tool's entry point is that `Main`, with `tool.jar` alone on the class path. The `toolgate` CLI's `dev`, `check` and `pack` run these tasks or goals in a project, and `toolgate init --lang java [--build gradle|maven]` writes a project that uses them.
