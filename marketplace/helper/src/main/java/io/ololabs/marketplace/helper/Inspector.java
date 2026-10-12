// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code POST /inspect} and {@code POST /sign} (api.md §3). Inspect never signs; sign re-reads and re-validates the
 * bytes, then checks the digest, the publisher prefix and the reserved prefixes before signing.
 */
public final class Inspector {
    static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{1,30}$");
    private static final int MAX_TITLE = 100;
    private static final int MAX_SUMMARY = 300;
    private static final int MAX_DESCRIPTION = 20_000;
    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG = 32;

    private final Config config;
    private final CliValidator validator;
    private final Signer signer;

    public Inspector(Config config, CliValidator validator, Signer signer) {
        this.config = config;
        this.validator = validator;
        this.signer = signer;
    }

    /** What inspection established: the public result plus the parsed manifest for internal use. */
    record Inspection(Map<String, Object> result, Map<String, Object> manifest) {
        boolean valid() {
            return Boolean.TRUE.equals(result.get("valid"));
        }
    }

    public Map<String, Object> inspect(byte[] bytes, byte[] previousBytes, String previousVersion) {
        Map<String, Object> previousManifest = null;
        if (previousBytes != null) {
            Inspection previous = check(previousBytes);
            if (!previous.valid()) {
                throw new HelperException(422, "INVALID_REQUEST", "previousVersion does not hold a valid package");
            }
            previousManifest = previous.manifest();
            if (previousVersion != null && !previousVersion.equals(previousManifest.get("version"))) {
                throw new HelperException(422, "INVALID_REQUEST", "previousVersion.version does not match its package");
            }
        }
        Inspection inspection = check(bytes);
        Map<String, Object> result = inspection.result();
        if (inspection.valid()) {
            compare(result, previousManifest, inspection.manifest());
        }
        return result;
    }

