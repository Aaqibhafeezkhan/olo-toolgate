// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the unchanged ToolGate CLI, {@code toolgate validate <file>}, on bytes the helper wrote itself.
 *
 * <p>The CLI compiles and runs a project's tests when given a directory, so this class only ever passes a single
 * regular file it created, in a private temporary directory, and checks that again just before running it. Nothing in
 * the package is executed: the CLI only reads the archive.
 */
public final class CliValidator {
    private static final Pattern DIGEST = Pattern.compile("^(packageDigest|descriptorSetDigest) (sha256:[0-9a-f]{64})$", Pattern.MULTILINE);
    private static final int MAX_OUTPUT = 64 * 1024;

    /** What the CLI established: both digests, or the reason it refused. */
    public record Result(boolean valid, String packageDigest, String descriptorSetDigest, String code, String message) {
    }

    private final Config config;

    public CliValidator(Config config) {
        this.config = config;
    }

    public Result validate(byte[] bytes) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("tgpkg-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file = dir.resolve("upload.tgpkg");
            Files.write(file, bytes);
            return run(file);
        } catch (IOException e) {
            throw new HelperException(500, "SERVER_ERROR", "cannot stage the package for validation");
        } finally {
            delete(dir);
        }
    }

    /** Validates one regular file. Refuses anything else, so a directory never reaches the CLI. */
    Result run(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("toolgate validate is only given a regular file: " + file);
        }
        ProcessBuilder builder = new ProcessBuilder(config.toolgateCli().toString(), "validate", file.toString())
                .redirectErrorStream(true);
        builder.environment().clear();
        Process process = builder.start();
        process.getOutputStream().close();
        // Read output on its own thread, so a CLI that hangs without printing still hits the timeout.
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()));
        byte[] bytes;
        try {
            if (!process.waitFor(config.validationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new HelperException(422, "TIMEOUT", "validation took longer than " + config.validationTimeout().toSeconds() + " seconds");
            }
            bytes = output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new HelperException(503, "UNAVAILABLE", "interrupted");
        } catch (ExecutionException | TimeoutException e) {
            throw new HelperException(500, "SERVER_ERROR", "cannot read toolgate validate output");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            String message = text.strip().replaceFirst("^toolgate: ", "");
            return new Result(false, null, null, code(message), message);
        }
        String packageDigest = null;
        String setDigest = null;
        Matcher m = DIGEST.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("packageDigest")) {
                packageDigest = m.group(2);
            } else {
                setDigest = m.group(2);
            }
        }
        if (packageDigest == null || setDigest == null) {
            throw new HelperException(500, "SERVER_ERROR", "unexpected output from toolgate validate");
        }
        return new Result(true, packageDigest, setDigest, null, null);
    }

    private static byte[] readBounded(InputStream in) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (out.size() < MAX_OUTPUT) {
                    out.write(buffer, 0, Math.min(n, MAX_OUTPUT - out.size()));
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Maps the CLI's reason to an api.md error code. */
    static String code(String message) {
        String m = message.toLowerCase();
        if (m.contains("entry path") || m.contains("entry name") || m.contains("symlink") || m.contains("duplicate entry")
                || m.contains("collid")) {
            return "ARCHIVE_PATH";
        }
        if (m.contains("checksum") || m.contains("not listed") || m.contains("listed file")) {
            return "CHECKSUM";
        }
        if (m.contains("too many entries") || m.contains("ratio") || m.contains("too large") || m.contains("exceed")) {
            return "SIZE";
        }
        if (m.contains("descriptor")) {
            return "DESCRIPTOR";
        }
        if (m.contains("toolgate-package.json") || m.contains("manifest")) {
            return "MANIFEST";
        }
        if (m.contains("runtime") || m.contains("jar")) {
            return "RUNTIME";
        }
        return "ARCHIVE";
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort; the directory lives on a tmpfs that is discarded with the container.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }
}
