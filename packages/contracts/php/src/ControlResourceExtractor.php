<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Reviewed immutable versioned extraction definition; changing it invalidates operation bindings. */
final readonly class ControlResourceExtractor implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $name,
        public bool $enabled,
        public int $revision,
        public EnterpriseExtractorKind $extractorKind,
        public string $version,
        public array $fields,
        public array $fixedResources,
        public int $maxResources,
        public ?string $amountPointer = null,
        public ?string $operationPointer = null,
        public ?array $locatorTemplates = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'name', 'enabled', 'revision', 'extractorKind', 'version', 'fields', 'fixedResources', 'maxResources', 'amountPointer', 'operationPointer', 'locatorTemplates']) || array_diff(['id', 'name', 'enabled', 'revision', 'extractorKind', 'version', 'fields', 'fixedResources', 'maxResources'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['name'],
            $data['enabled'],
            $data['revision'],
            EnterpriseExtractorKind::from($data['extractorKind']),
            $data['version'],
            array_map(static fn ($item) => ExtractorField::fromArray($item), $data['fields']),
            array_map(static fn ($item) => ResourceDescriptor::fromArray($item), $data['fixedResources']),
            $data['maxResources'],
            array_key_exists('amountPointer', $data) ? $data['amountPointer'] : null,
            array_key_exists('operationPointer', $data) ? $data['operationPointer'] : null,
            array_key_exists('locatorTemplates', $data) ? array_map(static fn ($item) => $item, $data['locatorTemplates']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
