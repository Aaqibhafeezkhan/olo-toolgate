<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Queue an immutable project revision for the shared SDK worker. */
final readonly class SdkBuilderBuildRequest implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $draftId,
        public int $expectedRevision
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'draftId', 'expectedRevision']) || array_diff(['id', 'draftId', 'expectedRevision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['draftId'],
            $data['expectedRevision']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
