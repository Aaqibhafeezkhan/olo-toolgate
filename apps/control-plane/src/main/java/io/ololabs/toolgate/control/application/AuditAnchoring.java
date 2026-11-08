// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Ids.TenantId;
import java.time.Clock;

/**
 * ADR 062 periodic head anchoring. Control verifies the chain segment since its last anchor,
 * signs the committed head with the dedicated anchor key, publishes it to the external sink
 * and only then records it. A broken chain is never signed.
 */
public final class AuditAnchoring {
    /** One recorded anchor, as mirrored in Control's database for scheduling and presentation. */
    public record Recorded(long chainIndex, String headDigest, String keyId, long issuedAtUnixMs, String document, String signature,
                           String sink, String location, String contentSha256) {
        public AuditAnchors.Anchor anchor() { return new AuditAnchors.Anchor(document, signature); }
    }

    public enum Outcome { NOTHING_TO_ANCHOR, ANCHORED, CHAIN_BROKEN }
    public record Result(Outcome outcome, Recorded anchor, AuditChain.Result chain) {}

    private static final int PAGE = 1000;
    private final Store store; private final AuditAnchors.Signer signer; private final AuditAnchors.Keyring keyring;
    private final AuditAnchorSink sink; private final Clock clock;

    public AuditAnchoring(Store store, AuditAnchors.Signer signer, AuditAnchors.Keyring keyring, AuditAnchorSink sink, Clock clock) {
        keyring.active(signer.keyId());
        this.store = store; this.signer = signer; this.keyring = keyring; this.sink = sink; this.clock = clock;
    }

    public Result anchor(TenantId tenant) {
        var head = store.transaction(tenant, false, Store.Session::auditHead);
        var last = store.transaction(tenant, false, Store.Session::latestAuditAnchor);
        if (head.chainIndex() == 0 || last != null && last.chainIndex() >= head.chainIndex()) return new Result(Outcome.NOTHING_TO_ANCHOR, last, null);
        // Continue only from the last anchor that still verifies against the pinned keyring.
        // A revoked key's anchors mean nothing; then the whole chain is re-verified from genesis.
        var start = AuditChain.Head.EMPTY;
        if (last != null) {
            try { start = AuditAnchors.verify(last.anchor(), keyring, tenant.value()).head(); }
            catch (AuditAnchors.Refused refused) { if (refused.refusal() != AuditAnchors.Refusal.REVOKED_KEY) throw refused; }
            if (!start.equals(AuditChain.Head.EMPTY) && (start.chainIndex() != last.chainIndex() || !start.digest().equals(last.headDigest())))
                throw new AuditAnchors.Refused(AuditAnchors.Refusal.CHAIN_MISMATCH);
        }
        var verified = verifySegment(tenant, start, head);
        if (!verified.valid()) return new Result(Outcome.CHAIN_BROKEN, last, verified);
        var name = AuditAnchors.objectName(tenant.value(), head);
        var existing = sink.read(name);
        AuditAnchors.Anchor anchor;
        AuditAnchorSink.Receipt receipt;
        if (existing != null) {
            // A previous attempt published but did not record; the object must be our own valid anchor.
            anchor = AuditAnchors.fromObject(existing);
            var claims = AuditAnchors.verify(anchor, keyring, tenant.value());
            if (!claims.head().equals(head)) throw new AuditAnchors.Refused(AuditAnchors.Refusal.CHAIN_MISMATCH);
            receipt = sink.publish(name, existing);
        } else {
            anchor = AuditAnchors.sign(signer, tenant.value(), head, clock.millis());
            AuditAnchors.verify(anchor, keyring, tenant.value());
            receipt = sink.publish(name, AuditAnchors.object(anchor));
        }
        var claims = AuditAnchors.parse(anchor.document());
        var recorded = new Recorded(head.chainIndex(), head.digest(), claims.keyId(), claims.issuedAtUnixMs(), anchor.document(), anchor.signature(),
            receipt.sink(), receipt.location(), receipt.contentSha256());
        store.transaction(tenant, true, tx -> { tx.recordAuditAnchor(recorded); return null; });
        return new Result(Outcome.ANCHORED, recorded, verified);
    }

    private AuditChain.Result verifySegment(TenantId tenant, AuditChain.Head start, AuditChain.Head head) {
        var result = new AuditChain.Result(AuditChain.Defect.NONE, start.chainIndex(), start);
        var at = start;
        while (at.chainIndex() < head.chainIndex()) {
            long after = at.chainIndex();
            var page = store.transaction(tenant, false, tx -> tx.auditChain(after, PAGE));
            var bounded = page.stream().filter(entry -> entry.chainIndex() <= head.chainIndex()).toList();
            if (bounded.isEmpty()) return AuditChain.verify(tenant.value(), at, bounded, head);
            result = AuditChain.verify(tenant.value(), at, bounded, bounded.get(bounded.size() - 1).chainIndex() >= head.chainIndex() ? head : null);
            if (!result.valid()) return result;
            at = result.verifiedHead();
        }
        return result;
    }
}
