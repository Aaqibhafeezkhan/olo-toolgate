// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** The OLO Orgs Marketplace as ToolGate Admin sees it (marketplace design §8, api.md §1, §2, §4).
 *
 *  Control reads the public catalog from the one origin the environment configures and marks each version compatible
 *  or not with this ToolGate. Import downloads a version's detail, signed release record and bytes through Control and
 *  runs every check of design §8.3; only then does it hand the bytes to the existing upload, under Super Admin, which
 *  validates them again and audits where they came from. Importing never deploys, enables or grants anything. */
public final class Marketplace {
    public static final String RELEASE_SCHEMA = "toolgate.marketplace.release/1", PACKAGE_MEDIA_TYPE = "application/vnd.ololabs.toolgate.package+zip";
    static final String API = "/api/toolgate/v1";
    static final int MAX_JSON_BYTES = 2 * 1024 * 1024, MAX_RELEASE_BYTES = 64 * 1024, MAX_MEDIA_BYTES = 1024 * 1024;
    private static final Pattern MARKETPLACE_ID = Pattern.compile("^[a-z0-9][a-z0-9.-]{0,63}$"), KEY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern PACKAGE_ID = Pattern.compile("^[a-z][a-z0-9-]{1,30}(\\.[a-z][a-z0-9-]{1,30}){1,3}$");
    private static final Pattern VERSION = Pattern.compile("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$");
    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{1,30}$"), MEDIA_ID = Pattern.compile("^m_[0-9a-f]{32}$");
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$"), DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern TEXT = Pattern.compile("^[\\p{L}\\p{N} ._:/@+-]{0,200}$");
    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the raw 32-byte key follows. */
    private static final byte[] ED25519_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(Marketplace.class);

    /** One pinned Marketplace signing key. */
    public record Key(String keyId, byte[] raw, String fingerprint) {}

    /** The environment-owned settings (api.md §4). A configuration import can't change them. */
    public record Settings(boolean enabled, String marketplaceId, String origin, Map<String, Key> keys) {
        public static Settings disabled() { return new Settings(false, null, null, Map.of()); }

        /** Parses TOOLGATE_CONTROL_MARKETPLACE_ID, _BASE_URL and _KEYS (comma-separated {@code keyId:base64}). */
        public static Settings parse(String marketplaceId, String baseUrl, String keys) {
            if (marketplaceId == null || !MARKETPLACE_ID.matcher(marketplaceId).matches()) throw new IllegalArgumentException("TOOLGATE_CONTROL_MARKETPLACE_ID is required");
            String origin;
            try { origin = EndpointService.origin(baseUrl == null ? "" : baseUrl.replaceFirst("/+$", "")); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("TOOLGATE_CONTROL_MARKETPLACE_BASE_URL must be an HTTPS origin"); }
            var pinned = new LinkedHashMap<String, Key>();
            for (var entry : (keys == null ? "" : keys).split(",")) {
                if (entry.isBlank()) continue;
                int colon = entry.indexOf(':');
                String keyId = colon < 0 ? "" : entry.substring(0, colon).strip();
                byte[] raw;
                try { raw = Base64.getDecoder().decode(entry.substring(colon + 1).strip()); } catch (IllegalArgumentException e) { raw = new byte[0]; }
                if (!KEY_ID.matcher(keyId).matches() || raw.length != 32 || pinned.containsKey(keyId))
                    throw new IllegalArgumentException("TOOLGATE_CONTROL_MARKETPLACE_KEYS must list keyId:base64PublicKey pairs of 32-byte Ed25519 keys");
                pinned.put(keyId, new Key(keyId, raw, fingerprint(raw)));
            }
            if (pinned.isEmpty()) throw new IllegalArgumentException("TOOLGATE_CONTROL_MARKETPLACE_KEYS needs at least one key");
            return new Settings(true, marketplaceId, origin, Map.copyOf(pinned));
        }
    }

    private final Settings settings; private final MarketplaceSource source; private final ServerPackages packages;
    private final Store store; private final Codec codec; private final Clock clock;

    public Marketplace(Settings settings, MarketplaceSource source, ServerPackages packages, Store store, Codec codec, Clock clock) {
        this.settings = settings; this.source = source; this.packages = packages; this.store = store; this.codec = codec; this.clock = clock;
    }

    public Settings settings() { return settings; }

