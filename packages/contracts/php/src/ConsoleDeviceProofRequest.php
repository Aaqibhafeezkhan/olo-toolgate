<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Bounded local native-client proof request for the selected console origin. */
final readonly class ConsoleDeviceProofRequest implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $consoleUrl,
        public string $nonce
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'consoleUrl', 'nonce']) || array_diff(['kind', 'consoleUrl', 'nonce'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['kind'],
            $data['consoleUrl'],
            $data['nonce']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
