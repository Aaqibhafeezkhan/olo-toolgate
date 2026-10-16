<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Endpoint identity foundation wire model. */
final readonly class EndpointCheckIn implements \JsonSerializable {
    public function __construct(
        public int $sequence,
        public ClientReport $report,
        public ?string $adoptionDigest = null,
        public ?array $localTools = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sequence', 'report', 'adoptionDigest', 'localTools']) || array_diff(['sequence', 'report'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['sequence'],
            ClientReport::fromArray($data['report']),
            array_key_exists('adoptionDigest', $data) ? $data['adoptionDigest'] : null,
            array_key_exists('localTools', $data) ? array_map(static fn ($item) => LocalToolInfo::fromArray($item), $data['localTools']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
