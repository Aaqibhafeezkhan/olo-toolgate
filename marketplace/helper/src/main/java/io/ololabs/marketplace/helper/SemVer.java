// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic Versioning 2.0.0, enough to order versions and name the bump between two of them. */
public record SemVer(long major, long minor, long patch, String preRelease) implements Comparable<SemVer> {
    private static final Pattern FORM = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    public static SemVer parse(String text) {
        Matcher m = text == null ? null : FORM.matcher(text);
        if (m == null || !m.matches()) {
            throw new HelperException(422, "SEMVER", "not a semantic version: " + text);
        }
        return new SemVer(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)), m.group(4));
    }

    @Override
    public int compareTo(SemVer other) {
        int c = Long.compare(major, other.major);
        if (c == 0) {
            c = Long.compare(minor, other.minor);
        }
        if (c == 0) {
            c = Long.compare(patch, other.patch);
        }
        if (c != 0) {
            return c;
        }
        if (preRelease == null || other.preRelease == null) {
            return preRelease == null ? (other.preRelease == null ? 0 : 1) : -1;
        }
        String[] a = preRelease.split("\\.");
        String[] b = other.preRelease.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean an = a[i].matches("\\d+");
            boolean bn = b[i].matches("\\d+");
            int d = an && bn ? Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]))
                    : an ? -1 : bn ? 1 : a[i].compareTo(b[i]);
            if (d != 0) {
                return d;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** The bump from {@code previous} to this version: 3 major, 2 minor, 1 patch (or pre-release), 0 none or lower. */
    public int bumpFrom(SemVer previous) {
        if (compareTo(previous) <= 0) {
            return 0;
        }
        if (major != previous.major) {
            return 3;
        }
        if (minor != previous.minor) {
            return 2;
        }
        return 1;
    }

    /**
     * The smallest bump an API change needs after {@code previous}. Before 1.0 a breaking change needs a minor bump and
     * an addition a patch (docs/development/foundation-upgrades.md); from 1.0 they need major and minor.
     */
    public static int requiredBump(SemVer previous, String apiChange) {
        boolean preview = previous.major == 0;
        return switch (apiChange) {
            case "breaking" -> preview ? 2 : 3;
            case "compatible" -> preview ? 1 : 2;
            default -> 1;
        };
    }

    public static String bumpName(int bump) {
        return switch (bump) {
            case 3 -> "major";
            case 2 -> "minor";
            case 1 -> "patch";
            default -> null;
        };
    }
}
