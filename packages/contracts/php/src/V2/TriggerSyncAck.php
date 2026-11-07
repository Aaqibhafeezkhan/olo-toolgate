<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Trigger block of the check-in ack (plan 8.3, 8.5). */
final readonly class TriggerSyncAck implements \JsonSerializable {
    public function __construct(
        public int $generation,
        public int $maxCheckInBytes,
        public ?bool $checkInNow = null,
        public ?array $acked = null,
        public ?array $dispositions = null,
        public ?array $retryLater = null,
        public ?array $leases = null,
        public ?array $revoked = null,
        public ?array $rejected = null,
        public ?array $snapshotPages = null,
        public ?bool $snapshotRequested = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['generation', 'maxCheckInBytes', 'checkInNow', 'acked', 'dispositions', 'retryLater', 'leases', 'revoked', 'rejected', 'snapshotPages', 'snapshotRequested']) || array_diff(['generation', 'maxCheckInBytes'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['generation'],
            $data['maxCheckInBytes'],
            array_key_exists('checkInNow', $data) ? $data['checkInNow'] : null,
            array_key_exists('acked', $data) ? array_map(static fn ($item) => TriggerOutboxAck::fromArray($item), $data['acked']) : null,
            array_key_exists('dispositions', $data) ? array_map(static fn ($item) => TriggerDisposition::fromArray($item), $data['dispositions']) : null,
            array_key_exists('retryLater', $data) ? array_map(static fn ($item) => TriggerRetryLater::fromArray($item), $data['retryLater']) : null,
            array_key_exists('leases', $data) ? array_map(static fn ($item) => TriggerLease::fromArray($item), $data['leases']) : null,
            array_key_exists('revoked', $data) ? array_map(static fn ($item) => TriggerRevocation::fromArray($item), $data['revoked']) : null,
            array_key_exists('rejected', $data) ? array_map(static fn ($item) => TriggerRejection::fromArray($item), $data['rejected']) : null,
            array_key_exists('snapshotPages', $data) ? array_map(static fn ($item) => TriggerSnapshotPageAck::fromArray($item), $data['snapshotPages']) : null,
            array_key_exists('snapshotRequested', $data) ? $data['snapshotRequested'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
