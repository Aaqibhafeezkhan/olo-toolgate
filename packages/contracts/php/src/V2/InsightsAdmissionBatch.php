<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Gateway to Control: admission counts since the previous batch of this process epoch. Best-effort; Control ignores a repeated (gatewayId, epoch, sequence) and reports missing sequences as source gaps. */
final readonly class InsightsAdmissionBatch implements \JsonSerializable {
    public function __construct(
        public string $gatewayId,
        public string $epoch,
        public int $sequence,
        public array $counts,
        public ?bool $lastBatch = null,
        public ?int $droppedRequests = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['gatewayId', 'epoch', 'sequence', 'lastBatch', 'droppedRequests', 'counts']) || array_diff(['gatewayId', 'epoch', 'sequence', 'counts'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['gatewayId'],
            $data['epoch'],
            $data['sequence'],
            array_map(static fn ($item) => InsightsAdmissionCount::fromArray($item), $data['counts']),
            array_key_exists('lastBatch', $data) ? $data['lastBatch'] : null,
            array_key_exists('droppedRequests', $data) ? $data['droppedRequests'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
