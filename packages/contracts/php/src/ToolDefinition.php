<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Capability definition. JSON schemas are data, never executable code. */
final readonly class ToolDefinition implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $name,
        public string $description,
        public array $actions,
        public \stdClass $inputSchema,
        public \stdClass $outputSchema,
        public ?ReconciliationMethod $reconciliation = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'name', 'description', 'actions', 'inputSchema', 'outputSchema', 'reconciliation']) || array_diff(['id', 'name', 'description', 'actions', 'inputSchema', 'outputSchema'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['name'],
            $data['description'],
            array_map(static fn ($item) => ToolAction::fromArray($item), $data['actions']),
            (object) $data['inputSchema'],
            (object) $data['outputSchema'],
            array_key_exists('reconciliation', $data) ? ReconciliationMethod::from($data['reconciliation']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
