<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One deployed tool a Tool Host runs. The host reports it in its check-in as a local tool, so Control places calls for it there. */
final readonly class ToolHostAssignment implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public string $action,
        public string $description,
        public bool $enabled,
        public \stdClass $inputSchema,
        public string $toolDigest,
        public string $packageDigest
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'action', 'description', 'enabled', 'inputSchema', 'toolDigest', 'packageDigest']) || array_diff(['toolId', 'action', 'description', 'enabled', 'inputSchema', 'toolDigest', 'packageDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            $data['action'],
            $data['description'],
            $data['enabled'],
            (object) $data['inputSchema'],
            $data['toolDigest'],
            $data['packageDigest']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
