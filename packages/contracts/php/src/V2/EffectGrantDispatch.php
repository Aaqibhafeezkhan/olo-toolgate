<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One owner may claim the SENDING transition once. */
final readonly class EffectGrantDispatch implements \JsonSerializable {
    public function __construct(
        public SignedEffectGrant $grant,
        public string $brokerInstanceId,
        public string $handlerId
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['grant', 'brokerInstanceId', 'handlerId']) || array_diff(['grant', 'brokerInstanceId', 'handlerId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            SignedEffectGrant::fromArray($data['grant']),
            $data['brokerInstanceId'],
            $data['handlerId']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
