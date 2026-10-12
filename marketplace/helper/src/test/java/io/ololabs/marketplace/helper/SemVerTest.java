// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

final class SemVerTest {
    private SemVerTest() {
    }

    public static void testOrdering() {
        Check.that(SemVer.parse("1.0.0").compareTo(SemVer.parse("1.0.0-rc.1")) > 0, "release after pre-release");
        Check.that(SemVer.parse("1.0.0-rc.2").compareTo(SemVer.parse("1.0.0-rc.10")) < 0, "numeric identifiers");
        Check.that(SemVer.parse("1.0.0-alpha").compareTo(SemVer.parse("1.0.0-alpha.1")) < 0, "shorter first");
        Check.equal(0, SemVer.parse("1.2.3+build").compareTo(SemVer.parse("1.2.3")));
        Check.refused("SEMVER", () -> SemVer.parse("1.2"));
    }

    public static void testBumps() {
        SemVer base = SemVer.parse("1.2.3");
        Check.equal(3, SemVer.parse("2.0.0").bumpFrom(base));
        Check.equal(2, SemVer.parse("1.3.0").bumpFrom(base));
        Check.equal(1, SemVer.parse("1.2.4").bumpFrom(base));
        Check.equal(0, SemVer.parse("1.2.3").bumpFrom(base));
        Check.equal(0, SemVer.parse("1.0.0").bumpFrom(base));
    }

    public static void testRequiredBumpBeforeAndAfterOne() {
        Check.equal(3, SemVer.requiredBump(SemVer.parse("1.0.0"), "breaking"));
        Check.equal(2, SemVer.requiredBump(SemVer.parse("1.0.0"), "compatible"));
        Check.equal(1, SemVer.requiredBump(SemVer.parse("1.0.0"), "none"));
        Check.equal(2, SemVer.requiredBump(SemVer.parse("0.1.0"), "breaking"));
        Check.equal(1, SemVer.requiredBump(SemVer.parse("0.1.0"), "compatible"));
    }
}
