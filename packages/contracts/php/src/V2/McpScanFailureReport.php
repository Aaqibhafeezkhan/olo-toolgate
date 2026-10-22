<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A failed scan, reported by the lease holder before the deadline. */
final readonly class McpScanFailureReport implements \JsonSerializable {
    public function __construct(
        public string $scanId,
        public string $fencingToken,
        public McpCheckFailure $failure,
        public ?string $observedSpkiPin = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['scanId', 'fencingToken', 'failure', 'observedSpkiPin']) || array_diff(['scanId', 'fencingToken', 'failure'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['scanId'],
            $data['fencingToken'],
            McpCheckFailure::from($data['failure']),
            array_key_exists('observedSpkiPin', $data) ? $data['observedSpkiPin'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
