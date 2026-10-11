<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Lease renewal for a running ledger invocation (every lease/3). Also carries the latest progress and, while the tool waits for input, its input request (invocation-state-v2.md T6, T7). */
final readonly class LeaseHeartbeat implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken,
        public ?TaskProgress $progress = null,
        public ?InputRequestFrame $inputRequest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'progress', 'inputRequest']) || array_diff(['invocationId', 'fencingToken'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken'],
            array_key_exists('progress', $data) ? TaskProgress::fromArray($data['progress']) : null,
            array_key_exists('inputRequest', $data) ? InputRequestFrame::fromArray($data['inputRequest']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
