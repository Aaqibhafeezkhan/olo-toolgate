<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** ADR 075 NativeFileEffectBegin; only protected qualified filesystem broker evidence. */
final readonly class NativeFileEffectBegin implements \JsonSerializable {
    public function __construct(
        public string $requestId,
        public string $leaseId,
        public string $fencingToken,
        public int $ordinal,
        public NativeFileIntent $intent
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['requestId', 'leaseId', 'fencingToken', 'ordinal', 'intent']) || array_diff(['requestId', 'leaseId', 'fencingToken', 'ordinal', 'intent'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['requestId'],
            $data['leaseId'],
            $data['fencingToken'],
            $data['ordinal'],
            NativeFileIntent::fromArray($data['intent'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
