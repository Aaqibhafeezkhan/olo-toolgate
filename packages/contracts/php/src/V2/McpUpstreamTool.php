<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One tool exactly as the upstream tools/list returned it, within the scan limits. */
final readonly class McpUpstreamTool implements \JsonSerializable {
    public function __construct(
        public string $name,
        public \stdClass $inputSchema,
        public ?string $title = null,
        public ?string $description = null,
        public ?\stdClass $outputSchema = null,
        public ?\stdClass $annotations = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['name', 'title', 'description', 'inputSchema', 'outputSchema', 'annotations']) || array_diff(['name', 'inputSchema'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['name'],
            (object) $data['inputSchema'],
            array_key_exists('title', $data) ? $data['title'] : null,
            array_key_exists('description', $data) ? $data['description'] : null,
            array_key_exists('outputSchema', $data) ? (object) $data['outputSchema'] : null,
            array_key_exists('annotations', $data) ? (object) $data['annotations'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
