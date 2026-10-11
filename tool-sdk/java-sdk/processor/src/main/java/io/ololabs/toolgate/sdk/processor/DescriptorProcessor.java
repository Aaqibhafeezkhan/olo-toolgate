// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import io.ololabs.toolgate.sdk.annotations.AuthType;
import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.HttpAuth;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.Idempotency;
import io.ololabs.toolgate.sdk.annotations.JwtClaim;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Reconcile;
import io.ololabs.toolgate.sdk.annotations.RuntimeTier;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.SecretScope;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.TcpDestination;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ToolVariables;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.annotations.Variable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * Turns the authoring annotations into generation-1 semantic descriptors (tool-sdk/spec/descriptor). Writes the
 * canonical bytes of each descriptor to {@code META-INF/toolgate/tools/<local name>.json} and an index to
 * {@code META-INF/toolgate/package.json}. Requires {@code -Atoolgate.descriptorGeneration=1}.
 */
public final class DescriptorProcessor extends AbstractProcessor {
    public static final String GENERATION_OPTION = "toolgate.descriptorGeneration";
    static final int GENERATION = 1;

    static final Pattern PACKAGE_ID = Pattern.compile("^[a-z][a-z0-9-]{1,30}(\\.[a-z][a-z0-9-]{1,30}){1,3}$");
    static final Pattern LOCAL_NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,47}(\\.[a-z][a-z0-9_-]{0,47}){0,2}$");
    static final Pattern TOOL_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$");
    static final Pattern MCP_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");
    static final Pattern VARIABLE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    static final Pattern DECLARATION_ID = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9-]{1,128}$");
    static final List<String> RESERVED = List.of("toolgate.", "hotfolder.", "builtin.");
    static final Set<String> FORBIDDEN_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "host");
    static final String CONTEXT = "io.ololabs.toolgate.sdk.context.ToolContext";
    static final String TOOL_RESULT = "io.ololabs.toolgate.sdk.context.ToolResult";

    private static final List<Class<? extends Annotation>> ANNOTATIONS = List.of(ToolPackage.class, ServerTool.class,
            ClientTool.class, Destination.class, HttpAuth.class, HttpRoute.class,
            TcpDestination.class);

    private boolean done;
    private boolean failed;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("io.ololabs.toolgate.sdk.annotations.*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(GENERATION_OPTION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (done || round.processingOver()) return false;
        Set<Element> roots = new LinkedHashSet<>();
        for (Class<? extends Annotation> a : ANNOTATIONS) roots.addAll(round.getElementsAnnotatedWithAny(Set.of(a)));
        for (String container : List.of("Destinations", "HttpAuths", "HttpRoutes", "TcpDestinations")) {
            TypeElement c = processingEnv.getElementUtils().getTypeElement("io.ololabs.toolgate.sdk.annotations." + container);
            roots.addAll(round.getElementsAnnotatedWith(c));
        }
        if (roots.isEmpty()) return false;
        done = true;
        try {
            Element anchor = roots.iterator().next();
            String generation = processingEnv.getOptions().get(GENERATION_OPTION);
            if (generation == null) {
                throw new Problem(anchor, "the build must set " + GENERATION_OPTION
                        + " (descriptorGeneration in the Gradle or Maven plugin); it has no default");
            }
            if (!generation.trim().equals(String.valueOf(GENERATION))) {
                throw new Problem(anchor, GENERATION_OPTION + "=" + generation + " is not supported; this SDK emits generation " + GENERATION);
            }
            new Build(round).run();
        } catch (Problem p) {
            error(p.element, p.getMessage());
        }
        return false;
    }

    private void error(Element at, String message) {
        failed = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "toolgate: " + message, at);
    }

    /** One package's declarations, resolved into descriptors. */
    private final class Build {
        private final RoundEnvironment round;
        private final Map<String, Declared<Destination>> destinations = new TreeMap<>();
        private final Map<String, Declared<HttpAuth>> auths = new TreeMap<>();
        private final Map<String, Declared<HttpRoute>> routes = new TreeMap<>();
        private final Map<String, Declared<TcpDestination>> tcps = new TreeMap<>();
        private final Map<String, ExecutableElement> tools = new TreeMap<>();
        private final Map<String, String> reconcilers = new HashMap<>();
        private TypeElement packageType;
        private ToolPackage pkg;

        Build(RoundEnvironment round) {
            this.round = round;
        }

        void run() {
            packageDeclaration();
            network();
            for (Element e : round.getElementsAnnotatedWithAny(Set.of(ServerTool.class, ClientTool.class))) {
                guard(() -> register((ExecutableElement) e));
            }
            for (Element e : round.getElementsAnnotatedWith(Reconcile.class)) guard(() -> reconciler((ExecutableElement) e));
            if (failed) return;
            Map<String, Object> descriptors = new TreeMap<>();
            Map<String, ExecutableElement> handlers = new TreeMap<>();
            for (Map.Entry<String, ExecutableElement> t : tools.entrySet()) {
                guard(() -> {
                    descriptors.put(t.getKey(), descriptor(t.getKey(), t.getValue()));
                    handlers.put(t.getKey(), t.getValue());
                });
            }
            if (failed) return;
            write(descriptors, handlers);
        }

        private void guard(Runnable step) {
            try {
                step.run();
            } catch (Problem p) {
                error(p.element, p.getMessage());
            }
        }

        private void packageDeclaration() {
            Set<? extends Element> declared = round.getElementsAnnotatedWith(ToolPackage.class);
            if (declared.size() != 1) {
                Element at = declared.isEmpty()
                        ? round.getElementsAnnotatedWithAny(Set.of(ServerTool.class, ClientTool.class)).stream().findFirst().orElse(null)
                        : declared.iterator().next();
                throw new Problem(at, "exactly one @ToolPackage is required per package; found " + declared.size());
            }
            packageType = (TypeElement) declared.iterator().next();
            pkg = packageType.getAnnotation(ToolPackage.class);
            if (!PACKAGE_ID.matcher(pkg.id()).matches()) {
                throw new Problem(packageType, "package id " + pkg.id() + " does not match " + PACKAGE_ID.pattern());
            }
            reserved(pkg.id(), "package id", packageType);
            if (pkg.name().isBlank()) throw new Problem(packageType, "package name is required");
            if (pkg.publisher().isBlank()) throw new Problem(packageType, "package publisher is required");
        }

        private void network() {
            for (Element e : round.getRootElements()) collectNetwork(e);
            for (Declared<HttpRoute> r : routes.values()) guard(() -> route(r));
            for (Declared<Destination> d : destinations.values()) {
                guard(() -> {
                    variable(d.value.hostSetting(), "hostSetting of destination " + d.value.id(), d.at);
                    port(d.value.port(), d.at);
                });
            }
            for (Declared<TcpDestination> d : tcps.values()) {
                guard(() -> {
                    variable(d.value.hostSetting(), "hostSetting of TCP destination " + d.value.id(), d.at);
                    port(d.value.port(), d.at);
                });
            }
            for (Declared<HttpAuth> a : auths.values()) {
                guard(() -> {
                    if (!a.value.header().isEmpty() && !HEADER.matcher(a.value.header()).matches()) {
                        throw new Problem(a.at, "auth " + a.value.id() + " header is not a header name");
                    }
                    if (!a.value.secret().isEmpty()) variable(a.value.secret(), "secret of auth " + a.value.id(), a.at);
                    if (!a.value.connection().isEmpty()) declarationId(a.value.connection(), "connection", a.at);
                });
            }
        }

        private void collectNetwork(Element e) {
            if (e instanceof TypeElement type) {
                for (Destination d : type.getAnnotationsByType(Destination.class)) put(destinations, d.id(), d, type, "destination");
                for (HttpAuth a : type.getAnnotationsByType(HttpAuth.class)) put(auths, a.id(), a, type, "auth");
                for (HttpRoute r : type.getAnnotationsByType(HttpRoute.class)) put(routes, r.id(), r, type, "route");
                for (TcpDestination t : type.getAnnotationsByType(TcpDestination.class)) put(tcps, t.id(), t, type, "TCP destination");
                for (Element inner : type.getEnclosedElements()) collectNetwork(inner);
            }
        }

        private <A> void put(Map<String, Declared<A>> map, String id, A value, Element at, String kind) {
            guard(() -> {
                declarationId(id, kind + " id", at);
                if (map.put(id, new Declared<>(value, at)) != null) {
                    throw new Problem(at, kind + " id " + id + " is declared twice in the package");
                }
            });
        }

        private void route(Declared<HttpRoute> d) {
            HttpRoute r = d.value;
            if (!destinations.containsKey(r.destination())) {
                throw new Problem(d.at, "route " + r.id() + " names undeclared destination " + r.destination());
            }
            if (!r.auth().isEmpty() && !auths.containsKey(r.auth())) {
                throw new Problem(d.at, "route " + r.id() + " names undeclared auth " + r.auth());
            }
            pathTemplate(r, d.at);
            String authHeader = r.auth().isEmpty() ? "" : auths.get(r.auth()).value.header().toLowerCase(Locale.ROOT);
            for (String h : r.headers()) {
                String lower = h.toLowerCase(Locale.ROOT);
                if (!HEADER.matcher(h).matches()) throw new Problem(d.at, "route " + r.id() + " header " + h + " is not a header name");
                if (FORBIDDEN_HEADERS.contains(lower) || lower.startsWith("x-forwarded-") || lower.equals(authHeader)) {
                    throw new Problem(d.at, "route " + r.id() + " may not allow header " + h);
                }
            }
            unique(Arrays.asList(r.query()), "query name", d.at);
            unique(Arrays.asList(r.headers()), "header", d.at);
            unique(Arrays.asList(r.requestContentTypes()), "content type", d.at);
            range(r.maxRequestBytes(), 0, 1L << 30, "maxRequestBytes", d.at);
            range(r.maxResponseBytes(), 0, 1L << 30, "maxResponseBytes", d.at);
            range(r.timeoutMs(), 1, 900_000, "timeoutMs", d.at);
        }

        /** RFC 6570 level 1 with {name:regex}: every variable needs a pattern, and the patterns .* and .+ are refused. */
        private void pathTemplate(HttpRoute r, Element at) {
            String path = r.path();
            if (!path.startsWith("/")) throw new Problem(at, "route " + r.id() + " path must start with /");
            Set<String> names = new TreeSet<>();
            int i = 0;
            while (i < path.length()) {
                char c = path.charAt(i);
                if (c == '}') throw new Problem(at, "route " + r.id() + " path has an unmatched }");
                if (c != '{') {
                    i++;
                    continue;
                }
                int depth = 1;
                int j = i + 1;
                while (j < path.length() && depth > 0) {
                    if (path.charAt(j) == '{') depth++;
                    if (path.charAt(j) == '}') depth--;
                    j++;
                }
                if (depth != 0) throw new Problem(at, "route " + r.id() + " path has an unmatched {");
                String body = path.substring(i + 1, j - 1);
                int colon = body.indexOf(':');
                String name = colon < 0 ? body : body.substring(0, colon);
                variable(name, "path variable of route " + r.id(), at);
                if (!names.add(name)) throw new Problem(at, "route " + r.id() + " repeats path variable " + name);
                if (colon < 0) {
                    throw new Problem(at, "route " + r.id() + " path variable " + name + " needs a pattern, {" + name + ":regex}");
                }
                {
                    String regex = body.substring(colon + 1);
                    if (regex.equals(".*") || regex.equals(".+")) {
                        throw new Problem(at, "route " + r.id() + " path variable " + name + " may not use " + regex);
                    }
                    try {
                        Pattern.compile(regex);
                    } catch (RuntimeException e) {
                        throw new Problem(at, "route " + r.id() + " path variable " + name + " pattern does not compile");
                    }
                }
                i = j;
            }
        }

        private void register(ExecutableElement method) {
            ServerTool server = method.getAnnotation(ServerTool.class);
            ClientTool client = method.getAnnotation(ClientTool.class);
            if (server != null && client != null) throw new Problem(method, "a handler is either @ServerTool or @ClientTool");
            String name = server != null ? server.name() : client.name();
            if (!LOCAL_NAME.matcher(name).matches()) {
                throw new Problem(method, "tool name " + name + " does not match " + LOCAL_NAME.pattern());
            }
            reserved(name, "tool name", method);
            if (tools.put(name, method) != null) throw new Problem(method, "tool " + name + " is declared twice");
            handlerShape(method);
        }

        private void reconciler(ExecutableElement method) {
            Reconcile r = method.getAnnotation(Reconcile.class);
            String self = toolName(method);
            if (self == null) throw new Problem(method, "a @Reconcile handler must also be a @ServerTool or @ClientTool");
            if (!tools.containsKey(r.tool())) throw new Problem(method, "@Reconcile names unknown tool " + r.tool());
            if (r.tool().equals(self)) throw new Problem(method, "a tool cannot reconcile itself");
            if (reconcilers.put(r.tool(), self) != null) throw new Problem(method, "tool " + r.tool() + " has two @Reconcile handlers");
        }

        private String toolName(ExecutableElement method) {
            ServerTool s = method.getAnnotation(ServerTool.class);
            ClientTool c = method.getAnnotation(ClientTool.class);
            return s != null ? s.name() : c != null ? c.name() : null;
        }

        private void handlerShape(ExecutableElement method) {
            TypeElement owner = (TypeElement) method.getEnclosingElement();
            if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)) {
                throw new Problem(method, "a handler must be a public instance method");
            }
            if (!owner.getModifiers().contains(Modifier.PUBLIC) || owner.getKind() != ElementKind.CLASS
                    || owner.getModifiers().contains(Modifier.ABSTRACT)
                    || (owner.getNestingKind() == NestingKind.MEMBER && !owner.getModifiers().contains(Modifier.STATIC))
                    || (owner.getNestingKind() != NestingKind.TOP_LEVEL && owner.getNestingKind() != NestingKind.MEMBER)) {
                throw new Problem(method, "a handler must live in a public, concrete, top-level or static class");
            }
            List<ExecutableElement> constructors = ElementFilter.constructorsIn(owner.getEnclosedElements());
            boolean noArg = constructors.stream()
                    .anyMatch(c -> c.getParameters().isEmpty() && c.getModifiers().contains(Modifier.PUBLIC));
            if (!noArg) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "toolgate: " + owner + " has no public no-argument constructor; provide it through a ToolRegistry", owner);
            }
        }

        private Map<String, Object> descriptor(String name, ExecutableElement method) {
            ServerTool server = method.getAnnotation(ServerTool.class);
            ClientTool client = method.getAnnotation(ClientTool.class);
            Tool t = server != null ? Tool.of(server) : Tool.of(client);
            boolean isClient = client != null;
            TypeElement owner = (TypeElement) method.getEnclosingElement();

            String toolId = pkg.id() + "/" + name;
            if (toolId.length() > 128 || !TOOL_ID.matcher(toolId).matches()) {
                throw new Problem(method, "toolId " + toolId + " is longer than 128 characters or not a valid id");
            }
            String alias = pkg.id().substring(pkg.id().lastIndexOf('.') + 1);
            String mcpName = alias + "." + name;
            if (!MCP_NAME.matcher(mcpName).matches()) throw new Problem(method, "mcpName " + mcpName + " is longer than 128 characters");

            if (t.title.isEmpty() == t.text.isEmpty()) {
                throw new Problem(method, "tool " + name + " must set exactly one of title and text");
            }
            String title = t.title.isEmpty() ? t.text : t.title;
            length(title, 1, 100, "title", method);
            length(t.description, 1, 2048, "description", method);
            if (t.useWhen.length() > 512) throw new Problem(method, "useWhen is longer than 512 characters");

            ToolVariables grouped = method.getAnnotation(ToolVariables.class);
            Schemas schemas = new Schemas(processingEnv);
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            List<String> parameters = new ArrayList<>();
            inputs(method, grouped, schemas, properties, required, parameters);

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("descriptorGeneration", (long) GENERATION);
            d.put("toolId", toolId);
            d.put("mcpName", mcpName);
            d.put("kind", isClient ? "CLIENT" : "SERVER");
            d.put("title", title);
            d.put("description", t.description);
            if (!t.useWhen.isEmpty()) d.put("useWhen", t.useWhen);
            d.put("inputSchema", schemas.inputs(properties, required));
            Map<String, Object> output = output(method);
            if (output != null) d.put("outputSchema", output);
            d.put("errors", errors(t.errors, method));

            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("safety", t.effect.name());
            effect.put("destructive", t.destructive);
            effect.put("openWorld", switch (t.openWorld) {
                case TRUE -> true;
                case FALSE -> false;
                case DEFAULT -> !isClient && t.routes.length > 0;
            });
            d.put("effect", effect);
            d.put("businessKey", businessKey(t, method, properties.keySet()));
            d.put("execution", execution(t, method));
            if (reconcilers.containsKey(name)) d.put("reconcileTool", reconcilers.get(name));
            if (isClient) d.put("tiers", tiers(client.tiers(), method));

            Set<String> routeIds = new TreeSet<>(unique(Arrays.asList(t.routes), "route", method));
            Set<String> tcpIds = new TreeSet<>(unique(Arrays.asList(t.tcp), "TCP destination", method));
            List<Object> routeList = new ArrayList<>();
            Set<String> destinationIds = new TreeSet<>();
            Set<String> authIds = new TreeSet<>();
            for (String id : routeIds) {
                Declared<HttpRoute> r = routes.get(id);
                if (r == null) throw new Problem(method, "tool " + name + " lists undeclared route " + id);
                routeList.add(route(r.value));
                destinationIds.add(r.value.destination());
                if (!r.value.auth().isEmpty()) authIds.add(r.value.auth());
            }
            d.put("routes", routeList);
            d.put("destinations", destinationIds.stream().map(id -> destination(destinations.get(id).value)).collect(Collectors.toList()));
            d.put("httpAuth", authIds.stream().map(id -> auth(auths.get(id).value)).collect(Collectors.toList()));
            List<Object> tcpList = new ArrayList<>();
            for (String id : tcpIds) {
                Declared<TcpDestination> tcp = tcps.get(id);
                if (tcp == null) throw new Problem(method, "tool " + name + " lists undeclared TCP destination " + id);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("hostSetting", tcp.value.hostSetting());
                m.put("port", (long) tcp.value.port());
                tcpList.add(m);
            }
            d.put("tcp", tcpList);

            Variables vars = variables(method, owner, grouped, properties.keySet(), isClient);
            for (String authId : authIds) {
                HttpAuth a = auths.get(authId).value;
                if (!a.secret().isEmpty() && vars.secrets.stream().noneMatch(s -> s.name().equals(a.secret()))) {
                    throw new Problem(method, "auth " + authId + " uses secret " + a.secret() + ", which tool " + name + " does not declare");
                }
            }
            if (isClient && owner.getAnnotationsByType(HttpAuth.class).length > 0) {
                throw new Problem(method, "client tool " + name + " cannot be declared next to @HttpAuth");
            }
            d.put("variables", vars.toJson());

            Set<String> permissions = new TreeSet<>();
            if (!routeIds.isEmpty()) permissions.add("HTTP_ROUTE");
            if (!tcpIds.isEmpty()) {
                permissions.add("TCP");
                permissions.add("CREDENTIALS_IN_SANDBOX");
            }
            for (String authId : authIds) {
                AuthType type = auths.get(authId).value.type();
                if (type == AuthType.CUSTOM) permissions.add("CREDENTIALS_IN_SANDBOX");
                if (type == AuthType.TOKEN_EXCHANGE) permissions.add("TOKEN_EXCHANGE");
                if (type == AuthType.CONNECTION) permissions.add("CONNECTION");
            }
            if (vars.secrets.stream().anyMatch(s -> s.scope() == SecretScope.USER)) permissions.add("USER_SECRET");
            d.put("permissions", Jcs.sorted(permissions));

            for (String set : t.toolsets) length(set, 1, 64, "toolset", method);
            d.put("toolsets", Jcs.sorted(unique(Arrays.asList(t.toolsets), "toolset", method)));

            Example[] examples = method.getAnnotationsByType(Example.class);
            if (examples.length > 8) throw new Problem(method, "tool " + name + " declares " + examples.length + " examples; at most 8 are allowed");
            for (Example e : examples) {
                if (e.arguments().isBlank()) throw new Problem(method, "an example needs arguments");
                if (!e.expectedOutput().isEmpty() && !e.expectedError().isEmpty()) {
                    throw new Problem(method, "an example expects either an output or an error");
                }
            }
            parametersByTool.put(name, parameters);
            Map<String, Object> dataSchemas = new TreeMap<>();
            for (ErrorDecl e : t.errors) if (!e.dataSchema().isEmpty()) dataSchemas.put(e.code(), e.dataSchema());
            dataSchemasByTool.put(name, dataSchemas);
            return d;
        }

        private final Map<String, List<String>> parametersByTool = new HashMap<>();
        private final Map<String, Map<String, Object>> dataSchemasByTool = new HashMap<>();

        private void inputs(ExecutableElement method, ToolVariables grouped, Schemas schemas, Map<String, Object> properties,
                List<String> required, List<String> parameters) {
            int contexts = 0;
            List<VariableElement> params = new ArrayList<>(method.getParameters());
            boolean groupedInputs = grouped != null && grouped.inputs().length > 0;
            for (VariableElement p : params) {
                if (schemas.qualifiedName(p.asType()).equals(CONTEXT)) {
                    contexts++;
                    parameters.add("$context");
                    continue;
                }
                if (groupedInputs) throw new Problem(p, "with @ToolVariables inputs the handler takes only ToolContext");
                Param param = p.getAnnotation(Param.class);
                if (param == null) throw new Problem(p, "parameter " + p.getSimpleName() + " needs @Param");
                Spec spec = Spec.of(param);
                String name = param.name().isEmpty() ? p.getSimpleName().toString() : param.name();
                input(name, spec, p.asType(), schemas, p, properties, required, schemas.optional(p, p.asType()));
                parameters.add(name);
            }
            if (contexts > 1) throw new Problem(method, "a handler takes at most one ToolContext");
            if (groupedInputs) {
                for (Variable v : grouped.inputs()) {
                    if (v.type() == ValueType.INFER || v.type() == ValueType.OBJECT || v.type() == ValueType.ARRAY) {
                        throw new Problem(method, "grouped input " + v.name() + " needs a scalar type; use the typed form for objects and arrays");
                    }
                    input(v.name(), Spec.of(v), scalarType(v.type()), schemas, method, properties, required, false);
                }
            }
        }

        private TypeMirror scalarType(ValueType type) {
            var elements = processingEnv.getElementUtils();
            var types = processingEnv.getTypeUtils();
            return switch (type) {
                case INT -> types.getPrimitiveType(TypeKind.INT);
                case LONG -> types.getPrimitiveType(TypeKind.LONG);
                case NUMBER -> types.getPrimitiveType(TypeKind.DOUBLE);
                case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
                case BINARY -> types.getArrayType(types.getPrimitiveType(TypeKind.BYTE));
                default -> elements.getTypeElement("java.lang.String").asType();
            };
        }

        private void input(String name, Spec spec, TypeMirror type, Schemas schemas, Element at,
                Map<String, Object> properties, List<String> required, boolean optional) {
            variable(name, "input name", at);
            if (spec.description().isEmpty()) throw new Problem(at, "input " + name + " needs a description");
            if (spec.description().length() > 1024) throw new Problem(at, "input " + name + " description is longer than 1024 characters");
            if (properties.put(name, schemas.schema(type, spec, at)) != null) throw new Problem(at, "input " + name + " is declared twice");
            if (spec.required() && spec.defaultValue().isEmpty() && !optional) required.add(name);
        }

        private Map<String, Object> output(ExecutableElement method) {
            TypeMirror type = method.getReturnType();
            Schemas schemas = new Schemas(processingEnv);
            if (type.getKind() == TypeKind.VOID) throw new Problem(method, "a handler returns a record, a POJO, a Map or ToolResult");
            TypeElement stage = processingEnv.getElementUtils().getTypeElement("java.util.concurrent.CompletionStage");
            var types = processingEnv.getTypeUtils();
            if (type.getKind() == TypeKind.DECLARED && types.isAssignable(types.erasure(type), types.erasure(stage.asType()))) {
                List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
                if (args.size() != 1) throw new Problem(method, "CompletionStage needs its result type");
                type = args.get(0);
            }
            if (schemas.qualifiedName(type).equals(TOOL_RESULT)) return null;
            return schemas.output(type, method);
        }

        private List<Object> errors(ErrorDecl[] errors, Element at) {
            Map<String, Object> byCode = new TreeMap<>();
            for (ErrorDecl e : errors) {
                if (!ERROR_CODE.matcher(e.code()).matches()) throw new Problem(at, "error code " + e.code() + " does not match " + ERROR_CODE.pattern());
                length(e.description(), 1, 1024, "error description", at);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", e.code());
                m.put("description", e.description());
                m.put("retryable", e.retryable());
                if (byCode.put(e.code(), m) != null) throw new Problem(at, "error code " + e.code() + " is declared twice");
            }
            return new ArrayList<>(byCode.values());
        }

        private Map<String, Object> businessKey(Tool t, ExecutableElement method, Set<String> inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("required", switch (t.requiresBusinessKey) {
                case REQUIRED -> true;
                case NOT_REQUIRED -> false;
                case DEFAULT -> t.effect == EffectSafety.NON_IDEMPOTENT && t.destructive;
            });
            m.put("namespace", t.namespace.name());
            if (!t.businessKeyArgument.isEmpty()) {
                if (!inputs.contains(t.businessKeyArgument)) {
                    throw new Problem(method, "businessKeyArgument " + t.businessKeyArgument + " is not an input of the tool");
                }
                m.put("argument", t.businessKeyArgument);
            }
            Idempotency idem = method.getAnnotation(Idempotency.class);
            if (idem != null) {
                if (!HEADER.matcher(idem.header()).matches()) throw new Problem(method, "@Idempotency header is not a header name");
                m.put("idempotencyHeader", idem.header());
                m.put("idempotencyScope", idem.scope().name());
            }
            return m;
        }

        private Map<String, Object> execution(Tool t, ExecutableElement method) {
            long ceiling = t.async ? 900_000 : 60_000;
            long duration = t.maxDurationMs == -1 ? (t.async ? 900_000 : 30_000) : t.maxDurationMs;
            if (duration < 1 || duration > ceiling) {
                throw new Problem(method, "maxDurationMs must be 1.." + ceiling + (t.async ? " for async tools" : " for sync tools; make the tool async for longer work"));
            }
            if (t.cancelIfAbandoned && !t.async) throw new Problem(method, "cancelIfAbandoned applies to async tools only");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("async", t.async);
            m.put("maxDurationMs", duration);
            m.put("stateless", t.stateless);
            if (t.maxConcurrency != -1) {
                range(t.maxConcurrency, 1, 1_048_576, "maxConcurrency", method);
                m.put("maxConcurrency", (long) t.maxConcurrency);
            }
            m.put("cancelIfAbandoned", t.cancelIfAbandoned);
            return m;
        }

        private List<String> tiers(RuntimeTier[] tiers, Element at) {
            if (tiers.length == 0) throw new Problem(at, "a client tool needs at least one tier");
            List<String> names = new ArrayList<>();
            for (RuntimeTier tier : tiers) names.add(tier.name());
            return Jcs.sorted(unique(names, "tier", at));
        }

        private Map<String, Object> route(HttpRoute r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("destination", r.destination());
            m.put("method", r.method().name());
            m.put("path", r.path());
            m.put("query", Jcs.sorted(Arrays.asList(r.query())));
            m.put("headers", Jcs.sorted(Arrays.asList(r.headers())));
            m.put("requestContentTypes", Jcs.sorted(Arrays.asList(r.requestContentTypes())));
            m.put("maxRequestBytes", r.maxRequestBytes());
            m.put("maxResponseBytes", r.maxResponseBytes());
            m.put("streaming", r.streaming().name());
            m.put("timeoutMs", (long) r.timeoutMs());
            if (!r.auth().isEmpty()) m.put("auth", r.auth());
            return m;
        }

        private Map<String, Object> destination(Destination d) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("hostSetting", d.hostSetting());
            m.put("port", (long) d.port());
            m.put("spkiPins", new ArrayList<>(Arrays.asList(d.spkiPins())));
            if (!d.caBundle().isEmpty()) m.put("caBundle", d.caBundle());
            return m;
        }

        private Map<String, Object> auth(HttpAuth a) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("type", a.type().name());
            if (!a.header().isEmpty()) m.put("header", a.header());
            if (!a.secret().isEmpty()) m.put("secret", a.secret());
            if (!a.connection().isEmpty()) m.put("connection", a.connection());
            m.put("scopes", new ArrayList<>(Arrays.asList(a.scopes())));
            if (!a.audience().isEmpty()) m.put("audience", a.audience());
            return m;
        }

        /** Globals, secrets and claims in scope: the package type, the handler's class, then the handler. */
        private Variables variables(ExecutableElement method, TypeElement owner, ToolVariables grouped, Set<String> inputs, boolean isClient) {
            Variables scope = new Variables();
            Set<Element> scopes = new LinkedHashSet<>(List.of(packageType, owner));
            for (Element s : scopes) scope.add(s, s.getAnnotationsByType(Global.class), s.getAnnotationsByType(Secret.class), s.getAnnotationsByType(JwtClaim.class));
            Variables tool = new Variables();
            tool.add(method, method.getAnnotationsByType(Global.class), method.getAnnotationsByType(Secret.class), method.getAnnotationsByType(JwtClaim.class));
            if (grouped != null) tool.add(method, grouped.globals(), grouped.secrets(), grouped.jwt());
            for (String n : tool.names.keySet()) {
                if (scope.names.containsKey(n)) {
                    throw new Problem(method, "tool-level " + tool.names.get(n) + " cannot override the declaration " + scope.names.get(n) + " of its package or class");
                }
            }
            Variables all = new Variables();
            all.merge(scope);
            all.merge(tool);
            Map<String, String> seen = new HashMap<>(all.names);
            for (String input : inputs) {
                String prior = seen.put(input.toLowerCase(Locale.ROOT), "input " + input);
                if (prior != null) throw new Problem(method, "input " + input + " and " + prior + " have the same name, ignoring case");
            }
            if (isClient && !all.secrets.isEmpty()) {
                throw new Problem(method, "client tool cannot use secrets; found " + all.secrets.get(0).name());
            }
            for (Global g : all.globals) global(g, method);
            return all;
        }

        private void reserved(String value, String what, Element at) {
            for (String prefix : RESERVED) {
                if (value.startsWith(prefix) || value.equals(prefix.substring(0, prefix.length() - 1))) {
                    throw new Problem(at, what + " " + value + " uses the reserved prefix " + prefix);
                }
            }
        }

        private void write(Map<String, Object> descriptors, Map<String, ExecutableElement> handlers) {
            List<Object> index = new ArrayList<>();
            try {
                for (Map.Entry<String, Object> e : descriptors.entrySet()) {
                    byte[] bytes = Jcs.bytes(e.getValue());
                    try (OutputStream out = processingEnv.getFiler()
                            .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/toolgate/tools/" + e.getKey() + ".json",
                                    handlers.values().toArray(new Element[0]))
                            .openOutputStream()) {
                        out.write(bytes);
                    }
                    ExecutableElement h = handlers.get(e.getKey());
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", e.getKey());
                    entry.put("toolDescriptorDigest", Jcs.sha256(bytes));
                    entry.put("handlerClass", ((TypeElement) h.getEnclosingElement()).getQualifiedName().toString());
                    entry.put("handlerMethod", h.getSimpleName().toString());
                    entry.put("parameters", parametersByTool.get(e.getKey()));
                    if (!dataSchemasByTool.get(e.getKey()).isEmpty()) entry.put("errorDataSchemas", dataSchemasByTool.get(e.getKey()));
                    index.add(entry);
                }
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("descriptorGeneration", (long) GENERATION);
                manifest.put("id", pkg.id());
                manifest.put("name", pkg.name());
                manifest.put("publisher", pkg.publisher());
                if (!pkg.description().isEmpty()) manifest.put("description", pkg.description());
                manifest.put("tools", index);
                try (OutputStream out = processingEnv.getFiler()
                        .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/toolgate/package.json", packageType)
                        .openOutputStream()) {
                    out.write(Jcs.bytes(manifest));
                }
            } catch (IOException | IllegalArgumentException e) {
                throw new Problem(packageType, "cannot write descriptors: " + e.getMessage());
            }
        }
    }

    /** Scope-level or tool-level globals, secrets and claims, with case-insensitive name tracking. */
    private static final class Variables {
        final List<Global> globals = new ArrayList<>();
        final List<Secret> secrets = new ArrayList<>();
        final List<JwtClaim> claims = new ArrayList<>();
        final Map<String, String> names = new LinkedHashMap<>();

        void add(Element at, Global[] gs, Secret[] ss, JwtClaim[] cs) {
            for (Global g : gs) {
                name(g.name(), "global " + g.name(), at);
                globals.add(g);
            }
            for (Secret s : ss) {
                name(s.name(), "secret " + s.name(), at);
                if (s.type() == ValueType.INFER) throw new Problem(at, "secret " + s.name() + " needs a type");
                secrets.add(s);
            }
            for (JwtClaim c : cs) {
                name(c.name(), "claim " + c.name(), at);
                if (c.claim().isEmpty() || c.claim().length() > 256) throw new Problem(at, "claim " + c.name() + " needs a claim of 1-256 characters");
                if (c.type() == ValueType.INFER) throw new Problem(at, "claim " + c.name() + " needs a type");
                claims.add(c);
            }
        }

        void merge(Variables other) {
            for (Map.Entry<String, String> e : other.names.entrySet()) {
                if (names.containsKey(e.getKey())) continue;
                names.put(e.getKey(), e.getValue());
            }
            globals.addAll(other.globals);
            secrets.addAll(other.secrets);
            claims.addAll(other.claims);
        }

        private void name(String name, String what, Element at) {
            variable(name, what, at);
            String prior = names.put(name.toLowerCase(Locale.ROOT), what);
            if (prior != null) throw new Problem(at, what + " and " + prior + " have the same name, ignoring case");
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> g = new TreeMap<>();
            for (Global x : globals) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", x.name());
                v.put("type", x.type().name());
                v.put("required", x.required());
                v.put("sensitive", x.sensitive());
                if (!x.description().isEmpty()) v.put("description", x.description());
                v.putAll(global(x, null));
                g.put(Jcs.nfc(x.name()), v);
            }
            Map<String, Object> s = new TreeMap<>();
            for (Secret x : secrets) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", x.name());
                v.put("type", x.type().name());
                v.put("scope", x.scope().name());
                s.put(Jcs.nfc(x.name()), v);
            }
            Map<String, Object> c = new TreeMap<>();
            for (JwtClaim x : claims) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", x.name());
                v.put("claim", x.claim());
                v.put("type", x.type().name());
                v.put("required", x.required());
                c.put(Jcs.nfc(x.name()), v);
            }
            m.put("globals", new ArrayList<>(g.values()));
            m.put("secrets", new ArrayList<>(s.values()));
            m.put("claims", new ArrayList<>(c.values()));
            return m;
        }
    }

    /** The members ServerTool and ClientTool share. */
    private record Tool(String title, String text, String description, String useWhen, EffectSafety effect,
            boolean destructive, io.ololabs.toolgate.sdk.annotations.OpenWorld openWorld, boolean async,
            long maxDurationMs, boolean stateless, int maxConcurrency, String[] routes, String[] tcp,
            io.ololabs.toolgate.sdk.annotations.BusinessKey requiresBusinessKey,
            io.ololabs.toolgate.sdk.annotations.KeyNamespace namespace, String businessKeyArgument,
            boolean cancelIfAbandoned, ErrorDecl[] errors, String[] toolsets) {

        static Tool of(ServerTool s) {
            return new Tool(s.title(), s.text(), s.description(), s.useWhen(), s.effect(), s.destructive(), s.openWorld(),
                    s.async(), s.maxDurationMs(), s.stateless(), s.maxConcurrency(), s.routes(), s.tcp(),
                    s.requiresBusinessKey(), s.businessKeyNamespace(), s.businessKeyArgument(), s.cancelIfAbandoned(),
                    s.errors(), s.toolsets());
        }

        static Tool of(ClientTool c) {
            return new Tool(c.title(), c.text(), c.description(), c.useWhen(), c.effect(), c.destructive(), c.openWorld(),
                    c.async(), c.maxDurationMs(), c.stateless(), c.maxConcurrency(), new String[0], new String[0],
                    c.requiresBusinessKey(), c.businessKeyNamespace(), c.businessKeyArgument(), c.cancelIfAbandoned(),
                    c.errors(), c.toolsets());
        }
    }

    private record Declared<A>(A value, Element at) {}

    /** The constraint members of a global; INT and LONG bounds are not materialised. */
    static Map<String, Object> global(Global g, Element at) {
        if (g.type() == ValueType.INFER) throw new Problem(at, "global " + g.name() + " needs a type");
        if (g.type() == ValueType.ENUM && g.enumValues().length == 0) throw new Problem(at, "global " + g.name() + " of type ENUM needs enumValues");
        if (g.description().length() > 1024) throw new Problem(at, "global " + g.name() + " description is longer than 1024 characters");
        Map<String, Object> constraints = new LinkedHashMap<>();
        if (g.type() == ValueType.ENUM) constraints.put("enum", new ArrayList<>(Arrays.asList(g.enumValues())));
        Schemas.constraints(constraints, g.type(), Spec.of(g), at, false);
        constraints.remove("x-toolgate-sensitive");
        return constraints;
    }

    static void variable(String name, String what, Element at) {
        if (!VARIABLE.matcher(name).matches()) throw new Problem(at, what + " " + name + " does not match " + VARIABLE.pattern());
    }

    static void declarationId(String id, String what, Element at) {
        if (!DECLARATION_ID.matcher(id).matches()) throw new Problem(at, what + " " + id + " does not match " + DECLARATION_ID.pattern());
    }

    static void length(String value, int min, int max, String what, Element at) {
        if (value.length() < min || value.length() > max) throw new Problem(at, what + " must be " + min + "-" + max + " characters");
    }

    static void range(long value, long min, long max, String what, Element at) {
        if (value < min || value > max) throw new Problem(at, what + " must be " + min + ".." + max);
    }

    static void port(int port, Element at) {
        range(port, 1, 65535, "port", at);
    }

    static <T> List<T> unique(List<T> values, String what, Element at) {
        Set<T> seen = new LinkedHashSet<>();
        for (T v : values) if (!seen.add(v)) throw new Problem(at, what + " " + v + " is listed twice");
        return values;
    }
}
