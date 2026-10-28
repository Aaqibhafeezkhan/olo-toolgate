<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Canonical JSON SDK source project at an optimistic revision; no executable registration. */
final readonly class SdkBuilderDraft implements \JsonSerializable {
    public function __construct(
        public string $id,
        public int $revision,
        public string $toolGroupId,
        public string $source,
        public string $sourceDigest
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'revision', 'toolGroupId', 'source', 'sourceDigest']) || array_diff(['id', 'revision', 'toolGroupId', 'source', 'sourceDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['revision'],
            $data['toolGroupId'],
            $data['source'],
            $data['sourceDigest']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
