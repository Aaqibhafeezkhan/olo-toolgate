<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Local tool boundary for signed managed registrations; validate schema before use. */
final readonly class LocalToolInfo implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public string $action,
        public string $description,
        public bool $enabled,
        public \stdClass $inputSchema,
        public string $toolDigest,
        public string $packageDigest,
        public ?BusinessKeyPolicy $businessKeyPolicy = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'action', 'description', 'enabled', 'inputSchema', 'toolDigest', 'packageDigest', 'businessKeyPolicy']) || array_diff(['toolId', 'action', 'description', 'enabled', 'inputSchema', 'toolDigest', 'packageDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            $data['action'],
            $data['description'],
            $data['enabled'],
            (object) $data['inputSchema'],
            $data['toolDigest'],
            $data['packageDigest'],
            array_key_exists('businessKeyPolicy', $data) ? BusinessKeyPolicy::fromArray($data['businessKeyPolicy']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
