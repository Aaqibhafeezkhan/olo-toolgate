<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Language runtime a tool needs. */
final readonly class RuntimeDeclaration implements \JsonSerializable {
    public function __construct(
        public string $runtimeId,
        public string $language,
        public string $version,
        public ?string $baseImageDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['runtimeId', 'language', 'version', 'baseImageDigest']) || array_diff(['runtimeId', 'language', 'version'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['runtimeId'],
            $data['language'],
            $data['version'],
            array_key_exists('baseImageDigest', $data) ? $data['baseImageDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
