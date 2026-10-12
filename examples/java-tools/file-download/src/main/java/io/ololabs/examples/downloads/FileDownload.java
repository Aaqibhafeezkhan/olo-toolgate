// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.downloads;

import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.RawResponse;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Downloads a file from the administrator's file server into a local directory and returns where it landed.
 *
 * <p>A ToolGate tool never takes a free-form URL: the server is the {@code DownloadHost} setting, chosen per deployment,
 * and the tool may fetch only {@code /<folder>/<file>} from it, with each segment a plain name. That keeps a model from
 * turning the tool into a way to reach internal services. The file is written to {@code DownloadDir} under a validated
 * name, through a temporary file and an atomic move, and never overwrites an existing file unless asked to.
 */
@ToolPackage(id = "examples.downloads", name = "File download", publisher = "ToolGate Examples",
        description = "Downloads files from an approved file server into a local directory and returns their location.")
@Destination(id = "file-server", hostSetting = "DownloadHost")
@HttpRoute(id = "file.get", destination = "file-server", method = HttpMethod.GET,
        path = "/{folder:[A-Za-z0-9][A-Za-z0-9._-]{0,99}}/{file:[A-Za-z0-9][A-Za-z0-9._-]{0,199}}",
        maxResponseBytes = 33_554_432, timeoutMs = 50_000)
@Global(name = "DownloadDir", type = ValueType.STRING, defaultValue = "downloads",
        description = "Directory downloaded files are saved in. Use an absolute path under a Tool Host.")
@Global(name = "PublicBaseUrl", type = ValueType.STRING, defaultValue = "none",
        description = "URL at which DownloadDir is shared, such as https://files.example.com/downloads/, or none.")
public class FileDownload {
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    public record Downloaded(
            @Param(description = "Path that was fetched from the file server") String source,
            @Param(description = "Absolute path of the saved file") String path,
            @Param(description = "file: URL of the saved file") String fileUrl,
            @Param(description = "Shared URL of the saved file, or empty when DownloadDir is not shared") String publicUrl,
            @Param(description = "Size in bytes") long sizeBytes,
            @Param(description = "SHA-256 of the content, hex") String sha256,
            @Param(description = "Content-Type the server sent, or empty") String contentType) {}

    @ServerTool(name = "fetch", title = "Download a file",
            description = "Downloads <folder>/<file> from the approved file server into the download directory and"
                    + " returns its local path, file URL, shared URL, size and SHA-256.",
            effect = EffectSafety.IDEMPOTENT, routes = "file.get", maxDurationMs = 60_000,
            errors = {
                @ErrorDecl(code = "FILE_EXISTS", description = "A file with that name is already saved; pass overwrite."),
                @ErrorDecl(code = "NOT_FOUND", description = "The file server has no such file.")})
    @Example(arguments = "{\"path\":\"../secrets/key.pem\"}", expectedError = "INVALID_ARGUMENT")
    @Example(arguments = "{\"path\":\"reports/missing.csv\"}", expectedError = "NOT_FOUND",
            exchanges = "exchanges/missing.json")
    public Downloaded fetch(
            @Param(description = "Path on the file server, as folder/file, such as reports/2026-10.csv",
                    pattern = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}/[A-Za-z0-9][A-Za-z0-9._-]{0,199}$") String path,
            @Param(description = "Name to save it under; defaults to the file's own name", required = false)
            String saveAs,
            @Param(description = "Replace a saved file with the same name", defaultValue = "false") boolean overwrite,
            ToolContext context) {
        String[] parts = path.split("/", -1);
        // A name starts with a letter or digit, so neither "." nor ".." can get through.
        if (parts.length != 2 || !NAME.matcher(parts[0]).matches() || !NAME.matcher(parts[1]).matches()) {
            throw new ToolInputError("path must be folder/file, each a plain name");
        }
        String name = saveAs == null || saveAs.isBlank() ? parts[1] : saveAs;
        if (!NAME.matcher(name).matches()) throw new ToolInputError("saveAs must be a plain file name");

        Path dir = Path.of(context.global().getStringValue("DownloadDir")).toAbsolutePath().normalize();
        Path target = dir.resolve(name);
        if (Files.exists(target) && !overwrite) {
            throw new ToolBusinessError("FILE_EXISTS", name + " is already saved; pass overwrite to replace it.");
        }

        RawResponse response;
        try {
            response = context.http().get("file-server", "/{folder}/{file}")
                    .pathVar("folder", parts[0]).pathVar("file", parts[1]).sendRaw();
        } catch (io.ololabs.toolgate.sdk.errors.UpstreamError e) {
            if (e.status().orElse(0) == 404) throw new ToolBusinessError("NOT_FOUND", "The file server has no " + path + ".");
            throw e;
        }
        byte[] body = response.body();
        try {
            Files.createDirectories(dir);
            Path temporary = Files.createTempFile(dir, ".download-", ".part");
            try {
                Files.write(temporary, body);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new ToolConfigurationError("DownloadDir cannot be created or written");
        }
        String publicBase = context.global().getStringValue("PublicBaseUrl").trim();
        String publicUrl = publicBase.equals("none") || publicBase.isEmpty() ? ""
                : (publicBase.endsWith("/") ? publicBase : publicBase + "/") + URLEncoder.encode(name, StandardCharsets.UTF_8);
        List<String> type = response.headers().entrySet().stream()
                .filter(h -> h.getKey().equalsIgnoreCase("content-type")).map(Map.Entry::getValue).findFirst().orElse(List.of());
        context.log().info("saved " + body.length + " bytes as " + name);
        return new Downloaded("/" + path, target.toString(), target.toUri().toString(), publicUrl, body.length,
                sha256(body), type.isEmpty() ? "" : type.get(0));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
