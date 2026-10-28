// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.v2.DeploymentProfile;
import io.ololabs.toolgate.contracts.v2.ProfilePackage;
import io.ololabs.toolgate.contracts.v2.ProfilePackageSource;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** The one application service that provisions packages from a deployment profile (docs/contracts/platform-requirements.md).
 *
 *  The whole profile is validated before anything changes. Each pinned package is then imported through the ordinary
 *  verifier — the sealed core artifact from the image lock, or the exact signed Marketplace release — deployed to the
 *  Tool Hosts it names (which receive only its ServerTool exports), released to the active devices of the device groups
 *  it names (a signed client release, ADR 026, rolled out to members that do not hold it yet), and its declared exports
 *  are enabled in their reviewed Tool Groups. Bindings named by the profile allow exactly the profile's packages that name them. A Tool Host
 *  that has not registered, or a device group with no active device, leaves the package PENDING; a pin, verifier, compatibility or reference failure leaves it
 *  FAILED, and nothing is substituted. Repeating an identical profile changes nothing; removing an entry never
 *  uninstalls a package. Installation grants no runtime access: grants and policies stay separate reviewed records. */
public final class PackageProvisioning {
    /** The sealed image lock: the exact core artifact for a package and version, or null when the lock has none. */
    public interface CoreArtifacts { Core core(String packageId, String version); }
    public record Core(String packageDigest, Supplier<byte[]> bytes) {}

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final Store store; private final Codec codec; private final ServerPackages packages; private final Marketplace marketplace; private final CoreArtifacts core;
    private final ClientReleases clients; private final java.util.function.Function<io.ololabs.toolgate.control.domain.Ids.TenantId,FleetService> fleet; private final Clock clock;
    public PackageProvisioning(Store store, Codec codec, ServerPackages packages, Marketplace marketplace, CoreArtifacts core, ClientReleases clients, java.util.function.Function<io.ololabs.toolgate.control.domain.Ids.TenantId,FleetService> fleet, Clock clock) {
        this.store = store; this.codec = codec; this.packages = packages; this.marketplace = marketplace; this.core = core; this.clients = clients; this.fleet = fleet; this.clock = clock;
    }

    /** Whether a profile names device groups: their membership changes after READY, so the profile keeps being reapplied. */
    public boolean followsDevices(String document) {
        return codec.model(document, DeploymentProfile.class).packages().stream().anyMatch(p -> p.deviceGroupIds() != null && !p.deviceGroupIds().isEmpty());
    }

    private JsonNode node(String document) { return (JsonNode) codec.value(document); }
    private static String hex(String digest) { return digest.substring(7); }
    private static String bounded(String reason) { return reason == null ? "Provisioning failed" : reason.length() > 256 ? reason.substring(0, 256) : reason; }
    private static Failure refused(String reason) { return new Failure(io.ololabs.toolgate.contracts.ErrorCode.VALIDATION, 400, reason); }

    /** Structural and semantic checks of the complete profile, before any mutation. */
    private void validate(DeploymentProfile profile) {
        var ids = new HashSet<String>();
        for (var p : profile.packages()) {
            if (!ids.add(p.packageId())) throw refused("The profile names " + p.packageId() + " twice");
            if (p.source() == ProfilePackageSource.MARKETPLACE && p.packageDigest() == null) throw refused(p.packageId() + " needs an exact Marketplace package digest");
            if (p.source() == ProfilePackageSource.PREBUILT_CORE) {
                var sealed = core == null ? null : core.core(p.packageId(), p.version());
                if (sealed == null) throw refused(p.packageId() + " " + p.version() + " is not in the sealed core package lock");
                if (p.packageDigest() != null && !p.packageDigest().equals(sealed.packageDigest())) throw refused(p.packageId() + " differs from the sealed core package lock");
            }
            var hosts = p.hostIds() == null ? List.<String>of() : p.hostIds();
            if (new HashSet<>(hosts).size() != hosts.size()) throw refused(p.packageId() + " names a Tool Host twice");
            var groups = p.deviceGroupIds() == null ? List.<String>of() : p.deviceGroupIds();
            if (new HashSet<>(groups).size() != groups.size()) throw refused(p.packageId() + " names a device group twice");
            var tools = new HashSet<String>();
            for (var t : p.tools() == null ? List.<io.ololabs.toolgate.contracts.v2.ProfileToolActivation>of() : p.tools()) {
                if (!t.toolId().startsWith(p.packageId() + "/")) throw refused(t.toolId() + " is not an export of " + p.packageId());
                if (!tools.add(t.toolId())) throw refused(t.toolId() + " is activated twice");
            }
            if (!tools.isEmpty() && hosts.isEmpty() && groups.isEmpty()) throw refused(p.packageId() + " activates exports without a Tool Host or device group");
        }
    }

