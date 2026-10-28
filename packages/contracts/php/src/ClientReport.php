<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Batched report of device inventory and applied desired revision. */
final readonly class ClientReport implements \JsonSerializable {
    public function __construct(
        public string $deviceId,
        public string $clientVersion,
        public int $appliedRevision,
        public array $packages,
        public ?DevicePower $power = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deviceId', 'clientVersion', 'appliedRevision', 'packages', 'power']) || array_diff(['deviceId', 'clientVersion', 'appliedRevision', 'packages'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['deviceId'],
            $data['clientVersion'],
            $data['appliedRevision'],
            array_map(static fn ($item) => ReportedPackage::fromArray($item), $data['packages']),
            array_key_exists('power', $data) ? DevicePower::from($data['power']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