    /** {@code sha256:<hex>} over the raw public key, as the Marketplace's key file and About page show it. */
    public static String fingerprint(byte[] raw) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    // ---- Reading the catalog -------------------------------------------------------------------------------------

    /** Whether the Marketplace is on, and which keys are pinned. Administrators with read access. */
    public Store.Reply status(DirectoryService.Actor actor) {
        reader(actor);
        var status = JSON.objectNode().put("enabled", settings.enabled());
        if (settings.enabled()) {
            status.put("marketplaceId", settings.marketplaceId()).put("baseUrl", settings.origin());
            var keys = status.putArray("keys");
            settings.keys().values().forEach(k -> keys.addObject().put("keyId", k.keyId()).put("fingerprint", k.fingerprint()));
        } else status.putArray("keys");
        return reply(status);
    }

    public Store.Reply catalog(DirectoryService.Actor actor, Integer page, Integer limit, String q, String category, String tag, String publisher) {
        reader(actor); enabled();
        var query = new StringBuilder("?page=").append(page == null ? 1 : bounded(page, 1, 10000)).append("&limit=").append(limit == null ? 20 : bounded(limit, 1, 100));
        param(query, "q", q); param(query, "category", category); param(query, "tag", tag);
        if (publisher != null && !publisher.isEmpty()) { slug(publisher); param(query, "publisher", publisher); }
        var catalog = json(API + "/catalog" + query, MAX_JSON_BYTES);
        for (var item : catalog.path("items")) annotate(item);
        return reply(catalog);
    }

    /** Every version of one package, each marked compatible or not, and the newest compatible one to install. */
    public Store.Reply versions(DirectoryService.Actor actor, String packageId) {
        reader(actor); enabled(); packageId(packageId);
        var versions = json(API + "/tools/" + packageId, MAX_JSON_BYTES);
        String install = null;
        for (var version : versions.path("versions")) {
            boolean compatible = annotate(version);
            if (install == null && compatible && !version.path("delisted").asBoolean(true)) install = version.path("version").asText(null);
        }
        if (versions.isObject()) ((ObjectNode) versions).put("installVersion", install);
        return reply(versions);
    }

    public Store.Reply detail(DirectoryService.Actor actor, String packageId, String version) {
        reader(actor); enabled(); packageId(packageId); version(version);
        var detail = json(API + "/tools/" + packageId + "/" + version, MAX_JSON_BYTES);
        annotate(detail);
        return reply(detail);
    }

    public Store.Reply publisher(DirectoryService.Actor actor, String slug) {
        reader(actor); enabled(); slug(slug);
        var publisher = json(API + "/publishers/" + slug, MAX_JSON_BYTES);
        for (var item : publisher.path("packages")) annotate(item);
        return reply(publisher);
    }

