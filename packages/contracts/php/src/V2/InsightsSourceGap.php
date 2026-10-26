<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Admission counts that may be missing: a skipped sequence, or an epoch that stopped without a final batch. */
final readonly class InsightsSourceGap implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $gatewayId,
        public string $epoch,
        public int $lastSequence,
        public int $missingSequences,
        public int $lastSeenUnixMs,
        public int $droppedRequests
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'gatewayId', 'epoch', 'lastSequence', 'missingSequences', 'lastSeenUnixMs', 'droppedRequests']) || array_diff(['tenantId', 'gatewayId', 'epoch', 'lastSequence', 'missingSequences', 'lastSeenUnixMs', 'droppedRequests'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['gatewayId'],
            $data['epoch'],
            $data['lastSequence'],
            $data['missingSequences'],
            $data['lastSeenUnixMs'],
            $data['droppedRequests']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
