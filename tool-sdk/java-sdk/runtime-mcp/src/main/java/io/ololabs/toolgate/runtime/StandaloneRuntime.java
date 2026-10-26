// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.IdentityVerifier;
import io.ololabs.toolgate.protocol.MemoryTaskStore;
import io.ololabs.toolgate.protocol.ProtocolCore;
import io.ololabs.toolgate.protocol.ProtocolError;
import io.ololabs.toolgate.protocol.RequestMeta;
import io.ololabs.toolgate.protocol.StdioServer;
import io.ololabs.toolgate.protocol.StreamableHttpServer;
import io.ololabs.toolgate.protocol.TaskRecord;
import io.ololabs.toolgate.protocol.Tasks;
import io.ololabs.toolgate.protocol.ToolCatalog;
import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.context.ToolRegistry;
import io.ololabs.toolgate.sdk.context.ToolResult;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import io.ololabs.toolgate.sdk.errors.ToolRetryableError;
import io.ololabs.toolgate.sdk.errors.UpstreamError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The standalone runtime (toolgate-sdk-runtime-mcp): serves the tools the descriptor processor found on the class
 * path as a plain MCP server. Same descriptors, error model and execution rules as governed mode
 * (runtime-modes.md §3); only deployment values come from the environment or {@code toolgate.dev.json}.
 */