    /** Applies a profile as the deployment identity and records its status. */
    public Store.Reply apply(DirectoryService.Actor actor, String document) {
        var profile = codec.model(document, DeploymentProfile.class);
        validate(profile);
        String profileDigest = Canonical.digest(node(document));
        var previous = store.transaction(actor.tenant(), false, tx -> tx.packages().provisioning(profile.profileId()));
        var before = new HashMap<String, JsonNode>();
        if (previous != null) for (var p : node(previous.document()).path("packages")) before.put(p.path("packageId").asText(), p);

        var statuses = new ArrayList<ObjectNode>(); var digests = new HashMap<String, Set<String>>();
        for (var p : profile.packages()) {
            var status = JSON.objectNode().put("packageId", p.packageId()).put("version", p.version()).put("required", p.required());
            statuses.add(status);
            try { provision(actor, p, status, before.get(p.packageId()), digests); }
            catch (Failure failure) { status.put("state", "FAILED").put("reason", bounded(failure.getMessage())); }
            catch (RuntimeException failure) { status.put("state", "FAILED").put("reason", "Provisioning failed: " + failure.getClass().getSimpleName()); }
        }
        bindings(actor, profile, digests, statuses);

        String state = statuses.stream().anyMatch(s -> s.path("required").asBoolean() && s.path("state").asText().equals("FAILED")) ? "FAILED"
            : statuses.stream().anyMatch(s -> s.path("required").asBoolean() && s.path("state").asText().equals("PENDING")) ? "PENDING" : "READY";
        return store.transaction(actor.tenant(), true, tx -> {
            var current = tx.packages().provisioning(profile.profileId());
            var result = JSON.objectNode().put("profileId", profile.profileId()).put("profileDigest", profileDigest).put("generation", current == null ? 1 : current.generation()).put("state", state);
            var items = result.putArray("packages"); statuses.forEach(items::add);
            if (current != null) {
                var old = (ObjectNode) node(current.document());
                // An identical outcome of an identical profile is the same generation: nothing to record.
                var comparable = old.deepCopy(); comparable.remove("appliedAtUnixMs"); comparable.remove("previousProfileDigest"); comparable.remove("changes");
                if (Canonical.digest(comparable).equals(Canonical.digest(result)) && current.profileDigest().equals(profileDigest)) return new Store.Reply(200, current.document(), current.generation());
                result.put("generation", current.generation() + 1);
                if (!current.profileDigest().equals(profileDigest)) {
                    result.put("previousProfileDigest", current.profileDigest());
                    var changes = changes(before, statuses); if (!changes.isEmpty()) result.set("changes", changes);
                } else {
                    if (old.has("previousProfileDigest")) result.set("previousProfileDigest", old.get("previousProfileDigest"));
                    if (old.has("changes")) result.set("changes", old.get("changes"));
                }
            }
            result.put("appliedAtUnixMs", clock.millis());
            codec.model(codec.json(result), io.ololabs.toolgate.contracts.v2.ProvisioningStatus.class);
            long generation = result.get("generation").asLong(); String json = codec.json(result);
            tx.packages().saveProvisioning(new PackageStore.Provisioning(profile.profileId(), generation, profileDigest, json));
            tx.audit(actor.id(), "PROFILE_PROVISION", "profile:" + profile.profileId(), generation, "profile-" + hex(profileDigest).substring(0, 32) + "-" + generation, hex(profileDigest));
            return new Store.Reply(200, json, generation);
        });
    }

