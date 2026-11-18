<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** ADR 075 NativeFileIntent; only protected qualified filesystem broker evidence. */
final readonly class NativeFileIntent implements \JsonSerializable {
    public function __construct(
        public NativeFileOperation $operation,
        public string $path,
        public ?string $destination = null,
        public ?string $contentSha256 = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['operation', 'path', 'destination', 'contentSha256']) || array_diff(['operation', 'path'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            NativeFileOperation::from($data['operation']),
            $data['path'],
            array_key_exists('destination', $data) ? $data['destination'] : null,
            array_key_exists('contentSha256', $data) ? $data['contentSha256'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
