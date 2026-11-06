<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Direct-mTLS owner request for the single AUTHORIZED to SENDING transition before a bound OLO write. A repeated request never permits another send. */
final readonly class ServicePermitDispatch implements \JsonSerializable {
    public function __construct(
        public ServicePermit $permit,
        public string $brokerInstanceId,
        public string $handlerId
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['permit', 'brokerInstanceId', 'handlerId']) || array_diff(['permit', 'brokerInstanceId', 'handlerId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ServicePermit::fromArray($data['permit']),
            $data['brokerInstanceId'],
            $data['handlerId']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