    /** What a changed profile changes, package by package, for review: added, re-pinned, or dropped (and still installed). */
    private static ArrayNode changes(Map<String, JsonNode> before, List<ObjectNode> statuses) {
        var changes = JSON.arrayNode(); var seen = new HashSet<String>();
        for (var now : statuses) {
            String id = now.path("packageId").asText(); seen.add(id); var was = before.get(id);
            if (was == null) {
                var change = changes.addObject().put("packageId", id).put("change", "ADDED").put("version", now.path("version").asText());
                if (now.has("packageDigest")) change.put("packageDigest", now.path("packageDigest").asText());
            } else if (!was.path("version").asText().equals(now.path("version").asText()) || !was.path("packageDigest").asText().equals(now.path("packageDigest").asText())) {
                var change = changes.addObject().put("packageId", id).put("change", "CHANGED").put("previousVersion", was.path("version").asText()).put("version", now.path("version").asText());
                if (was.has("packageDigest")) change.put("previousPackageDigest", was.path("packageDigest").asText());
                if (now.has("packageDigest")) change.put("packageDigest", now.path("packageDigest").asText());
            }
        }
        for (var e : new TreeMap<>(before).entrySet()) if (!seen.contains(e.getKey())) {
            var change = changes.addObject().put("packageId", e.getKey()).put("change", "REMOVED").put("previousVersion", e.getValue().path("version").asText());
            if (e.getValue().has("packageDigest")) change.put("previousPackageDigest", e.getValue().path("packageDigest").asText());
        }
        return changes;
    }

