<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Admin-approved private address range. */
final readonly class PrivateRangeApproval implements \JsonSerializable {
    public function __construct(
        public string $host,
        public string $cidr,
        public array $ports
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['host', 'cidr', 'ports']) || array_diff(['host', 'cidr', 'ports'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['host'],
            $data['cidr'],
            array_map(static fn ($item) => $item, $data['ports'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
