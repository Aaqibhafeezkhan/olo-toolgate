// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * ADR 060 per-tenant Control audit hash chain. Every row commits to its tenant,
 * its 1-based chain index, its predecessor's digest and every stored audit field
 * under one domain-separated, length-prefixed canonical encoding.
 */
public final class AuditChain {
    /** Predecessor of chain index 1; tenant binding comes from the row digest itself. */
    public static final String GENESIS = "0".repeat(64);
    static final byte[] DOMAIN = "toolgate/control-audit-chain/v1\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    /** The exact millisecond UTC text the SQLite dialect stores; PostgreSQL stores the same instant. */
    public static final DateTimeFormatter SQLITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    /** Marks a stored timestamp that is not in canonical millisecond form. */
    public static final long MALFORMED_TIME = Long.MIN_VALUE;

    private AuditChain() {}

    public record Head(long chainIndex, String digest) {
        public static final Head EMPTY = new Head(0, GENESIS);
        public Head {
            if (chainIndex < 0 || digest == null || !HEX.matcher(digest).matches()) throw Failure.validation();
            if (chainIndex == 0 && !digest.equals(GENESIS)) throw Failure.validation();
        }
    }

    /** One stored row as read back from either SQL dialect. */
    public record Entry(long sequence, String tenantId, long chainIndex, String previousDigest, String actorId, String operation,
                        String target, long revision, String requestId, String requestDigest, String evidence,
                        long occurredAtMillis, String rowDigest) {
        public Head head() { return new Head(chainIndex, rowDigest); }
    }

    public enum Defect { NONE, MALFORMED, FOREIGN_TENANT, MISSING_OR_REORDERED, BROKEN_LINK, EDITED, TRUNCATED, HEAD_MISMATCH }

    /** First detected defect (or NONE), the chain index where it was detected and the last verified head. */
    public record Result(Defect defect, long chainIndex, Head verifiedHead) {
        public boolean valid() { return defect == Defect.NONE; }
    }

    public static String digest(String tenantId, long chainIndex, String previousDigest, String actorId, String operation, String target,
                                long revision, String requestId, String requestDigest, String evidence, long occurredAtMillis) {
        if (chainIndex < 1 || previousDigest == null || !HEX.matcher(previousDigest).matches() || occurredAtMillis == MALFORMED_TIME) throw Failure.validation();
        try {
            var bytes = new ByteArrayOutputStream(512);
            var out = new DataOutputStream(bytes);
            out.write(DOMAIN);
            text(out, tenantId); out.writeLong(chainIndex); text(out, previousDigest);
            text(out, actorId); text(out, operation); text(out, target); out.writeLong(revision);
            text(out, requestId); text(out, requestDigest);
            if (evidence == null) out.writeByte(0); else { out.writeByte(1); text(out, evidence); }
            out.writeLong(occurredAtMillis);
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String digest(Entry entry) {
        return digest(entry.tenantId(), entry.chainIndex(), entry.previousDigest(), entry.actorId(), entry.operation(), entry.target(),
            entry.revision(), entry.requestId(), entry.requestDigest(), entry.evidence(), entry.occurredAtMillis());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) throw Failure.validation();
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    public static String sqliteTime(long millis) { return SQLITE_TIME.format(Instant.ofEpochMilli(millis)); }

    /** Parses only the exact canonical text that {@link #sqliteTime} produces; anything else is malformed. */
    public static long sqliteMillis(String text) {
        try {
            long millis = Instant.from(SQLITE_TIME.parse(text)).toEpochMilli();
            return sqliteTime(millis).equals(text) ? millis : MALFORMED_TIME;
        } catch (RuntimeException malformed) { return MALFORMED_TIME; }
    }

    /** PostgreSQL stores microseconds; a non-millisecond value was not written by the chain writer. */
    public static long instantMillis(Instant instant) {
        return instant.getNano() % 1_000_000 == 0 ? instant.toEpochMilli() : MALFORMED_TIME;
    }

    /**
     * Verifies a contiguous segment that continues from {@code start}. When {@code expected}
     * (for example a signed anchor head) is given, the segment must reach it with the same digest.
     */
    public static Result verify(String tenantId, Head start, Iterable<Entry> entries, Head expected) {
        Head previous = start;
        for (var entry : entries) {
            long at = entry.chainIndex();
            if (entry.occurredAtMillis() == MALFORMED_TIME || entry.previousDigest() == null || entry.rowDigest() == null
                || !HEX.matcher(entry.previousDigest()).matches() || !HEX.matcher(entry.rowDigest()).matches())
                return new Result(Defect.MALFORMED, at, previous);
            if (!tenantId.equals(entry.tenantId())) return new Result(Defect.FOREIGN_TENANT, at, previous);
            if (at != previous.chainIndex() + 1) return new Result(Defect.MISSING_OR_REORDERED, at, previous);
            if (!entry.previousDigest().equals(previous.digest())) return new Result(Defect.BROKEN_LINK, at, previous);
            if (!digest(entry).equals(entry.rowDigest())) return new Result(Defect.EDITED, at, previous);
            if (expected != null && at == expected.chainIndex() && !entry.rowDigest().equals(expected.digest()))
                return new Result(Defect.HEAD_MISMATCH, at, previous);
            previous = entry.head();
        }
        if (expected != null) {
            if (previous.chainIndex() < expected.chainIndex()) return new Result(Defect.TRUNCATED, previous.chainIndex() + 1, previous);
            if (expected.chainIndex() == start.chainIndex() && !expected.digest().equals(start.digest())) return new Result(Defect.HEAD_MISMATCH, start.chainIndex(), start);
        }
        return new Result(Defect.NONE, previous.chainIndex(), previous);
    }
}