    private void provision(DirectoryService.Actor actor, ProfilePackage p, ObjectNode status, JsonNode before, Map<String, Set<String>> digests) {
        // 1. Import through the ordinary verifier.
        status.put("step", "IMPORT");
        String digest;
        if (p.source() == ProfilePackageSource.PREBUILT_CORE) {
            var sealed = core.core(p.packageId(), p.version()); digest = sealed.packageDigest(); status.put("packageDigest", digest);
            if (version(actor, digest) == null) packages.upload(actor, sealed.bytes().get(), "profile-core-" + hex(digest).substring(0, 32));
        } else {
            digest = p.packageDigest(); status.put("packageDigest", digest);
            if (version(actor, digest) == null) {
                if (marketplace == null || !marketplace.settings().enabled()) throw refused("No Marketplace is configured for " + p.packageId());
                marketplace.install(actor, p.packageId(), p.version(), digest, "profile-market-" + hex(digest).substring(0, 32));
            }
        }
        var stored = version(actor, digest);
        if (stored == null || !stored.packageId().equals(p.packageId()) || !stored.version().equals(p.version())) throw refused(p.packageId() + " " + p.version() + " did not import as pinned");
        var allowed = new TreeSet<String>(); allowed.add(hex(digest)); digests.put(p.packageId(), allowed);
        var expected = new HashMap<String, String>();  // toolId -> packageDigest of its directory record

        // 2. Deploy eligible exports to the named Tool Hosts.
        var hosts = new TreeSet<>(p.hostIds() == null ? List.<String>of() : p.hostIds());
        var toolIds = status.putArray("toolIds");
        if (!hosts.isEmpty()) {
            status.put("step", "DEPLOY");
            var hostIds = status.putArray("hostIds"); hosts.forEach(hostIds::add);
            var globals = JSON.objectNode(); if (p.globals() != null) p.globals().forEach(globals::set);
            String globalsDigest = Canonical.digest(globals); status.put("globalsDigest", globalsDigest);
            var missing = store.transaction(actor.tenant(), false, tx -> hosts.stream().filter(h -> tx.packages().toolHost(h) == null).toList());
            if (!missing.isEmpty()) { status.put("state", "PENDING").put("reason", bounded("Waiting for Tool Host " + String.join(", ", missing) + " to register")); return; }
            var deployment = store.transaction(actor.tenant(), false, tx -> { var raw = tx.packages().deployment(p.packageId()); return raw == null ? null : node(raw); });
            boolean same = deployment != null && deployment.path("packageDigest").asText().equals(digest) && hostSet(deployment).equals(hosts)
                && before != null && globalsDigest.equals(before.path("globalsDigest").asText());
            if (!same) {
                var request = JSON.objectNode().put("packageDigest", digest); request.set("globals", globals);
                var targets = request.putArray("hostIds"); hosts.forEach(targets::add);
                deployment = node(packages.deploy(actor, codec.json(request), "profile-deploy-" + hex(digest).substring(0, 24) + "-" + hex(globalsDigest).substring(0, 8)).body());
            }
            deployment.path("toolIds").forEach(id -> { toolIds.add(id.asText()); expected.put(id.asText(), hex(digest)); });
        }

        // 3. Release device-runnable exports to the active devices of the named groups.
        var groups = new TreeSet<>(p.deviceGroupIds() == null ? List.<String>of() : p.deviceGroupIds());
        boolean waitingForDevice = false;
        if (!groups.isEmpty()) {
            status.put("step", "RELEASE");
            var groupIds = status.putArray("deviceGroupIds"); groups.forEach(groupIds::add);
            if (clients == null || !clients.configured()) throw refused("Devices need client images configured (TOOLGATE_CONTROL_CLIENT_IMAGES_*) to receive " + p.packageId());
            if (!clients.signs()) throw refused("Devices need the package-release key (TOOLGATE_CONTROL_PACKAGE_RELEASE_PRIVATE_KEY_PATH) to sign " + p.packageId());
            var document = fleet.apply(actor.tenant()).document(actor, p.packageId(), p.version());
            if (document == null) {
                clients.release(actor, codec.json(JSON.objectNode().put("packageDigest", digest)), "profile-release-" + hex(digest).substring(0, 32));
                document = fleet.apply(actor.tenant()).document(actor, p.packageId(), p.version());
                if (document == null) throw refused("The client release of " + p.packageId() + " " + p.version() + " was not recorded");
            }
            for (var tool : document.tools()) {
                String managed = tool.authorizationProfile().tool().packageDigest();
                toolIds.add(tool.toolId()); expected.put(tool.toolId(), managed); allowed.add(managed);
            }
            status.put("step", "ROLLOUT");
            var targets = fleet.apply(actor.tenant()).targets(actor, p.packageId(), p.version(), groups);
            var missing = targets.members().stream().filter(d -> !targets.assigned().contains(d)).toList();
            if (!missing.isEmpty()) {
                String id = "profile-" + DirectoryService.digest(p.packageId() + "\n" + p.version() + "\n" + String.join(",", missing)).substring(0, 40);
                var request = JSON.objectNode().put("id", id).put("packageId", p.packageId()).put("version", p.version()).put("desiredPresence", true).put("percentage", 100);
                var devices = request.putArray("deviceIds"); missing.forEach(devices::add);
                fleet.apply(actor.tenant()).rollout(actor, codec.json(request), id, id);
            }
            var deviceIds = status.putArray("deviceIds"); targets.members().forEach(deviceIds::add);
            waitingForDevice = targets.members().isEmpty();
        }

        // 4. Enable the declared exports in their reviewed groups. Unknown exports block; nothing is created for them.
        status.put("step", "ACTIVATE");
        var activations = p.tools() == null ? List.<io.ololabs.toolgate.contracts.v2.ProfileToolActivation>of() : p.tools();
        if (!activations.isEmpty()) store.transaction(actor.tenant(), true, tx -> {
            var access = new ManagementAccess(codec); long now = clock.millis();
            for (var permission : List.of("tool.update", "tool.enable", "tool_group.manage_members")) access.requireAll(tx.load(), actor.userId(), permission, now);
            var directory = tx.load(); var entries = new HashMap<>(directory.entries()); boolean changed = false;
            for (var t : activations) {
                var entry = entries.get(Kind.TOOL.id(t.toolId()));
                String recorded = expected.getOrDefault(t.toolId(), hex(digest));
                if (entry == null || !recorded.equals(node(entry.document()).path("packageDigest").asText())) throw refused(t.toolId() + " is not an installed export of " + p.packageId() + " " + p.version());
                if (!entries.containsKey(Kind.TOOL_GROUP.id(t.toolGroupId()))) throw refused("Tool Group " + t.toolGroupId() + " does not exist");
                if (!entry.enabled()) {
                    var tool = (ObjectNode) node(entry.document()); tool.put("enabled", true).put("revision", tool.path("revision").asLong() + 1);
                    entries.put(entry.id(), codec.entry(Kind.TOOL, codec.json(tool))); changed = true;
                }
                if (!GroupGraph.memberships(new Directory(directory.revision(), entries), codec, Kind.TOOL, t.toolId(), false).equals(List.of(t.toolGroupId()))) {
                    GroupGraph.setMemberships(entries, codec, Kind.TOOL, t.toolId(), List.of(t.toolGroupId())); changed = true;
                }
            }
            if (changed) save(tx, actor, directory, entries, "PROFILE_ACTIVATE", "package:" + p.packageId());
            return null;
        });
        if (waitingForDevice) { status.put("state", "PENDING").put("reason", bounded("Waiting for an active device in " + String.join(", ", groups))); return; }
        status.put("step", "DONE").put("state", "READY");
    }

