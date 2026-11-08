<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Acknowledgement from a socket-authenticated component; identity is never caller supplied. */
final readonly class KillAcknowledgement implements \JsonSerializable {
    public function __construct(
        public int $authorizationEpoch,
        public KillDeliveryPath $deliveryPath
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['authorizationEpoch', 'deliveryPath']) || array_diff(['authorizationEpoch', 'deliveryPath'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['authorizationEpoch'],
            KillDeliveryPath::from($data['deliveryPath'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
