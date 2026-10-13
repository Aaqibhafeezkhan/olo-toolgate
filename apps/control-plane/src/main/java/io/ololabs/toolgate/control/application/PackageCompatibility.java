// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ContractSet;
import io.ololabs.toolgate.contracts.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Can this Control and its Tool Hosts run a package (marketplace design §6.2 A)? The manifest declares what it needs:
 *  `compatibility.contractsVersion`, `compatibility.minimumToolHostVersion`, `sdk.protocol`, `sdk.descriptorGeneration`
 *  and `runtimes`. Upload refuses what no Tool Host of this release can run; deploy refuses a target Tool Host that is
 *  too old or speaks no common protocol. The same rules mark Marketplace versions compatible or not. */
public final class PackageCompatibility {
    /** The tool protocol range the Tool Host of this release speaks (apps/tool-host `ProtocolRange { min: 2, max: 2 }`). */
    public static final int PROTOCOL_MIN = 2, PROTOCOL_MAX = 2;
    /** Descriptor generations Control accepts (descriptor.schema.json `descriptorGeneration` const). */
    public static final Set<Long> DESCRIPTOR_GENERATIONS = Set.of(1L);
    /** Runtime languages a Tool Host of this release provides. */
    public static final Set<String> RUNTIME_LANGUAGES = Set.of("java");
    private static final Pattern SEMVER = Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private PackageCompatibility() {}

    /** Why this Control can't run the package; empty when it can. `manifest` is the validated package manifest. */
    public static List<String> problems(JsonNode manifest) {
        var problems = new ArrayList<String>();
        String contracts = manifest.path("compatibility").path("contractsVersion").asText("");
        if (!SEMVER.matcher(contracts).matches()) problems.add("compatibility.contractsVersion is missing or not a version");
        else if (major(contracts) != major(ContractSet.VERSION)) problems.add("built against contracts " + contracts + ", this Control supports " + major(ContractSet.VERSION) + ".x");
        var minimumHost = manifest.path("compatibility").path("minimumToolHostVersion");
        if (!minimumHost.isMissingNode() && !SEMVER.matcher(minimumHost.asText("")).matches()) problems.add("compatibility.minimumToolHostVersion is not a version");
        var generation = manifest.path("sdk").path("descriptorGeneration");
        if (!generation.isIntegralNumber() || !DESCRIPTOR_GENERATIONS.contains(generation.asLong())) problems.add("descriptor generation " + generation.asText("?") + " is not one Control accepts " + DESCRIPTOR_GENERATIONS);
        var protocol = manifest.path("sdk").path("protocol");
        if (!protocol.path("min").isIntegralNumber() || !protocol.path("max").isIntegralNumber() || protocol.path("min").asLong() > protocol.path("max").asLong())
            problems.add("sdk.protocol is missing or not a range");
        else if (!overlaps(protocol, PROTOCOL_MIN, PROTOCOL_MAX))
            problems.add("tool protocol " + range(protocol) + " does not overlap " + PROTOCOL_MIN + "-" + PROTOCOL_MAX);
        problems.addAll(runtimeProblems(manifest));
        return problems;
    }

    /** Runtimes no Tool Host of this release provides. Deploy checks these for packages stored before upload did. */
    public static List<String> runtimeProblems(JsonNode manifest) {
        var problems = new ArrayList<String>();
        if (!manifest.path("runtimes").isArray() || manifest.path("runtimes").isEmpty()) problems.add("the package declares no runtime");
        for (var runtime : manifest.path("runtimes"))
            if (!RUNTIME_LANGUAGES.contains(runtime.path("language").asText())) problems.add("no Tool Host provides the " + runtime.path("language").asText("?") + " runtime");
        return problems;
    }

    /** Why the registered Tool Host can't run the package; empty when it can. */
    public static List<String> hostProblems(JsonNode manifest, String hostId, JsonNode registration) {
        var problems = new ArrayList<String>();
        String minimum = manifest.path("compatibility").path("minimumToolHostVersion").asText(""), version = registration.path("version").asText("");
        if (!minimum.isEmpty() && (!SEMVER.matcher(version).matches() || compare(version, minimum) < 0))
            problems.add("Tool Host " + hostId + " is version " + version + ", the package needs " + minimum + " or later");
        JsonNode protocol = manifest.path("sdk").path("protocol"), hostProtocol = registration.path("protocol");
        if (!overlaps(protocol, hostProtocol.path("min").asLong(0), hostProtocol.path("max").asLong(-1)))
            problems.add("Tool Host " + hostId + " speaks protocol " + range(hostProtocol) + ", the package needs " + range(protocol));
        return problems;
    }

    /** Refuses an incompatible package with the first reason, and logs every reason (errors carry no detail). */
    public static void require(List<String> problems) {
        if (problems.isEmpty()) return;
        org.jboss.logging.Logger.getLogger(PackageCompatibility.class).infof("package_incompatible reasons=%s", problems);
        throw incompatible();
    }

    public static Failure incompatible() { return new Failure(ErrorCode.VALIDATION, 422, "Package is not compatible with this ToolGate"); }

    private static boolean overlaps(JsonNode range, long min, long max) { return range.path("min").asLong(Long.MAX_VALUE) <= max && min <= range.path("max").asLong(Long.MIN_VALUE); }
    private static String range(JsonNode range) { return range.path("min").asText("?") + "-" + range.path("max").asText("?"); }
    static long major(String version) { return Long.parseLong(version.substring(0, version.indexOf('.'))); }

    /** SemVer 2.0.0 precedence; build metadata is ignored. */
    static int compare(String a, String b) {
        var x = SEMVER.matcher(a); var y = SEMVER.matcher(b);
        if (!x.matches() || !y.matches()) throw new IllegalArgumentException("not a version");
        for (int i = 1; i <= 3; i++) { int c = new java.math.BigInteger(x.group(i)).compareTo(new java.math.BigInteger(y.group(i))); if (c != 0) return c; }
        String p = x.group(4), q = y.group(4);
        if (p == null || q == null) return p == null ? (q == null ? 0 : 1) : -1;
        String[] l = p.split("\\."), r = q.split("\\.");
        for (int i = 0; i < Math.min(l.length, r.length); i++) {
            boolean ln = l[i].matches("[0-9]+"), rn = r[i].matches("[0-9]+");
            int c = ln && rn ? new java.math.BigInteger(l[i]).compareTo(new java.math.BigInteger(r[i])) : ln ? -1 : rn ? 1 : l[i].compareTo(r[i]);
            if (c != 0) return c;
        }
        return Integer.compare(l.length, r.length);
    }
}
