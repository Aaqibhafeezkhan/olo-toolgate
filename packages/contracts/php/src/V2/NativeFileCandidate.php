<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Current installed file-only candidate; metadata is not execution authority. */
final readonly class NativeFileCandidate implements \JsonSerializable {
    public function __construct(
        public string $deviceId,
        public string $toolId,
        public string $managedDigest,
        public string $imageDigest,
        public bool $writeAccess
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deviceId', 'toolId', 'managedDigest', 'imageDigest', 'writeAccess']) || array_diff(['deviceId', 'toolId', 'managedDigest', 'imageDigest', 'writeAccess'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['deviceId'],
            $data['toolId'],
            $data['managedDigest'],
            $data['imageDigest'],
            $data['writeAccess']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