    /** Validates the bytes and extracts everything the listing needs. Errors make {@code valid} false. */
    Inspection check(byte[] bytes) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> errors = new ArrayList<>();
        result.put("valid", false);
        result.put("errors", errors);
        result.put("archiveSha256", sha256(bytes));
        if (bytes.length > config.maxPackageBytes()) {
            errors.add(error("SIZE", "package is larger than " + config.maxPackageBytes() / (1024 * 1024) + " MiB"));
            return new Inspection(result, null);
        }
        Map<String, byte[]> entries;
        try {
            CliValidator.Result cli = validator.validate(bytes);
            if (!cli.valid()) {
                errors.add(error(cli.code(), cli.message()));
                return new Inspection(result, null);
            }
            result.put("packageDigest", cli.packageDigest());
            result.put("descriptorSetDigest", cli.descriptorSetDigest());
            entries = PackageReader.read(bytes, config.maxExpandedBytes());
        } catch (HelperException e) {
            if (e.status() != 422) {
                throw e;
            }
            errors.add(error(e.code(), e.getMessage()));
            return new Inspection(result, null);
        }
        Map<String, Object> manifest;
        try {
            manifest = Json.obj(Json.parse(entries.get(PackageReader.MANIFEST)));
        } catch (IllegalArgumentException e) {
            errors.add(error("MANIFEST", "toolgate-package.json: " + e.getMessage()));
            return new Inspection(result, null);
        }
        String id = Json.str(manifest.get("id"));
        String version = Json.str(manifest.get("version"));
        result.put("packageId", id);
        result.put("version", version);
        result.put("runtime", "java");
        if (id == null || id.indexOf('.') < 0) {
            errors.add(error("MANIFEST", "package id needs a publisher prefix, such as acme.notify"));
        }
        checkRuntime(manifest, errors);
        result.put("listing", listing(manifest, entries, result));
        result.put("tools", tools(manifest));
        result.put("requiredSettings", settings(manifest));
        result.put("compatibility", compatibility(manifest, errors));
        result.put("apiChange", "none");
        result.put("requiredBump", null);
        result.put("valid", errors.isEmpty());
        return new Inspection(result, manifest);
    }

    /** Only Java server tools run in ToolGate today (design §6, "Supported runtime only"). */
    private static void checkRuntime(Map<String, Object> manifest, List<Object> errors) {
        if (!"SDK".equals(manifest.get("kind"))) {
            errors.add(error("RUNTIME", "only SDK packages are accepted, not " + manifest.get("kind")));
        }
        List<Object> runtimes = Json.arr(manifest.get("runtimes"));
        if (runtimes.isEmpty()) {
            errors.add(error("RUNTIME", "the package declares no runtime"));
        }
        for (Object runtime : runtimes) {
            if (!"java".equals(Json.obj(runtime).get("language"))) {
                errors.add(error("RUNTIME", "only Java runtimes are accepted, not " + Json.obj(runtime).get("language")));
            }
        }
        for (Object tool : Json.arr(manifest.get("tools"))) {
            Map<String, Object> descriptor = Json.obj(Json.obj(tool).get("descriptor"));
            if (!"SERVER".equals(descriptor.get("kind"))) {
                errors.add(error("RUNTIME", descriptor.get("toolId") + ": only server tools are accepted"));
            }
        }
    }

    /** Design §6.2 A: the helper reports these and rejects only a missing or malformed field. */
    private static Map<String, Object> compatibility(Map<String, Object> manifest, List<Object> errors) {
        Map<String, Object> compatibility = Json.obj(manifest.get("compatibility"));
        Map<String, Object> sdk = Json.obj(manifest.get("sdk"));
        Map<String, Object> out = new LinkedHashMap<>();
        String contracts = Json.str(compatibility.get("contractsVersion"));
        out.put("contractsVersion", semverOrError(contracts, "compatibility.contractsVersion", true, errors));
        out.put("minimumToolHostVersion", semverOrError(Json.str(compatibility.get("minimumToolHostVersion")),
                "compatibility.minimumToolHostVersion", false, errors));
        out.put("minimumClientVersion", semverOrError(Json.str(compatibility.get("minimumClientVersion")),
                "compatibility.minimumClientVersion", false, errors));
        Map<String, Object> protocol = Json.obj(sdk.get("protocol"));
        if (!(protocol.get("min") instanceof Long min) || !(protocol.get("max") instanceof Long max) || min > max) {
            errors.add(error("MANIFEST", "sdk.protocol must be a range with min <= max"));
            out.put("protocol", null);
        } else {
            out.put("protocol", Map.of("min", min, "max", max));
        }
        if (!(sdk.get("descriptorGeneration") instanceof Long generation)) {
            errors.add(error("MANIFEST", "sdk.descriptorGeneration is missing"));
            out.put("descriptorGeneration", null);
        } else {
            out.put("descriptorGeneration", generation);
        }
        List<Object> runtimes = new ArrayList<>();
        for (Object runtime : Json.arr(manifest.get("runtimes"))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("language", Json.obj(runtime).get("language"));
            r.put("version", Json.obj(runtime).get("version"));
            runtimes.add(r);
        }
        out.put("runtimes", runtimes);
        return out;
    }

    private static String semverOrError(String value, String field, boolean required, List<Object> errors) {
        if (value == null) {
            if (required) {
                errors.add(error("MANIFEST", field + " is missing"));
            }
            return null;
        }
        try {
            SemVer.parse(value);
            return value;
        } catch (HelperException e) {
            errors.add(error("MANIFEST", field + " is not a semantic version"));
            return null;
        }
    }

    /** Design §6.2 B: API diff and SemVer check against the previous available version. */
    private static void compare(Map<String, Object> result, Map<String, Object> previous, Map<String, Object> manifest) {
        if (previous == null) {
            return;
        }
        List<Object> errors = Json.arr(result.get("errors"));
        if (!manifest.get("id").equals(previous.get("id"))) {
            errors.add(error("SEMVER", "previousVersion is a different package: " + previous.get("id")));
            result.put("valid", false);
            return;
        }
        ApiDiff.Result diff = ApiDiff.between(previous, manifest);
        SemVer before = SemVer.parse(Json.str(previous.get("version")));
        SemVer now = SemVer.parse(Json.str(manifest.get("version")));
        int required = SemVer.requiredBump(before, diff.apiChange());
        result.put("apiChange", diff.apiChange());
        result.put("requiredBump", SemVer.bumpName(required));
        result.put("apiChanges", new ArrayList<Object>(diff.changes()));
        int actual = now.bumpFrom(before);
        if (actual == 0) {
            errors.add(error("SEMVER", "version " + now + " must be greater than the previous version " + previous.get("version")));
        } else if (actual < required) {
            errors.add(error("SEMVER", "the API change needs at least a " + SemVer.bumpName(required) + " version bump after "
                    + previous.get("version") + ": " + String.join("; ", diff.changes())));
        }
        result.put("valid", errors.isEmpty());
    }

    private static List<Object> tools(Map<String, Object> manifest) {
        List<Object> tools = new ArrayList<>();
        for (Object tool : Json.arr(manifest.get("tools"))) {
            Map<String, Object> d = Json.obj(Json.obj(tool).get("descriptor"));
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", d.get("mcpName"));
            t.put("title", d.get("title"));
            t.put("description", d.get("description"));
            t.put("effect", Json.at(d, "effect", "safety"));
            tools.add(t);
        }
        return tools;
    }

    /** Typed settings from each descriptor's variables and destinations, once per kind and name. */
    private static List<Object> settings(Map<String, Object> manifest) {
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
        for (Object tool : Json.arr(manifest.get("tools"))) {
            Map<String, Object> d = Json.obj(Json.obj(tool).get("descriptor"));
            for (Object destination : Json.arr(d.get("destinations"))) {
                add(settings, "destination", Json.str(Json.obj(destination).get("hostSetting")), null, null,
                        "Host of destination " + Json.obj(destination).get("id"));
            }
            for (Object tcp : Json.arr(d.get("tcp"))) {
                add(settings, "destination", Json.str(Json.obj(tcp).get("hostSetting")), null, null,
                        "Host of TCP destination " + Json.obj(tcp).get("id"));
            }
            Map<String, Object> variables = Json.obj(d.get("variables"));
            for (Object secret : Json.arr(variables.get("secrets"))) {
                Map<String, Object> s = Json.obj(secret);
                add(settings, "secret", Json.str(s.get("name")), Json.str(s.get("type")), null, null);
            }
            for (Object global : Json.arr(variables.get("globals"))) {
                Map<String, Object> g = Json.obj(global);
                if (Boolean.TRUE.equals(g.get("sensitive"))) {
                    add(settings, "global", Json.str(g.get("name")), Json.str(g.get("type")), null, Json.str(g.get("description")));
                } else {
                    add(settings, "global", Json.str(g.get("name")), Json.str(g.get("type")), g.get("default"), Json.str(g.get("description")));
                }
            }
            for (Object claim : Json.arr(variables.get("claims"))) {
                Map<String, Object> c = Json.obj(claim);
                add(settings, "claim", Json.str(c.get("name")), Json.str(c.get("type")), null, "Identity claim " + c.get("claim"));
            }
        }
        return new ArrayList<>(settings.values());
    }

    private static void add(Map<String, Map<String, Object>> settings, String kind, String name, String type,
                            Object fallback, String description) {
        if (name == null || settings.containsKey(kind + ":" + name)) {
            return;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", kind);
        s.put("name", name);
        s.put("type", type);
        s.put("default", fallback == null ? null : fallback instanceof String str ? str : Json.write(fallback));
        s.put("description", description);
        settings.put(kind + ":" + name, s);
    }

    /**
     * Listing metadata: {@code marketplace/listing.json} when present and consistent with the manifest, otherwise
     * derived from the manifest and descriptors (design §5.2 step 5). All of it is untrusted text, bounded here and
     * sanitized again by Drupal.
     */
    private static Map<String, Object> listing(Map<String, Object> manifest, Map<String, byte[]> entries, Map<String, Object> result) {
        List<Object> tools = Json.arr(manifest.get("tools"));
        String id = Json.str(manifest.get("id"));
        String firstDescription = tools.isEmpty() ? null : Json.str(Json.at(Json.obj(tools.get(0)), "descriptor", "description"));
        Map<String, Object> listing = new LinkedHashMap<>();
        listing.put("title", bound(tools.size() == 1 ? Json.str(Json.at(Json.obj(tools.get(0)), "descriptor", "title")) : humanize(id), MAX_TITLE));
        listing.put("summary", bound(firstSentence(firstDescription), MAX_SUMMARY));
        listing.put("description", bound(entries.containsKey(PackageReader.README)
                ? new String(entries.get(PackageReader.README), StandardCharsets.UTF_8) : firstDescription, MAX_DESCRIPTION));
        listing.put("license", null);
        listing.put("categories", new ArrayList<>());
        Set<String> tags = new LinkedHashSet<>();
        for (Object tool : tools) {
            String name = Json.str(Json.at(Json.obj(tool), "descriptor", "mcpName"));
            if (name != null && name.contains(".")) {
                tags.add(name.substring(0, name.indexOf('.')).toLowerCase());
            }
        }
        listing.put("tags", new ArrayList<>(boundTags(tags)));
        byte[] listingFile = entries.get(PackageReader.LISTING);
        if (listingFile == null) {
            return listing;
        }
        try {
            Map<String, Object> file = Json.obj(Json.parse(listingFile));
            Object fileId = file.get("packageId");
            Object fileVersion = file.get("version");
            if ((fileId != null && !fileId.equals(id)) || (fileVersion != null && !fileVersion.equals(manifest.get("version")))) {
                warn(result, "marketplace/listing.json names a different package or version; using the manifest instead");
                return listing;
            }
            putText(listing, "title", file.get("title"), MAX_TITLE);
            putText(listing, "summary", file.get("summary"), MAX_SUMMARY);
            putText(listing, "description", file.get("description"), MAX_DESCRIPTION);
            putText(listing, "license", file.get("license"), 128);
            if (file.get("categories") instanceof List<?> categories) {
                listing.put("categories", new ArrayList<>(boundTags(strings(categories))));
            }
            if (file.get("tags") instanceof List<?> fileTags) {
                listing.put("tags", new ArrayList<>(boundTags(strings(fileTags))));
            }
        } catch (IllegalArgumentException e) {
            warn(result, "marketplace/listing.json is not valid JSON; using the manifest instead");
        }
        return listing;
    }

    private static void warn(Map<String, Object> result, String warning) {
        @SuppressWarnings("unchecked")
        List<Object> warnings = (List<Object>) result.computeIfAbsent("warnings", k -> new ArrayList<>());
        warnings.add(warning);
    }

    private static Set<String> strings(List<?> values) {
        Set<String> out = new LinkedHashSet<>();
        for (Object v : values) {
            if (v instanceof String s && !s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    private static Set<String> boundTags(Set<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        for (String tag : tags) {
            if (out.size() < MAX_TAGS && tag.length() <= MAX_TAG) {
                out.add(tag);
            }
        }
        return out;
    }

    private static void putText(Map<String, Object> listing, String key, Object value, int max) {
        if (value instanceof String s && !s.isBlank()) {
            listing.put(key, bound(s.strip(), max));
        }
    }

    private static String bound(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String firstSentence(String text) {
        if (text == null) {
            return null;
        }
        int end = text.indexOf(". ");
        return end > 0 ? text.substring(0, end + 1) : text;
    }

    /** {@code examples.notify} becomes "Notify". */
    private static String humanize(String id) {
        if (id == null) {
            return null;
        }
        String last = id.substring(id.lastIndexOf('.') + 1).replace('-', ' ');
        return last.isEmpty() ? id : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /** {@code POST /sign}: re-validate, match digest and prefix, refuse reserved prefixes, then sign (design §6). */
    public Map<String, Object> sign(byte[] bytes, String packageDigest, String authorizedPublisherSlug) {
        if (authorizedPublisherSlug == null || !SLUG.matcher(authorizedPublisherSlug).matches()) {
            throw new HelperException(400, "INVALID_REQUEST", "authorizedPublisherSlug is missing or malformed");
        }
        if (packageDigest == null || !packageDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new HelperException(400, "INVALID_REQUEST", "packageDigest is missing or malformed");
        }
        Inspection inspection = check(bytes);
        Map<String, Object> result = inspection.result();
        if (!inspection.valid()) {
            throw new HelperException(403, "INVALID_PACKAGE", "the package no longer validates: " + Json.write(result.get("errors")));
        }
        if (!packageDigest.equals(result.get("packageDigest"))) {
            throw new HelperException(403, "DIGEST_MISMATCH", "the package digest differs from the one inspected");
        }
        String id = (String) result.get("packageId");
        String prefix = id.substring(0, id.indexOf('.'));
        if (!prefix.equals(authorizedPublisherSlug)) {
            throw new HelperException(403, "PREFIX_MISMATCH", "package id prefix '" + prefix + "' is not the publisher slug '"
                    + authorizedPublisherSlug + "'");
        }
        if (config.reservedPrefixes().contains(prefix) && !config.systemPublishers().contains(authorizedPublisherSlug)) {
            throw new HelperException(403, "RESERVED_PREFIX", "package id prefix '" + prefix + "' is reserved");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schema", Signer.SCHEMA);
        fields.put("marketplace", config.marketplaceId());
        fields.put("packageId", id);
        fields.put("version", result.get("version"));
        fields.put("packageDigest", result.get("packageDigest"));
        fields.put("descriptorSetDigest", result.get("descriptorSetDigest"));
        fields.put("archiveSha256", result.get("archiveSha256"));
        fields.put("publisherSlug", authorizedPublisherSlug);
        fields.put("signedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        return signer.release(fields);
    }

    static Map<String, Object> error(String code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        return e;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
