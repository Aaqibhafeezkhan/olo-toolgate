// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.protocol.ProtocolCore;
import io.ololabs.toolgate.protocol.RequestMeta;
import io.ololabs.toolgate.protocol.Tasks;
import io.ololabs.toolgate.sdk.processor.DescriptorProcessor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles tool sources with the descriptor processor and starts runtimes over them. */
final class Fixtures {
    static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {}

    /** Compiles sources keyed by path into a fresh class loader. */
    static ClassLoader compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            Path out = Files.createTempDirectory("toolgate-runtime");
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> s : sources.entrySet()) {
                units.add(new SimpleJavaFileObject(URI.create("string:///" + s.getKey()), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignore) {
                        return s.getValue();
                    }
                });
            }
            List<String> args = List.of("-d", out.toString(), "-classpath", System.getProperty("java.class.path"), "-encoding", "UTF-8",
                    "--release", "17", "-proc:full", "-A" + DescriptorProcessor.GENERATION_OPTION + "=1");
            try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, args, null, units);
                task.setProcessors(List.of(new DescriptorProcessor()));
                if (!task.call()) throw new IllegalStateException("fixture did not compile: " + diagnostics.getDiagnostics());
            }
            return new URLClassLoader(new URL[] {out.toUri().toURL()}, Fixtures.class.getClassLoader());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compiles a directory under src/test/resources/fixtures. */
    static ClassLoader fixture(String name) {
        Path dir = Path.of("src/test/resources/fixtures", name);
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
                sources.put(dir.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return compile(sources);
    }

    /** A started runtime, its diagnostics, and request helpers. */
    static final class Server {
        final StandaloneRuntime runtime;
        final ProtocolCore core;
        final ByteArrayOutputStream log = new ByteArrayOutputStream();
        /** One client connection; requests on it share an owner, as a stdio client's do. */
        final ProtocolCore.Connection connection = new ProtocolCore.Connection("stdio");
        private int next = 1;

        Server(ClassLoader loader, Map<String, String> env) {
            runtime = StandaloneRuntime.start(loader, env, Path.of("build"), new PrintStream(log, true, StandardCharsets.UTF_8));
            core = new ProtocolCore(runtime);
        }

        Server(ClassLoader loader, Map<String, String> env, Tasks tasks) {
            runtime = StandaloneRuntime.start(loader, env, Path.of("build"), new PrintStream(log, true, StandardCharsets.UTF_8), tasks);
            core = new ProtocolCore(runtime);
        }

        String diagnostics() {
            return log.toString(StandardCharsets.UTF_8);
        }

        JsonNode request(String method, ObjectNode params) {
            return request(method, params, connection);
        }

        JsonNode request(String method, ObjectNode params, ProtocolCore.Connection on) {
            ObjectNode message = JSON.createObjectNode();
            message.put("jsonrpc", "2.0");
            synchronized (this) {
                message.put("id", next++);
            }
            message.put("method", method);
            message.set("params", params);
            return core.handle(message, on);
        }

        static ObjectNode params(String extensions) {
            ObjectNode params = JSON.createObjectNode();
            ObjectNode meta = params.putObject("_meta");
            meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
            ObjectNode caps = meta.putObject("io.modelcontextprotocol/clientCapabilities");
            if (extensions != null) caps.putObject("extensions").putObject(extensions);
            return params;
        }

        JsonNode call(String tool, String arguments) {
            return call(tool, arguments, null);
        }

        /** A call from a client that declares the Tasks extension on this request. */
        JsonNode callTask(String tool, String arguments) {
            return call(tool, arguments, RequestMeta.TASKS);
        }

        JsonNode call(String tool, String arguments, String extension) {
            ObjectNode params = params(extension);
            params.put("name", tool);
            try {
                params.set("arguments", JSON.readTree(arguments));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return request("tools/call", params);
        }

        /** The structured error of a failed call. */
        JsonNode error(String tool, String arguments) {
            JsonNode result = call(tool, arguments).path("result");
            if (!result.path("isError").asBoolean()) throw new AssertionError("expected a tool error: " + result);
            return result.path("structuredContent").path("error");
        }
    }
}
