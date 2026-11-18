<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** ADR 075 NativeFileAck; only protected qualified filesystem broker evidence. */
final readonly class NativeFileAck implements \JsonSerializable {
    public function __construct(
        public bool $accepted
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['accepted']) || array_diff(['accepted'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['accepted'] !== true) {
            throw new \InvalidArgumentException('accepted must be True');
        }
        return new self(
            $data['accepted']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