    /** An avatar, icon or screenshot, through Control's origin, so the console keeps {@code img-src 'self'}. */
    public Response media(String mediaId) {
        enabled();
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) throw notFound();
        var response = source.get(API + "/media/" + mediaId, "image/png, image/jpeg", MAX_MEDIA_BYTES);
        if (response.status() == 404) throw notFound();
        if (response.status() != 200) throw unavailable();
        byte[] body = response.body();
        boolean png = response.mediaType().equals("image/png") && body.length > 8 && (body[0] & 0xff) == 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G';
        boolean jpeg = response.mediaType().equals("image/jpeg") && body.length > 3 && (body[0] & 0xff) == 0xff && (body[1] & 0xff) == 0xd8 && (body[2] & 0xff) == 0xff;
        if (!png && !jpeg) throw unavailable();
        return new Response(response.mediaType(), body);
    }

    /** Media bytes and their type. */
    public record Response(String mediaType, byte[] body) {}

    /** Marks a catalog item or version with {@code control: {compatible, reasons}}; returns whether it is compatible. */
    static boolean annotate(JsonNode item) {
        if (!item.isObject()) return false;
        var compatibility = item.path("compatibility");
        var manifest = JSON.objectNode();
        var declared = manifest.putObject("compatibility");
        if (compatibility.has("contractsVersion")) declared.set("contractsVersion", compatibility.get("contractsVersion"));
        if (compatibility.hasNonNull("minimumToolHostVersion")) declared.set("minimumToolHostVersion", compatibility.get("minimumToolHostVersion"));
        var sdk = manifest.putObject("sdk");
        if (compatibility.has("protocol")) sdk.set("protocol", compatibility.get("protocol"));
        if (compatibility.has("descriptorGeneration")) sdk.set("descriptorGeneration", compatibility.get("descriptorGeneration"));
        manifest.set("runtimes", compatibility.path("runtimes").isArray() ? compatibility.get("runtimes") : JSON.arrayNode());
        var problems = PackageCompatibility.problems(manifest);
        var control = ((ObjectNode) item).putObject("control").put("compatible", problems.isEmpty());
        var reasons = control.putArray("reasons"); problems.forEach(reasons::add);
        return problems.isEmpty();
    }

    // ---- Import (design §8.3) ------------------------------------------------------------------------------------

    /** Imports one version after every check of design §8.3, then runs the existing upload. Super Admin only. */
    public Store.Reply install(DirectoryService.Actor actor, String body, String requestId) {
        var request = (JsonNode) codec.value(body);
        if (!request.isObject() || request.size() != 2 || !request.path("packageId").isTextual() || !request.path("version").isTextual()) throw Failure.validation();
        String packageId = request.get("packageId").asText(), version = request.get("version").asText();
        packageId(packageId); version(version);
        enabled();
        // The same check the upload makes, first, so nobody else makes Control download anything.
        store.transaction(actor.tenant(), false, tx -> { actor.requireAdmin(); if (!new ManagementAccess(codec).superAdministrator(tx.load(), actor.userId())) throw ManagementAccess.denied(); return null; });

        String base = API + "/tools/" + packageId + "/" + version;
        var detail = json(base, MAX_JSON_BYTES);
        if (!packageId.equals(detail.path("packageId").asText()) || !version.equals(detail.path("version").asText())) throw rejected("5", "the detail record is for another package or version");
        // 1. Not delisted.
        if (!detail.path("delisted").isBoolean() || detail.get("delisted").asBoolean()) throw rejected("1", "the version is delisted");
        for (var field : new String[]{"downloadUrl", "releaseUrl"}) {
            var url = detail.path(field);
            String expected = settings.origin() + base + (field.equals("downloadUrl") ? "/package" : "/release");
            if (!url.isMissingNode() && !url.isNull() && !expected.equals(url.asText())) throw rejected("origin", field + " is not on the configured Marketplace origin");
        }
        var release = json(base + "/release", MAX_RELEASE_BYTES);
        if (!release.isObject()) throw rejected("2", "the release record is not an object");
        // 2. A schema Control knows.
        if (!RELEASE_SCHEMA.equals(release.path("schema").asText())) throw rejected("2", "unknown release schema");
        // 3. A key pinned for this Marketplace, and a valid signature over the canonical record.
        String keyId = text(release, "keyId");
        var key = settings.keys().get(keyId);
        if (key == null) throw rejected("3", "the release is signed with a key that is not pinned");
        if (!verify(key.raw(), release)) throw rejected("3", "the signature does not verify");
        // 4. Signed for this Marketplace.
        if (!settings.marketplaceId().equals(text(release, "marketplace"))) throw rejected("4", "the release is for another marketplace");
        // 5. The requested package and version, as the detail record says.
        if (!packageId.equals(text(release, "packageId")) || !version.equals(text(release, "version"))) throw rejected("5", "the release is for another package or version");
        // 6. The publisher of the detail record (and, below, the id prefix of the validated manifest).
        String publisherSlug = text(release, "publisherSlug");
        if (!SLUG.matcher(publisherSlug).matches() || !publisherSlug.equals(detail.path("publisher").path("slug").asText())) throw rejected("6", "the signed publisher differs from the detail record");
        String archiveSha256 = text(release, "archiveSha256"), packageDigest = text(release, "packageDigest"), setDigest = text(release, "descriptorSetDigest");
        if (!HEX64.matcher(archiveSha256).matches() || !DIGEST.matcher(packageDigest).matches() || !DIGEST.matcher(setDigest).matches()) throw rejected("7", "the release digests are malformed");

        var download = source.get(base + "/package", PACKAGE_MEDIA_TYPE, PackageArchive.MAX_PACKAGE_BYTES);
        if (download.status() == 410) throw rejected("1", "the version is delisted");
        expect(download, PACKAGE_MEDIA_TYPE);
        byte[] bytes = download.body();
        // 7. The bytes are the ones that were signed.
        if (!archiveSha256.equals(sha256(bytes))) throw rejected("7", "the package bytes differ from the signed archiveSha256");
        // 8. Control's own validation, and the identity and digests it computes, equal the signed values.
        PackageArchive.Verified verified;
        try { verified = PackageArchive.validate(bytes, document -> (JsonNode) codec.value(document)); }
        catch (Failure failure) { if (failure.status() == 413) throw failure; throw rejected("8", "the package does not validate"); }
        var manifest = verified.manifest();
        if (!packageId.equals(manifest.path("id").asText()) || !version.equals(manifest.path("version").asText())) throw rejected("8", "the manifest id or version differs from the signed values");
        if (!packageDigest.equals(verified.packageDigest()) || !setDigest.equals(verified.descriptorSetDigest())) throw rejected("8", "the computed digests differ from the signed values");
        if (!publisherSlug.equals(packageId.substring(0, packageId.indexOf('.')))) throw rejected("6", "the signed publisher is not the package id prefix");

        var origin = new ServerPackages.Origin(settings.marketplaceId(), packageId, version, publisherSlug, keyId);
        if (origin.target().length() > 256) throw Failure.validation();
        LOG.infof("marketplace_import marketplace=%s package=%s version=%s publisher=%s key=%s", settings.marketplaceId(), packageId, version, publisherSlug, keyId);
        return packages.upload(actor, bytes, requestId, origin);
    }

    /** Ed25519 over the RFC 8785 form of the record without its {@code signature}. */
    static boolean verify(byte[] raw, JsonNode release) {
        String signature = release.path("signature").asText("");
        try {
            byte[] spki = new byte[ED25519_PREFIX.length + 32];
            System.arraycopy(ED25519_PREFIX, 0, spki, 0, ED25519_PREFIX.length);
            System.arraycopy(raw, 0, spki, ED25519_PREFIX.length, 32);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            var unsigned = ((ObjectNode) release).deepCopy(); unsigned.remove("signature");
            var verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key); verifier.update(Canonical.jcs(unsigned));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) { return false; }
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private JsonNode json(String path, int maxBytes) {
        var response = source.get(path, "application/json", maxBytes);
        if (response.status() == 404) throw notFound();
        if (response.status() == 410) throw rejected("1", "the version is delisted");
        expect(response, "application/json");
        try { return (JsonNode) codec.value(new String(response.body(), StandardCharsets.UTF_8)); }
        catch (Failure failure) { throw unavailable(); }
    }

    private static void expect(MarketplaceSource.Response response, String mediaType) {
        if (response.status() >= 300 && response.status() < 400) throw rejected("origin", "the Marketplace answered with a redirect");
        if (response.status() != 200) throw unavailable();
        if (!response.mediaType().equals(mediaType)) throw unavailable();
    }

    private void reader(DirectoryService.Actor actor) {
        store.transaction(actor.tenant(), false, tx -> { actor.requireAdmin(); new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "package.read", clock.millis()); return null; });
    }
    private void enabled() { if (!settings.enabled()) throw new Failure(ErrorCode.NOT_FOUND, 404, "Marketplace is not enabled"); }
    private Store.Reply reply(Object value) { return new Store.Reply(200, codec.json(value), 0); }
    private static String text(JsonNode node, String field) { var value = node.path(field); if (!value.isTextual()) throw rejected("2", field + " is missing from the release record"); return value.asText(); }
    private static int bounded(int value, int min, int max) { if (value < min || value > max) throw Failure.validation(); return value; }
    private static void param(StringBuilder query, String name, String value) {
        if (value == null || value.isEmpty()) return;
        if (!TEXT.matcher(value).matches()) throw Failure.validation();
        query.append('&').append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
    private static void packageId(String value) { if (value == null || !PACKAGE_ID.matcher(value).matches()) throw Failure.validation(); }
    private static void version(String value) { if (value == null || value.length() > 64 || !VERSION.matcher(value).matches()) throw Failure.validation(); }
    private static void slug(String value) { if (value == null || !SLUG.matcher(value).matches()) throw Failure.validation(); }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static Failure notFound() { return new Failure(ErrorCode.NOT_FOUND, 404, "Not found in the Marketplace"); }
    private static Failure unavailable() { return new Failure(ErrorCode.DEPENDENCY_UNAVAILABLE, 503, "Marketplace unavailable"); }

    /** An import that failed a check of design §8.3. The reason is logged, never sent (errors carry no detail). */
    static Failure rejected(String step, String reason) {
        LOG.infof("marketplace_import_rejected step=%s reason=%s", step, reason);
        return new Failure(ErrorCode.VALIDATION, 422, "Marketplace import rejected at step " + step + ": " + reason);
    }
}
