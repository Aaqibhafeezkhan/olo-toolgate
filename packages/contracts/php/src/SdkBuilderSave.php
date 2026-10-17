<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Save a canonical SDK source revision in an authorized Tool Group. */
final readonly class SdkBuilderSave implements \JsonSerializable {
    public function __construct(
        public string $id,
        public int $expectedRevision,
        public string $toolGroupId,
        public string $source
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'expectedRevision', 'toolGroupId', 'source']) || array_diff(['id', 'expectedRevision', 'toolGroupId', 'source'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['expectedRevision'],
            $data['toolGroupId'],
            $data['source']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