    /** Each binding the profile names allows exactly the imported profile packages that name it. */
    private void bindings(DirectoryService.Actor actor, DeploymentProfile profile, Map<String, Set<String>> digests, List<ObjectNode> statuses) {
        var named = new TreeMap<String, TreeSet<String>>(); var owners = new HashMap<String, List<ObjectNode>>();
        for (int i = 0; i < profile.packages().size(); i++) {
            var p = profile.packages().get(i);
            for (var binding : p.bindingIds() == null ? List.<String>of() : p.bindingIds()) {
                named.computeIfAbsent(binding, b -> new TreeSet<>()); owners.computeIfAbsent(binding, b -> new ArrayList<>()).add(statuses.get(i));
                if (digests.containsKey(p.packageId())) named.get(binding).addAll(digests.get(p.packageId()));
            }
        }
        for (var e : named.entrySet()) {
            try {
                store.transaction(actor.tenant(), true, tx -> {
                    new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "execution_binding.update", clock.millis());
                    var directory = tx.load(); var entries = new HashMap<>(directory.entries());
                    var entry = entries.get(Kind.BINDING.id(e.getKey()));
                    if (entry == null) throw refused("Execution binding " + e.getKey() + " does not exist");
                    var binding = (ObjectNode) node(entry.document()); var allowed = JSON.arrayNode(); e.getValue().forEach(allowed::add);
                    if (binding.path("allowedPackageDigests").equals(allowed)) return null;
                    binding.set("allowedPackageDigests", allowed); binding.put("revision", binding.path("revision").asLong() + 1);
                    entries.put(entry.id(), codec.entry(Kind.BINDING, codec.json(binding)));
                    save(tx, actor, directory, entries, "PROFILE_BINDING", "binding:" + e.getKey());
                    return null;
                });
            } catch (Failure failure) {
                for (var status : owners.get(e.getKey())) if (!status.path("state").asText().equals("FAILED")) status.put("state", "FAILED").put("reason", bounded(failure.getMessage()));
            }
        }
    }

    private void save(Store.Session tx, DirectoryService.Actor actor, Directory before, Map<io.ololabs.toolgate.control.domain.Ids.RecordId, Directory.Entry> entries, String operation, String target) {
        var after = new Directory(before.revision() + 1, entries);
        try { after.validate(512, 1048576); codec.validatePolicies(after); GroupGraph.validate(after, codec); } catch (IllegalArgumentException invalid) { throw Failure.conflict(); }
        tx.save(before, after);
        tx.audit(actor.id(), operation, target, after.revision(), operation.toLowerCase(Locale.ROOT) + "-" + after.revision(), DirectoryService.digest(target + "\n" + after.revision()));
    }

    private static TreeSet<String> hostSet(JsonNode deployment) { var set = new TreeSet<String>(); deployment.path("hostIds").forEach(h -> set.add(h.asText())); return set; }
    private PackageStore.Version version(DirectoryService.Actor actor, String digest) { return store.transaction(actor.tenant(), false, tx -> tx.packages().version(digest)); }

    /** The latest recorded status of every applied profile (ProvisioningStatusPage), for readers with package.read. */
    public Store.Reply statuses(DirectoryService.Actor actor) {
        return store.transaction(actor.tenant(), false, tx -> {
            actor.requireAdmin(); new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "package.read", clock.millis());
            var page = new StringBuilder("{\"items\":[");
            var all = tx.packages().provisionings();
            for (int i = 0; i < all.size(); i++) page.append(i == 0 ? "" : ",").append(all.get(i).document());
            return new Store.Reply(200, page.append("]}").toString(), 0);
        });
    }

    /** The latest recorded status of a profile, for readers with package.read. */
    public Store.Reply status(DirectoryService.Actor actor, String profileId) {
        return store.transaction(actor.tenant(), false, tx -> {
            actor.requireAdmin(); new ManagementAccess(codec).requireAll(tx.load(), actor.userId(), "package.read", clock.millis());
            var current = tx.packages().provisioning(io.ololabs.toolgate.control.domain.Ids.valid(profileId));
            if (current == null) throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.NOT_FOUND, 404, "Profile not applied");
            return new Store.Reply(200, current.document(), current.generation());
        });
    }
}