public final class StandaloneRuntime implements ToolCatalog {
    static final int MAX_INPUT_BYTES = 1024 * 1024;
    static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    static final int MAX_ARGUMENTS = 256;
    static final long SYNC_CEILING_MS = 60_000;
    static final long TASK_CEILING_MS = 900_000;
    /** After cancellation is requested, the handler gets this long to stop before it is abandoned (execution.md §3). */
    static final long CANCEL_GRACE_MS = 5_000;
    static final long CONCURRENCY_WAIT_MS = 1_000;
    static final int MAX_MESSAGE_BYTES = 1024;
    static final int MAX_DATA_BYTES = 8 * 1024;
    static final String FAILED = "The tool failed.";
    static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ObjectMapper json = new ObjectMapper();
    private final Config config;
    private final PrintStream diagnostics;
    private final Redactor redactor = new Redactor();
    private final Map<String, Tool> byMcpName = new TreeMap<>();
    private final ObjectNode packageInfo;
    private final Tasks tasks;
    private IdentityVerifier verifier;
    private InetSocketAddress httpAddress;
    private Egress egress;
    private Reservations reservations;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "toolgate-tool");
        t.setDaemon(true);
        return t;
    });

    /** One served tool. */
    private static final class Tool {
        String name;
        String toolId;
        String mcpName;
        String digest;
        JsonNode descriptor;
        Method method;
        Object instance;
        List<String> parameters;
        SchemaValidator input;
        SchemaValidator output;
        Semaphore concurrency;
        boolean async;
        boolean cancelIfAbandoned;
        boolean ledger;
        long deadlineMs;
        Map<String, SchemaValidator> errorData = new HashMap<>();
        Map<String, DeclaredValues.Entry> globals = new LinkedHashMap<>();
        Map<String, DeclaredValues.Entry> secrets = new LinkedHashMap<>();
        Map<String, JsonNode> routes = new LinkedHashMap<>();
        Map<String, JsonNode> auths = new LinkedHashMap<>();
        java.util.Set<String> tcp = new java.util.TreeSet<>();
        JsonNode businessKey;
        boolean reserves;
    }

    private StandaloneRuntime(Config config, PrintStream diagnostics, ObjectNode packageInfo, Tasks tasks) {
        this.config = config;
        this.diagnostics = diagnostics;
        this.packageInfo = packageInfo;
        this.tasks = tasks;
    }

    /**
     * Loads the package from the class path and applies the startup rules of runtime-modes.md §2 and §4.
     *
     * @throws StartupRefused with the fixed code when the runtime cannot honour a declaration
     */
    public static StandaloneRuntime start(ClassLoader loader, Map<String, String> env, Path workingDirectory, PrintStream diagnostics) {
        return start(loader, env, workingDirectory, diagnostics, null);
    }

    /** As {@link #start(ClassLoader, Map, Path, PrintStream)} with the given task service, or the configured one when null. */
    static StandaloneRuntime start(ClassLoader loader, Map<String, String> env, Path workingDirectory, PrintStream diagnostics, Tasks tasks) {
        Config config = Config.load(env, workingDirectory);
        if (config.mode == Config.Mode.TEST) {
            throw new StartupRefused("CONFIGURATION", "TOOLGATE_MODE=test is only for ToolHarness; the runtime entry points reject it");
        }
        if (config.mode == Config.Mode.DEVELOPMENT && Verifiers.configured(env)) {
            throw new StartupRefused("CONFIGURATION", "development mode refuses to start when a production issuer is configured");
        }
        return start(loader, config, null, workingDirectory, diagnostics, tasks);
    }

    /**
     * Test mode, for {@code toolgate-sdk-testing} only (spec/authoring/testing.md): deployment values and claims come
     * from {@code fixtures}, shaped like {@code toolgate.dev.json}, and {@code exchanges} answers every route call in
     * place of the network. Tasks and business keys stay in memory and in {@code workingDirectory}.
     */
    public static StandaloneRuntime startTest(ClassLoader loader, JsonNode fixtures, TestExchanges exchanges, Path workingDirectory,
            PrintStream diagnostics) {
        return start(loader, Config.test(fixtures), exchanges, workingDirectory, diagnostics, null);
    }

    /** Test mode with a device files double, for ClientTools declared with {@code @DeviceFileAccess}. */
    public static StandaloneRuntime startTest(ClassLoader loader, JsonNode fixtures, TestExchanges exchanges, Path workingDirectory,
            PrintStream diagnostics, io.ololabs.toolgate.sdk.context.DeviceFiles files) {
        Config config = Config.test(fixtures);
        if (files != null) config.deviceFiles = files;
        return start(loader, config, exchanges, workingDirectory, diagnostics, null);
    }

    /**
     * Governed mode (Governed): one invocation under a Tool Host, with the host's values and claims in {@code config}
     * and every route call sent to the host's broker through {@code broker}.
     */
    static StandaloneRuntime startGoverned(ClassLoader loader, Config config, TestExchanges broker, Path workingDirectory,
            PrintStream diagnostics) {
        return start(loader, config, broker, workingDirectory, diagnostics, null);
    }

    private static StandaloneRuntime start(ClassLoader loader, Config config, TestExchanges exchanges, Path workingDirectory,
            PrintStream diagnostics, Tasks tasks) {
        Map<String, String> env = config.env;
        ObjectMapper json = new ObjectMapper();
        ObjectNode index = readIndex(loader, json);
        if (tasks == null) tasks = new Tasks(openTaskStore(env, workingDirectory), Tasks.DEFAULT_TTL_MS, Tasks.DEFAULT_POLL_INTERVAL_MS);
        StandaloneRuntime runtime = new StandaloneRuntime(config, diagnostics, index, tasks);
        if (config.mode == Config.Mode.PRODUCTION) runtime.verifier = Verifiers.fromEnv(env);
        if (env.get("TOOLGATE_AUTH_CLIENT_SECRET") != null) runtime.redactor.add(json.getNodeFactory().textNode(env.get("TOOLGATE_AUTH_CLIENT_SECRET")));
        runtime.httpAddress = httpAddress(env.get("TOOLGATE_HTTP_ADDRESS"), config.mode, runtime.verifier != null);
        Map<String, JsonNode> globalDeclarations = new TreeMap<>();
        for (JsonNode entry : index.path("tools")) {
            Tool tool = runtime.load(loader, entry);
            if (tool == null) continue;
            for (JsonNode g : tool.descriptor.path("variables").path("globals")) globalDeclarations.putIfAbsent(g.path("name").asText(), g);
            if (runtime.byMcpName.put(tool.mcpName, tool) != null) {
                throw new StartupRefused("CONFIGURATION", "two tools share the MCP name " + tool.mcpName);
            }
        }
        if (runtime.byMcpName.isEmpty()) throw new StartupRefused("CLIENT_TOOLS_UNAVAILABLE", "The package has no tools eligible for this execution target");
        List<JsonNode> destinations = new ArrayList<>();
        for (Tool tool : runtime.byMcpName.values()) tool.descriptor.path("destinations").forEach(destinations::add);
        runtime.egress = Egress.resolve(config, destinations, diagnostics);
        runtime.egress.exchanges = exchanges;
        List<JsonNode> tcpDestinations = new ArrayList<>();
        for (Tool tool : runtime.byMcpName.values()) tool.descriptor.path("tcp").forEach(tcpDestinations::add);
        runtime.egress.resolveTcp(config, tcpDestinations, diagnostics);
        // Under a Tool Host, Control reserves business keys (platform-requirements.md §12.3), so the local store is not used.
        if (config.mode == Config.Mode.GOVERNED) runtime.byMcpName.values().forEach(t -> t.reserves = false);
        if (runtime.byMcpName.values().stream().anyMatch(t -> t.reserves)) runtime.reservations = openReservations(env, workingDirectory);
        Map<String, JsonNode> globals = runtime.resolveGlobals(globalDeclarations);
        for (Tool tool : runtime.byMcpName.values()) runtime.bindVariables(tool, globals);
        if (config.mode != Config.Mode.GOVERNED) {
            diagnostics.println("toolgate: standalone runtime for " + index.path("id").asText() + " (" + runtime.byMcpName.size()
                    + " tools). There is no enterprise audit in standalone mode; diagnostics stay local.");
        }
        if (config.mode == Config.Mode.DEVELOPMENT) {
            diagnostics.println("toolgate: WARNING development mode: claims come from toolgate.dev.json fixtures and results are marked as fixture identity.");
        }
        if (runtime.verifier != null) {
            diagnostics.println("toolgate: HTTP requests need a bearer token, verified as " + Verifiers.describe(env)
                    + ". Over stdio there is no token, so identity tools return CLAIMS_UNAVAILABLE.");
        }
        for (String w : config.warnings) diagnostics.println("toolgate: WARNING " + w);
        return runtime;
    }

    /**
     * {@code TOOLGATE_HTTP_ADDRESS} as {@code host:port}. Development binds only to stdio or loopback
     * (runtime-modes.md §4); production does too unless an identity verifier (adapter A4) authenticates every request.
     */
    private static InetSocketAddress httpAddress(String value, Config.Mode mode, boolean verified) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        int colon = v.lastIndexOf(':');
        if (colon <= 0) throw new StartupRefused("CONFIGURATION", "TOOLGATE_HTTP_ADDRESS must be host:port, for example 127.0.0.1:8080");
        String host = v.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        int port;
        InetAddress ip;
        try {
            port = Integer.parseInt(v.substring(colon + 1));
            ip = InetAddress.getByName(host);
        } catch (NumberFormatException | UnknownHostException e) {
            throw new StartupRefused("CONFIGURATION", "TOOLGATE_HTTP_ADDRESS must be host:port, for example 127.0.0.1:8080");
        }
        if (port < 0 || port > 65535) throw new StartupRefused("CONFIGURATION", "TOOLGATE_HTTP_ADDRESS has an invalid port");
        if (!ip.isLoopbackAddress() && (mode == Config.Mode.DEVELOPMENT || !verified)) {
            throw new StartupRefused("CONFIGURATION", mode == Config.Mode.DEVELOPMENT
                    ? "development mode binds only to stdio or a loopback address, not " + v
                    : "Streamable HTTP binds a non-loopback address only with an identity verifier (TOOLGATE_AUTH_*), not " + v);
        }
        return new InetSocketAddress(ip, port);
    }

    /**
     * The business-key reservation store: SQLite at {@code TOOLGATE_STATE_DB} (default {@code .toolgate/state.db}), rows
     * kept with their results for {@code TOOLGATE_BUSINESS_KEY_RETENTION} (ISO-8601, default 30 days). Tools that need
     * reservations never run unreserved, so a store that cannot open refuses startup.
     */
    private static Reservations openReservations(Map<String, String> env, Path workingDirectory) {
        Path file = stateFile(env, workingDirectory);
        long retentionMs;
        try {
            retentionMs = java.time.Duration.parse(env.getOrDefault("TOOLGATE_BUSINESS_KEY_RETENTION", "P30D")).toMillis();
        } catch (java.time.format.DateTimeParseException e) {
            throw new StartupRefused("CONFIGURATION", "TOOLGATE_BUSINESS_KEY_RETENTION must be an ISO-8601 duration such as P30D");
        }
        if (retentionMs <= 0) throw new StartupRefused("CONFIGURATION", "TOOLGATE_BUSINESS_KEY_RETENTION must be positive");
        try {
            return Reservations.open(file, retentionMs, System::currentTimeMillis);
        } catch (java.sql.SQLException e) {
            throw new StartupRefused("CONFIGURATION", "the business-key store " + file + " cannot be opened: " + e.getMessage());
        }
    }

    /**
     * The task store {@code TOOLGATE_TASK_STORE} names: {@code memory} (the default), whose tasks end with the process, or
     * {@code sqlite}, kept in the state file so tasks and their results survive a restart.
     */
    private static io.ololabs.toolgate.protocol.TaskStore openTaskStore(Map<String, String> env, Path workingDirectory) {
        String kind = env.getOrDefault("TOOLGATE_TASK_STORE", "memory");
        if (kind.equals("memory")) return new MemoryTaskStore();
        if (!kind.equals("sqlite")) throw new StartupRefused("CONFIGURATION", "TOOLGATE_TASK_STORE must be memory or sqlite, not " + kind);
        Path file = stateFile(env, workingDirectory);
        try {
            return SqliteTaskStore.open(file);
        } catch (java.sql.SQLException e) {
            throw new StartupRefused("CONFIGURATION", "the task store " + file + " cannot be opened: " + e.getMessage());
        }
    }

    /** {@code TOOLGATE_STATE_DB}, default {@code .toolgate/state.db} in the working directory. */
    private static Path stateFile(Map<String, String> env, Path workingDirectory) {
        return env.containsKey("TOOLGATE_STATE_DB") ? Path.of(env.get("TOOLGATE_STATE_DB")) : workingDirectory.resolve(".toolgate").resolve("state.db");
    }

    /** The configured Streamable HTTP address, or null to serve stdio. */
    public InetSocketAddress httpAddress() {
        return httpAddress;
    }

    /** Serves MCP 2026-07-28 over Streamable HTTP at {@code /mcp} until closed. */
    public StreamableHttpServer serveHttp(InetSocketAddress address) throws IOException {
        StreamableHttpServer server = StreamableHttpServer.start(new ProtocolCore(this), address, "/mcp", verifier);
        diagnostics.println("toolgate: serving MCP 2026-07-28 at http://" + address.getHostString() + ":" + server.port() + "/mcp");
        return server;
    }

    /** Serves MCP 2026-07-28 over stdio until the input closes. */
    public void serveStdio(InputStream in, OutputStream out) throws IOException {
        new StdioServer(new ProtocolCore(this)).serve(in, out);
    }

    private static ObjectNode readIndex(ClassLoader loader, ObjectMapper json) {
        List<URL> found;
        try {
            found = Collections.list(loader.getResources("META-INF/toolgate/package.json"));
        } catch (IOException e) {
            throw new StartupRefused("CONFIGURATION", "cannot read META-INF/toolgate/package.json: " + e.getMessage());
        }
        if (found.size() != 1) {
            throw new StartupRefused("CONFIGURATION", "expected one META-INF/toolgate/package.json on the class path, found " + found.size()
                    + "; build the tool project with toolgate-sdk-processor");
        }
        try (InputStream in = found.get(0).openStream()) {
            return (ObjectNode) json.readTree(in);
        } catch (IOException | ClassCastException e) {
            throw new StartupRefused("CONFIGURATION", "META-INF/toolgate/package.json is not a JSON object");
        }
    }

    private Tool load(ClassLoader loader, JsonNode entry) {
        Tool tool = new Tool();
        tool.name = entry.path("name").asText();
        byte[] bytes;
        try (InputStream in = loader.getResourceAsStream("META-INF/toolgate/tools/" + tool.name + ".json")) {
            if (in == null) throw new StartupRefused("CONFIGURATION", "the descriptor of " + tool.name + " is missing");
            bytes = in.readAllBytes();
            tool.descriptor = json.readTree(bytes);
        } catch (IOException e) {
            throw new StartupRefused("CONFIGURATION", "cannot read the descriptor of " + tool.name);
        }
        tool.digest = sha256(bytes);
        if (!tool.digest.equals(entry.path("toolDescriptorDigest").asText())) {
            throw new StartupRefused("CONFIGURATION", "the descriptor of " + tool.name + " does not match its digest; rebuild the project");
        }
        JsonNode d = tool.descriptor;
        tool.toolId = d.path("toolId").asText();
        tool.mcpName = d.path("mcpName").asText();
        if (config.mode != Config.Mode.TEST && !config.location.allows(d.path("kind").asText())) return null;
        tool.businessKey = d.path("businessKey");
        tool.reserves = tool.businessKey.path("required").asBoolean() || tool.businessKey.hasNonNull("argument");
        if (config.mode == Config.Mode.PRODUCTION && verifier == null && needsIdentity(d)) {
            throw new StartupRefused("CONFIGURATION", "tool " + tool.toolId + " needs a verified identity (claims, a USER secret, TOKEN_EXCHANGE or the DELEGATED_USER namespace);"
                    + " configure the identity verifier with TOOLGATE_AUTH_*, or use TOOLGATE_MODE=development with fixtures to develop it.");
        }
        tool.async = d.path("execution").path("async").asBoolean();
        tool.cancelIfAbandoned = d.path("execution").path("cancelIfAbandoned").asBoolean();
        String safety = d.path("effect").path("safety").asText();
        tool.ledger = safety.equals("NON_IDEMPOTENT") || safety.equals("UNKNOWN");
        tool.deadlineMs = Math.min(d.path("execution").path("maxDurationMs").asLong(30_000), tool.async ? TASK_CEILING_MS : SYNC_CEILING_MS);
        if (d.path("execution").has("maxConcurrency")) tool.concurrency = new Semaphore(d.path("execution").path("maxConcurrency").asInt(), true);
        for (JsonNode r : d.path("routes")) tool.routes.put(r.path("id").asText(), r);
        for (JsonNode a : d.path("httpAuth")) tool.auths.put(a.path("id").asText(), a);
        for (JsonNode t : d.path("tcp")) tool.tcp.add(t.path("id").asText());
        tool.input = new SchemaValidator(d.path("inputSchema"));
        if (d.has("outputSchema")) tool.output = new SchemaValidator(d.get("outputSchema"));
        tool.parameters = new ArrayList<>();
        for (JsonNode p : entry.path("parameters")) tool.parameters.add(p.asText());
        Iterator<Map.Entry<String, JsonNode>> schemas = entry.path("errorDataSchemas").properties().iterator();
        while (schemas.hasNext()) {
            Map.Entry<String, JsonNode> s = schemas.next();
            try {
                tool.errorData.put(s.getKey(), new SchemaValidator(json.readTree(s.getValue().asText())));
            } catch (JsonProcessingException e) {
                throw new StartupRefused("CONFIGURATION", "the dataSchema of error " + s.getKey() + " in " + tool.toolId + " is not JSON");
            }
        }
        bindHandler(loader, tool, entry);
        return tool;
    }

    private static boolean needsIdentity(JsonNode d) {
        if (d.path("variables").path("claims").size() > 0) return true;
        for (JsonNode s : d.path("variables").path("secrets")) if (s.path("scope").asText().equals("USER")) return true;
        for (JsonNode a : d.path("httpAuth")) if (a.path("type").asText().equals("TOKEN_EXCHANGE")) return true;
        return d.path("businessKey").path("namespace").asText().equals("DELEGATED_USER");
    }

    private final Map<Class<?>, Object> instances = new HashMap<>();

    private void bindHandler(ClassLoader loader, Tool tool, JsonNode entry) {
        String className = entry.path("handlerClass").asText();
        String methodName = entry.path("handlerMethod").asText();
        Class<?> type;
        try {
            type = Class.forName(className, true, loader);
        } catch (ClassNotFoundException e) {
            throw new StartupRefused("CONFIGURATION", "handler class " + className + " is not on the class path");
        }
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            ServerTool s = m.getAnnotation(ServerTool.class);
            ClientTool c = m.getAnnotation(ClientTool.class);
            String declared = s != null ? s.name() : c != null ? c.name() : null;
            if (tool.name.equals(declared)) tool.method = m;
        }
        if (tool.method == null) throw new StartupRefused("CONFIGURATION", "handler " + className + "#" + methodName + " for " + tool.name + " was not found");
        if (tool.method.getParameterCount() != tool.parameters.size()) {
            throw new StartupRefused("CONFIGURATION", "handler " + className + "#" + methodName + " does not match its descriptor; rebuild the project");
        }
        tool.instance = instances.computeIfAbsent(type, t -> instance(loader, t));
    }

    private static Object instance(ClassLoader loader, Class<?> type) {
        for (ToolRegistry registry : ServiceLoader.load(ToolRegistry.class, loader)) {
            for (Object handler : registry.handlers()) if (handler.getClass() == type) return handler;
        }
        try {
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new StartupRefused("CONFIGURATION", type.getName() + " needs a public no-argument constructor or a ToolRegistry provider");
        }
    }

    /** Resolves every declared global once, validated against its declaration as governed configuration is. */
    private Map<String, JsonNode> resolveGlobals(Map<String, JsonNode> declarations) {
        Map<String, JsonNode> values = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : declarations.entrySet()) {
            JsonNode decl = e.getValue();
            String type = decl.path("type").asText();
            JsonNode raw = config.global(e.getKey(), json);
            JsonNode value = raw == null ? decl.get("default") : coerce(type, raw, "global " + e.getKey());
            if (value == null) {
                if (decl.path("required").asBoolean()) {
                    throw new StartupRefused("CONFIGURATION", "global " + e.getKey() + " is required; set TOOLGATE_GLOBAL_"
                            + e.getKey().toUpperCase(java.util.Locale.ROOT) + " or globals." + e.getKey() + " in toolgate.dev.json");
                }
                continue;
            }
            String failure = new SchemaValidator(globalSchema(decl)).validate(value);
            if (failure != null) throw new StartupRefused("CONFIGURATION", "global " + e.getKey() + " is invalid: " + failure.substring(1));
            if (decl.path("sensitive").asBoolean()) redactor.add(value);
            values.put(e.getKey(), value);
        }
        return values;
    }

    private JsonNode coerce(String type, JsonNode raw, String what) {
        if (!raw.isTextual()) return raw;
        String text = raw.asText().trim();
        try {
            return switch (type) {
                case "INT", "LONG" -> JSON.numberNode(Long.parseLong(text));
                case "NUMBER" -> JSON.numberNode(new java.math.BigDecimal(text));
                case "BOOLEAN" -> {
                    if (!text.equals("true") && !text.equals("false")) throw new NumberFormatException();
                    yield JSON.booleanNode(Boolean.parseBoolean(text));
                }
                case "OBJECT", "ARRAY" -> json.readTree(text);
                default -> raw;
            };
        } catch (NumberFormatException | JsonProcessingException e) {
            throw new StartupRefused("CONFIGURATION", what + " is not a valid " + type);
        }
    }

    private static JsonNode globalSchema(JsonNode decl) {
        ObjectNode schema = JSON.objectNode();
        switch (decl.path("type").asText()) {
            case "INT" -> {
                schema.put("type", "integer");
                schema.put("minimum", Integer.MIN_VALUE);
                schema.put("maximum", Integer.MAX_VALUE);
            }
            case "LONG" -> {
                schema.put("type", "integer");
                schema.put("minimum", -9007199254740991L);
                schema.put("maximum", 9007199254740991L);
            }
            case "NUMBER" -> schema.put("type", "number");
            case "BOOLEAN" -> schema.put("type", "boolean");
            case "OBJECT" -> schema.put("type", "object");
            case "ARRAY" -> schema.put("type", "array");
            case "DATE" -> schema.put("type", "string").put("format", "date");
            case "DATE_TIME" -> schema.put("type", "string").put("format", "date-time");
            case "DURATION" -> schema.put("type", "string").put("format", "duration");
            case "URI" -> schema.put("type", "string").put("format", "uri");
            case "BINARY" -> schema.put("type", "string").put("contentEncoding", "base64");
            default -> schema.put("type", "string");
        }
        for (String k : List.of("minimum", "maximum", "minLength", "maxLength", "pattern", "enum", "format")) {
            if (decl.has(k)) schema.set(k, decl.get(k));
        }
        return schema;
    }

    private void bindVariables(Tool tool, Map<String, JsonNode> globals) {
        for (JsonNode g : tool.descriptor.path("variables").path("globals")) {
            String name = g.path("name").asText();
            tool.globals.put(name, new DeclaredValues.Entry(g.path("type").asText(), globals.get(name)));
        }
        for (JsonNode s : tool.descriptor.path("variables").path("secrets")) {
            String name = s.path("name").asText();
            String type = s.path("type").asText();
            JsonNode value = config.secret(name, json);
            if (value != null) {
                value = coerce(type, value, "secret " + name);
                redactor.add(value);
            } else if (config.mode != Config.Mode.GOVERNED) {
                // Under a Tool Host a secret without a handle is injected by the broker, never seen here.
                diagnostics.println("toolgate: WARNING secret " + name + " of " + tool.toolId + " has no value; set TOOLGATE_SECRET_"
                        + name.toUpperCase(java.util.Locale.ROOT) + " or secrets." + name + " in toolgate.dev.json");
            }
            tool.secrets.put(name, new DeclaredValues.Entry(type, value));
        }
    }

    /** The declared claims of one call, read from its identity: fixtures in development, the verified credential otherwise. */
    private static Map<String, DeclaredValues.Entry> claims(Tool tool, JsonNode identity) {
        Map<String, DeclaredValues.Entry> out = new LinkedHashMap<>();
        for (JsonNode c : tool.descriptor.path("variables").path("claims")) {
            String claim = c.path("claim").asText();
            JsonNode value = identity == null ? null : claim.startsWith("/") ? identity.at(claim) : identity.get(claim);
            if (value != null && value.isMissingNode()) value = null;
            out.put(c.path("name").asText(), new DeclaredValues.Entry(c.path("type").asText(), value));
        }
        return out;
    }

    /** The MCP name of the tool with this tool id, or null. */
    String mcpNameOf(String toolId) {
        for (Tool t : byMcpName.values()) if (t.toolId.equals(toolId)) return t.mcpName;
        return null;
    }

    // ---- ToolCatalog ----

    @Override
    public ObjectNode serverInfo() {
        ObjectNode info = JSON.objectNode();
        info.put("name", packageInfo.path("id").asText());
        info.put("version", "standalone");
        return info;
    }

    @Override
    public ObjectNode discoverMeta() {
        ObjectNode meta = JSON.objectNode();
        ObjectNode digests = meta.putObject("io.ololabs.toolgate/descriptorDigests");
        TreeMap<String, String> byToolId = new TreeMap<>();
        for (Tool t : byMcpName.values()) byToolId.put(t.toolId, t.digest);
        StringBuilder set = new StringBuilder("[");
        for (Map.Entry<String, String> e : byToolId.entrySet()) {
            digests.put(e.getKey(), e.getValue());
            if (set.length() > 1) set.append(',');
            set.append("{\"toolDescriptorDigest\":\"").append(e.getValue()).append("\",\"toolId\":\"").append(e.getKey()).append("\"}");
        }
        meta.put("io.ololabs.toolgate/descriptorSetDigest", sha256(set.append(']').toString().getBytes(StandardCharsets.UTF_8)));
        return meta;
    }

    @Override
    public JsonNode tools(RequestMeta meta) {
        ArrayNode tools = JSON.arrayNode();
        for (Tool t : byMcpName.values()) {
            if (t.async && !meta.declares(RequestMeta.TASKS)) continue; // never offer work the client cannot collect (platform-requirements.md §12.5)
            JsonNode d = t.descriptor;
            ObjectNode tool = tools.addObject();
            tool.put("name", t.mcpName);
            tool.put("title", d.path("title").asText());
            String description = d.path("description").asText();
            if (d.has("useWhen")) description += "\n\nUse when: " + d.path("useWhen").asText();
            tool.put("description", description);
            tool.set("inputSchema", d.path("inputSchema"));
            if (d.has("outputSchema")) tool.set("outputSchema", d.get("outputSchema"));
            ObjectNode annotations = tool.putObject("annotations");
            String safety = d.path("effect").path("safety").asText();
            annotations.put("readOnlyHint", safety.equals("READ_ONLY"));
            annotations.put("destructiveHint", d.path("effect").path("destructive").asBoolean());
            annotations.put("idempotentHint", safety.equals("READ_ONLY") || safety.equals("IDEMPOTENT"));
            annotations.put("openWorldHint", d.path("effect").path("openWorld").asBoolean());
            if (t.async) tool.putObject("execution").put("taskSupport", "required");
        }
        return tools;
    }

    @Override
    public ObjectNode call(String name, JsonNode arguments, RequestMeta meta) {
        Tool tool = byMcpName.get(name);
        if (tool == null) throw new ProtocolError(ProtocolError.INVALID_PARAMS, "Unknown tool: " + name);
        if (tool.async && !meta.declares(RequestMeta.TASKS)) throw ProtocolError.missingExtension(RequestMeta.TASKS);
        Prepared prepared = prepare(tool, (ObjectNode) arguments, meta);
        if (prepared.context != null && !tool.async) {
            prepared.context.attachProgress(meta.progressToken(), meta.notifications());
            prepared.context.attachInputResponses(meta.inputResponses());
        }
        if (prepared.refused != null) return marked(prepared.refused);
        if (tool.reserves) {
            ObjectNode answered = reserve(tool, prepared, meta);
            if (answered != null) return answered;
        }
        if (tool.async) return startTask(tool, prepared, meta);
        ObjectNode result = execute(tool, prepared);
        ObjectNode pending = prepared.context.pendingInput();
        if (prepared.reserved) finish(prepared, pending != null ? Reservations.AWAITING_INPUT : outcome(result), pending != null ? null : result);
        return pending != null ? pending : marked(result);
    }

    /**
     * Reserves the call's business key (execution.md §4, platform-requirements.md §11.7). Returns the answer when the call must not run
     * (a refusal, or the existing invocation's result or task), or null when this call may run.
     */
    private ObjectNode reserve(Tool tool, Prepared prepared, RequestMeta meta) {
        Redactor redact = prepared.redact;
        String argument = tool.businessKey.path("argument").asText("");
        JsonNode fromArgument = argument.isEmpty() ? null : prepared.filled.get(argument);
        String key = fromArgument != null && fromArgument.isTextual() ? fromArgument.asText() : null;
        String header = meta.idempotencyKey();
        if (key != null && header != null && !key.equals(header)) {
            return marked(failure(tool, redact, "INVALID_ARGUMENT", "The business key in " + argument + " and the Idempotency-Key header differ", false, null));
        }
        if (key == null) key = header;
        if (key == null) {
            if (!tool.businessKey.path("required").asBoolean()) return null;
            return marked(failure(tool, redact, "INVALID_ARGUMENT", "This tool needs a business key: send "
                    + (argument.isEmpty() ? "" : "the " + argument + " argument or ") + "the Idempotency-Key header", false, null));
        }
        if (key.isEmpty() || key.length() > 255) {
            return marked(failure(tool, redact, "INVALID_ARGUMENT", "A business key has 1 to 255 characters", false, null));
        }
        // Identities come from the authenticated request only, never from arguments.
        String user = null;
        String sub = null;
        if (config.mode.configuredClaims()) {
            JsonNode claims = config.claims();
            if (claims != null && claims.path("sub").isTextual()) {
                sub = claims.get("sub").asText();
                user = "fixture:" + sub;
            }
        } else if (meta.identity() != null) {
            sub = meta.identity().subject();
            user = meta.identity().owner();
        }
        String caller = user != null ? user : "local";
        String namespace = tool.businessKey.path("namespace").asText("AGENT");
        String namespaceIdentity;
        switch (namespace) {
            case "DELEGATED_USER" -> {
                if (user == null) {
                    return marked(failure(tool, redact, "BUSINESS_KEY_NAMESPACE_MISMATCH",
                            "This tool keys operations per verified user, and this call has none", false, null));
                }
                namespaceIdentity = user;
            }
            case "TENANT" -> namespaceIdentity = "";
            default -> namespaceIdentity = caller;
        }
        ObjectNode fingerprint = JSON.objectNode();
        fingerprint.put("argumentsDigest", io.ololabs.toolgate.protocol.Jcs.digest(prepared.filled));
        fingerprint.put("package", packageInfo.path("id").asText());
        fingerprint.put("effect", tool.descriptor.path("effect").path("safety").asText());
        fingerprint.put("scope", "standalone");
        if (!namespace.equals("DELEGATED_USER") && sub != null) fingerprint.put("userId", user);
        String invocationId = prepared.context.invocationId();
        Reservations.Outcome outcome;
        try {
            outcome = reservations.reserve(tool.toolId, namespace, namespaceIdentity, key, io.ololabs.toolgate.protocol.Jcs.digest(fingerprint),
                    caller, invocationId);
        } catch (java.sql.SQLException e) {
            diagnostics.println("toolgate: business-key store failed for " + tool.toolId + ": " + e.getMessage());
            return marked(failure(tool, redact, "BUSINESS_KEY_STORE_UNAVAILABLE", "The business-key store is unavailable; nothing ran", true, null));
        }
        switch (outcome.kind()) {
            case NEW -> {
                prepared.reserved = true;
                String forwarded = switch (namespace) {
                    case "TENANT" -> key;
                    case "DELEGATED_USER" -> "u." + reservations.identityHash(namespaceIdentity) + "." + key;
                    default -> "a." + reservations.identityHash(namespaceIdentity) + "." + key;
                };
                prepared.context.attachBusinessKey(key);
                if (tool.businessKey.path("idempotencyScope").asText("").equals("BUSINESS_KEY")) prepared.routes.idempotencyValue(forwarded);
                return null;
            }
            case EXPIRED -> {
                return marked(failure(tool, redact, "EXPIRED_KEY", "This business key was used before and its record has expired; use a new key", false, null));
            }
            case IN_USE -> {
                return marked(failure(tool, redact, "IDEMPOTENCY_KEY_IN_USE", "This business key is in use", false, null));
            }
            case CONFLICT -> {
                return marked(failure(tool, redact, "IDEMPOTENCY_CONFLICT", "This business key was used with different arguments", false, null));
            }
            default -> {
                // EXISTING: never run again; answer with the original task or result.
                if (outcome.taskId() != null && meta.declares(RequestMeta.TASKS)) {
                    java.util.Optional<TaskRecord> task = tasks.find(meta.owner(), outcome.taskId());
                    if (task.isPresent()) return Tasks.createResult(task.get());
                }
                if (outcome.result() != null) return marked(outcome.result());
                if (outcome.state().equals(Reservations.RUNNING)) {
                    return marked(failure(tool, redact, "IN_PROGRESS", "The operation with this business key is still running", true, null));
                }
                return marked(failure(tool, redact, OUTCOME_UNKNOWN, "The operation with this business key stopped without a result,"
                        + " so its effect may have happened", false, null));
            }
        }
    }

    private void finish(Prepared prepared, String state, ObjectNode result) {
        try {
            reservations.finish(prepared.context.invocationId(), state, result);
        } catch (java.sql.SQLException e) {
            diagnostics.println("toolgate: business-key store failed to record " + prepared.context.invocationId() + ": " + e.getMessage());
        }
    }

    /** The reservation state a result ends in. */
    private static String outcome(ObjectNode result) {
        if (!result.path("isError").asBoolean()) return "COMPLETED";
        return switch (result.path("structuredContent").path("error").path("code").asText()) {
            case OUTCOME_UNKNOWN -> OUTCOME_UNKNOWN;
            case "CONCURRENCY_LIMIT" -> Reservations.NOT_STARTED;
            case "CANCELLED" -> "CANCELLED";
            default -> "FAILED";
        };
    }

    @Override
    public Tasks tasks() {
        return tasks;
    }

    private ObjectNode marked(ObjectNode result) {
        if (config.mode.fixtures()) result.putObject("_meta").put("io.ololabs.toolgate/identity", "fixture");
        return result;
    }

    /** Runs an async tool as a task: the CreateTaskResult goes back at once and the handler runs on. */
    private ObjectNode startTask(Tool tool, Prepared prepared, RequestMeta meta) {
        StandaloneContext context = prepared.context;
        TaskRecord task;
        try {
            task = tasks.create(meta.owner(), tool.cancelIfAbandoned, "Running", context::cancel);
        } catch (RuntimeException e) {
            // No task exists, so nothing runs; a reserved key goes back to the caller's next attempt.
            diagnostics.println("toolgate: task store failed to create a task for " + tool.toolId + ": " + prepared.redact.apply(String.valueOf(e)));
            if (prepared.reserved) finish(prepared, Reservations.NOT_STARTED, null);
            return marked(failure(tool, prepared.redact, "TASK_STORE_UNAVAILABLE", "The task store is unavailable; nothing ran", true, null));
        }
        context.attachTask(tasks, task.taskId);
        if (prepared.reserved) {
            try {
                reservations.attachTask(context.invocationId(), task.taskId);
            } catch (java.sql.SQLException e) {
                diagnostics.println("toolgate: business-key store failed to record task " + task.taskId + ": " + e.getMessage());
            }
        }
        workers.execute(() -> {
            try {
                ObjectNode result = execute(tool, prepared);
                JsonNode error = result.path("structuredContent").path("error");
                boolean cancelled = tasks.cancelRequested(task.taskId);
                if (prepared.reserved) finish(prepared, cancelled ? "CANCELLED" : outcome(result), cancelled ? null : result);
                if (cancelled) tasks.cancelled(task.taskId);
                else if (error.path("code").asText().equals(OUTCOME_UNKNOWN)) tasks.fail(task.taskId, ProtocolError.INTERNAL_ERROR, error.path("message").asText(), error);
                else tasks.complete(task.taskId, marked(result));
            } catch (RuntimeException e) {
                diagnostics.println("toolgate: task " + task.taskId + " of " + tool.toolId + " failed: " + prepared.redact.apply(String.valueOf(e)));
                tasks.fail(task.taskId, ProtocolError.INTERNAL_ERROR, "Internal error");
            }
        });
        return Tasks.createResult(task);
    }

    // ---- Invocation ----

    /** A validated call ready to run, or the error result that refused it before the handler. */
    private static final class Prepared {
        ObjectNode refused;
        Redactor redact;
        StandaloneContext context;
        Object[] args;
        Instant deadline;
        ObjectNode filled;
        RouteClient routes;
        boolean reserved;
    }

    private Prepared prepare(Tool tool, ObjectNode arguments, RequestMeta meta) {
        Prepared prepared = new Prepared();
        Redactor redact = redactor.copy();
        prepared.redact = redact;
        JsonNode properties = tool.descriptor.path("inputSchema").path("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = arguments.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            if (properties.path(f.getKey()).path("x-toolgate-sensitive").asBoolean()) redact.add(f.getValue());
        }
        try {
            if (json.writeValueAsBytes(arguments).length > MAX_INPUT_BYTES) {
                prepared.refused = failure(tool, redact, "INVALID_ARGUMENT", "The arguments are larger than 1 MiB", false, null);
                return prepared;
            }
        } catch (JsonProcessingException e) {
            prepared.refused = failure(tool, redact, "INVALID_ARGUMENT", "The arguments are not valid JSON", false, null);
            return prepared;
        }
        if (arguments.size() > MAX_ARGUMENTS) {
            prepared.refused = failure(tool, redact, "INVALID_ARGUMENT", "More than " + MAX_ARGUMENTS + " arguments", false, null);
            return prepared;
        }
        String invalid = tool.input.validate(arguments);
        if (invalid != null) {
            prepared.refused = failure(tool, redact, "INVALID_ARGUMENT", "Invalid arguments: " + invalid.substring(1), false, null);
            return prepared;
        }
        ObjectNode filled = arguments.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> declared = properties.properties().iterator();
        Map<String, DeclaredValues.Entry> inputs = new LinkedHashMap<>();
        while (declared.hasNext()) {
            Map.Entry<String, JsonNode> p = declared.next();
            if (!filled.has(p.getKey()) && p.getValue().has("default")) filled.set(p.getKey(), p.getValue().get("default"));
            inputs.put(p.getKey(), new DeclaredValues.Entry(DeclaredValues.typeOf(p.getValue()), filled.get(p.getKey())));
        }

        String invocationId = config.governedInvocationId != null ? config.governedInvocationId : UUID.randomUUID().toString();
        Instant deadline = Instant.now().plusMillis(tool.deadlineMs);
        if (config.governedDeadline != null && config.governedDeadline.isBefore(deadline)) deadline = config.governedDeadline;
        // Claims come from fixtures in development and test, and only from the verified credential otherwise (runtime-modes.md §4).
        JsonNode identity = config.mode.configuredClaims() ? config.claims() : meta.identity() == null ? null : meta.identity().claims();
        DeclaredValues claims = new DeclaredValues("claim", claims(tool, identity),
                n -> new PlatformError(PlatformError.Code.CLAIMS_UNAVAILABLE, "Claim " + n + " is not available"));
        claims.identity = identity;
        StandaloneContext context = new StandaloneContext(
                new DeclaredValues("input", inputs, n -> new ToolConfigurationError("input " + n + " has no value")),
                new DeclaredValues("global", tool.globals, n -> new ToolConfigurationError("global " + n + " has no value")),
                new DeclaredValues("secret", tool.secrets, n -> new ToolConfigurationError("secret " + n + " has no value; set TOOLGATE_SECRET_" + n.toUpperCase(java.util.Locale.ROOT))),
                claims, invocationId, tool.toolId, deadline, redact, diagnostics);
        context.attachResponses(config.responses);
        if (config.mode == Config.Mode.GOVERNED) context.attachMode(io.ololabs.toolgate.sdk.context.InvocationMode.GOVERNED);
        if (config.mode == Config.Mode.GOVERNED) context.attachPrincipal(config.governedPrincipal);
        if (config.mode == Config.Mode.GOVERNED) context.attachExecution(config.governedExecution, config.governedTraceparent);

        RouteClient routes = new RouteClient(egress, tool.routes, tool.auths, tool.secrets, context, deadline, redact);
        String idempotencyHeader = tool.businessKey.path("idempotencyHeader").asText("");
        // Forwarded upstream only through the declared header: the business key once reserved, else a value stable for this invocation.
        if (!idempotencyHeader.isEmpty()) routes.idempotency(idempotencyHeader, "i." + invocationId);
        context.attachHttp(routes);
        context.attachTcp(new TcpClient(egress, tool.tcp, context, deadline));
        boolean readFiles = false, writeFiles = false;
        for (JsonNode p : tool.descriptor.path("permissions")) { readFiles |= p.asText().equals("DEVICE_FILES_READ"); writeFiles |= p.asText().equals("DEVICE_FILES_WRITE"); }
        if (config.deviceFiles != null && readFiles) context.attachFiles(writeFiles ? config.deviceFiles : StdioBroker.readOnly(config.deviceFiles));
        prepared.routes = routes;
        prepared.filled = filled;
        prepared.context = context;
        prepared.deadline = deadline;
        try {
            prepared.args = bind(tool, filled, context);
        } catch (Converter.ConversionException e) {
            prepared.refused = failure(tool, redact, "INVALID_ARGUMENT", "Invalid arguments: " + e.getMessage(), false, null);
        }
        return prepared;
    }

    private ObjectNode execute(Tool tool, Prepared prepared) {
        Redactor redact = prepared.redact;
        StandaloneContext context = prepared.context;
        Object[] args = prepared.args;
        Instant deadline = prepared.deadline;
        if (tool.concurrency != null) {
            boolean acquired;
            try {
                acquired = tool.concurrency.tryAcquire(CONCURRENCY_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acquired = false;
            }
            if (!acquired) return platform(tool, redact, PlatformError.Code.CONCURRENCY_LIMIT, "Too many concurrent calls to this tool");
        }
        Future<Object> running;
        try {
            running = workers.submit(() -> {
                context.worker = Thread.currentThread();
                // A cancel that arrived before this thread started interrupted nobody; deliver it now.
                if (context.isCancelled()) Thread.currentThread().interrupt();
                try {
                    Object value = tool.method.invoke(tool.instance, args);
                    if (value instanceof CompletionStage<?> stage) {
                        long left = Math.max(1, deadline.toEpochMilli() - System.currentTimeMillis());
                        value = stage.toCompletableFuture().get(left, TimeUnit.MILLISECONDS);
                    }
                    return value;
                } finally {
                    if (tool.concurrency != null) tool.concurrency.release();
                }
            });
        } catch (RuntimeException e) {
            if (tool.concurrency != null) tool.concurrency.release();
            throw e;
        }
        Object value;
        long cancelSeen = 0;
        try {
            while (true) {
                long left = deadline.toEpochMilli() - System.currentTimeMillis();
                if (left <= 0) {
                    context.cancel();
                    running.cancel(true);
                    return platform(tool, redact, PlatformError.Code.DEADLINE_EXCEEDED, "The tool did not finish within " + tool.deadlineMs + " ms");
                }
                try {
                    value = running.get(Math.min(left, 100), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException e) {
                    if (!context.isCancelled()) continue;
                    long now = System.currentTimeMillis();
                    if (cancelSeen == 0) cancelSeen = now;
                    else if (now - cancelSeen >= CANCEL_GRACE_MS) {
                        // A handler that ignores cancellation is abandoned and its outcome discarded (execution.md §3).
                        running.cancel(true);
                        diagnostics.println("toolgate: " + tool.toolId + " " + context.invocationId() + " did not stop within the cancellation grace; abandoned");
                        return platform(tool, redact, PlatformError.Code.CANCELLED, "The invocation was cancelled");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.cancel();
            running.cancel(true);
            return platform(tool, redact, PlatformError.Code.CANCELLED, "The invocation was cancelled");
        } catch (ExecutionException e) {
            return thrown(tool, redact, unwrap(e.getCause()));
        }
        return complete(tool, redact, value);
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof InvocationTargetException || t instanceof ExecutionException
                || t instanceof java.util.concurrent.CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static Object[] bind(Tool tool, ObjectNode arguments, StandaloneContext context) {
        Type[] types = tool.method.getGenericParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            String name = tool.parameters.get(i);
            args[i] = name.equals("$context") ? context : Converter.fromJson(arguments.get(name), types[i]);
        }
        return args;
    }

    private ObjectNode complete(Tool tool, Redactor redact, Object value) {
        ObjectNode result = JSON.objectNode();
        try {
            if (value instanceof ToolResult r) {
                result.set("content", Converter.toJson(r.content()));
                if (r.structuredContent() != null) result.set("structuredContent", Converter.toJson(r.structuredContent()));
            } else {
                JsonNode structured = Converter.toJson(value);
                if (tool.output != null) {
                    String invalid = tool.output.validate(structured);
                    if (invalid != null) {
                        diagnostics.println("toolgate: " + tool.toolId + " returned output that does not match its outputSchema: " + redact.apply(invalid));
                        return failure(tool, redact, "INTERNAL_TOOL_ERROR", FAILED, false, null);
                    }
                }
                String text = json.writeValueAsString(structured);
                result.putArray("content").addObject().put("type", "text").put("text", text);
                result.set("structuredContent", structured);
            }
            if (json.writeValueAsBytes(result).length > MAX_OUTPUT_BYTES) {
                diagnostics.println("toolgate: " + tool.toolId + " output is larger than 4 MiB");
                return failure(tool, redact, "INTERNAL_TOOL_ERROR", FAILED, false, null);
            }
        } catch (Converter.ConversionException | JsonProcessingException e) {
            diagnostics.println("toolgate: " + tool.toolId + " returned a value that is not JSON: " + e.getMessage());
            return failure(tool, redact, "INTERNAL_TOOL_ERROR", FAILED, false, null);
        }
        result.put("isError", false);
        return result;
    }

    /** The error mapping of errors.md §2. */
    private ObjectNode thrown(Tool tool, Redactor redact, Throwable t) {
        if (t instanceof StandaloneContext.InputNeeded) return JSON.objectNode(); // the round ends with its InputRequiredResult
        if (t instanceof ToolInputError e) return failure(tool, redact, "INVALID_ARGUMENT", e.getMessage(), false, null);
        if (t instanceof ToolConfigurationError e) return failure(tool, redact, "CONFIGURATION", e.getMessage(), false, null);
        if (t instanceof PlatformError e) return platform(tool, redact, e.platformCode(), e.getMessage());
        if (t instanceof UpstreamError e) {
            ObjectNode data = JSON.objectNode();
            data.put("route", e.route());
            e.status().ifPresent(s -> data.put("status", s));
            e.upstreamRequestId().ifPresent(id -> data.put("upstreamRequestId", id));
            data.put("effectUncertain", e.effectUncertain());
            if (tool.ledger && e.effectUncertain()) {
                // The request went out and no complete response came back: the invocation ends OUTCOME_UNKNOWN (errors.md §2).
                data.put("upstreamCode", e.code());
                return failure(tool, redact, OUTCOME_UNKNOWN, "The call on route " + e.route()
                        + " was sent and no complete response arrived, so its effect may have happened.", false, data);
            }
            return failure(tool, redact, e.code(), e.getMessage(), retryable(tool, e.code()), data);
        }
        if (t instanceof ToolRetryableError e) {
            if (!declared(tool, e.code())) return undeclared(tool, redact, e);
            ObjectNode data = JSON.objectNode();
            e.retryAfter().ifPresent(d -> data.put("retryAfterMs", d.toMillis()));
            return failure(tool, redact, e.code(), e.getMessage(), retryable(tool, e.code()), data);
        }
        if (t instanceof ToolBusinessError e) {
            if (!declared(tool, e.code())) return undeclared(tool, redact, e);
            JsonNode data;
            try {
                data = Converter.toJson(e.data());
            } catch (Converter.ConversionException ce) {
                return internal(tool, redact, ce);
            }
            SchemaValidator schema = tool.errorData.get(e.code());
            if (schema != null && schema.validate(data) != null) {
                diagnostics.println("toolgate: " + tool.toolId + " raised " + e.code() + " with data that does not match its dataSchema");
                return failure(tool, redact, "INTERNAL_TOOL_ERROR", FAILED, false, null);
            }
            return failure(tool, redact, e.code(), e.getMessage(), false, data);
        }
        if (t instanceof ToolError e) return failure(tool, redact, e.code(), e.getMessage(), false, null);
        return internal(tool, redact, t);
    }

    private ObjectNode internal(Tool tool, Redactor redact, Throwable t) {
        java.io.StringWriter trace = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(trace));
        diagnostics.println("toolgate: " + tool.toolId + " failed: " + redact.apply(trace.toString()));
        return failure(tool, redact, "INTERNAL_TOOL_ERROR", FAILED, false, null);
    }

    private ObjectNode undeclared(Tool tool, Redactor redact, ToolError e) {
        ObjectNode data = JSON.objectNode();
        data.put("originalCode", e.code());
        return failure(tool, redact, "UNDECLARED_ERROR", e.getMessage(), false, data);
    }

    private static boolean declared(Tool tool, String code) {
        for (JsonNode e : tool.descriptor.path("errors")) if (e.path("code").asText().equals(code)) return true;
        return false;
    }

    /** Retryable only when the tool needs no execution ledger; the downgrade is recorded. */
    private boolean retryable(Tool tool, String code) {
        if (tool.ledger) {
            diagnostics.println("toolgate: " + tool.toolId + " " + code + " reported as not retryable: the tool is "
                    + tool.descriptor.path("effect").path("safety").asText());
            return false;
        }
        return true;
    }

    private ObjectNode platform(Tool tool, Redactor redact, PlatformError.Code code, String message) {
        boolean retryable = (code == PlatformError.Code.DEADLINE_EXCEEDED || code == PlatformError.Code.CONCURRENCY_LIMIT) && !tool.ledger;
        return failure(tool, redact, code.name(), message, retryable, null);
    }

    /** The result shape of errors.md §3, redacted and capped (§4). */
    private ObjectNode failure(Tool tool, Redactor redact, String code, String message, boolean retryable, JsonNode data) {
        String text = Redactor.cap(redact.apply(message == null || message.isEmpty() ? code : message), MAX_MESSAGE_BYTES);
        JsonNode safeData = data == null ? JSON.objectNode() : redact.apply(data);
        try {
            if (json.writeValueAsBytes(safeData).length > MAX_DATA_BYTES) {
                ObjectNode truncated = JSON.objectNode();
                truncated.put("truncated", true);
                safeData = truncated;
            }
        } catch (JsonProcessingException e) {
            safeData = JSON.objectNode();
        }
        ObjectNode result = JSON.objectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        ObjectNode error = result.putObject("structuredContent").putObject("error");
        error.put("code", code);
        error.put("message", text);
        error.put("retryable", retryable);
        error.set("data", safeData);
        result.put("isError", true);
        return result;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
