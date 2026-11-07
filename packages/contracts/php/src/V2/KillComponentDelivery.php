<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Current registered consumer and its latest authenticated acknowledgement, if any. */
final readonly class KillComponentDelivery implements \JsonSerializable {
    public function __construct(
        public KillComponentKind $kind,
        public string $componentId,
        public ?int $authorizationEpoch = null,
        public ?int $acknowledgedAtUnixMs = null,
        public ?KillDeliveryPath $deliveryPath = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'componentId', 'authorizationEpoch', 'acknowledgedAtUnixMs', 'deliveryPath']) || array_diff(['kind', 'componentId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            KillComponentKind::from($data['kind']),
            $data['componentId'],
            array_key_exists('authorizationEpoch', $data) ? $data['authorizationEpoch'] : null,
            array_key_exists('acknowledgedAtUnixMs', $data) ? $data['acknowledgedAtUnixMs'] : null,
            array_key_exists('deliveryPath', $data) ? KillDeliveryPath::from($data['deliveryPath']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
