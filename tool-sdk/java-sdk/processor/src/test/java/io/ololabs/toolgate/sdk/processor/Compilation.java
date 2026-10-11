// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles test sources with the processor and exposes what it wrote. */
final class Compilation {
    static final String GENERATION = "-A" + DescriptorProcessor.GENERATION_OPTION + "=1";

    private Compilation() {}

    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path out) {
        String errors() {
            return diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null)).collect(Collectors.joining("\n"));
        }

        byte[] resource(String path) {
            try {
                return Files.readAllBytes(out.resolve(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String descriptor(String tool) {
            return new String(resource("META-INF/toolgate/tools/" + tool + ".json"), StandardCharsets.UTF_8);
        }
    }

    /** Compiles a fixture directory under src/test/resources/fixtures. */
    static Result fixture(String name, String... options) {
        Path dir = Path.of("src/test/resources/fixtures", name);
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
                sources.put(dir.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return compile(sources, options);
    }

    /** Compiles sources keyed by path, such as {@code acme/x/Tools.java}. */
    static Result compile(Map<String, String> sources, String... options) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            Path out = Files.createTempDirectory("toolgate-processor");
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> s : sources.entrySet()) units.add(new Source(s.getKey(), s.getValue()));
            List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-classpath", System.getProperty("java.class.path"),
                    "-encoding", "UTF-8", "--release", "17", "-proc:full"));
            args.addAll(List.of(options));
            try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, args, null, units);
                task.setProcessors(List.of(new DescriptorProcessor()));
                boolean ok = task.call();
                return new Result(ok, diagnostics.getDiagnostics(), out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A one-tool package for declaration tests: the class body goes inside {@code public class Tools}. */
    static Result tools(String annotations, String body, String... options) {
        return compile(Map.of("acme/demo/Tools.java", """
                package acme.demo;

                import io.ololabs.toolgate.sdk.annotations.*;
                import io.ololabs.toolgate.sdk.context.*;
                import java.util.*;

                @ToolPackage(id = "acme.demo", name = "Demo", publisher = "Acme")
                @Destination(id = "api", hostSetting = "ApiHost")
                @HttpRoute(id = "items.get", destination = "api", method = HttpMethod.GET, path = "/items/{id:[0-9]+}")
                %s
                public class Tools {
                    public record Out(String value) {}
                %s
                }
                """.formatted(annotations, body)), options);
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String path, String code) {
            super(URI.create("string:///" + path), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
