<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Quickstart demo account pool, read only at install. Never stored as directory records. */
final readonly class ControlVisitorPool implements \JsonSerializable {
    public function __construct(
        public string $prefix,
        public string $teamId,
        public string $agentGroupId,
        public ?string $delegationId = null,
        public ?\stdClass $claims = null,
        public ?\stdClass $passClaims = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['prefix', 'teamId', 'agentGroupId', 'delegationId', 'claims', 'passClaims']) || array_diff(['prefix', 'teamId', 'agentGroupId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['prefix'],
            $data['teamId'],
            $data['agentGroupId'],
            array_key_exists('delegationId', $data) ? $data['delegationId'] : null,
            array_key_exists('claims', $data) ? (object) $data['claims'] : null,
            array_key_exists('passClaims', $data) ? (object) $data['passClaims'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
