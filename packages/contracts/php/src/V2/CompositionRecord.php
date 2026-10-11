<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A library merged into the package (composition.md §2). */
final readonly class CompositionRecord implements \JsonSerializable {
    public function __construct(
        public string $libraryId,
        public string $version,
        public string $fragmentDigest,
        public string $namespacePrefix,
        public array $mappings,
        public array $excluded,
        public array $narrowed
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['libraryId', 'version', 'fragmentDigest', 'namespacePrefix', 'mappings', 'excluded', 'narrowed']) || array_diff(['libraryId', 'version', 'fragmentDigest', 'namespacePrefix', 'mappings', 'excluded', 'narrowed'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['libraryId'],
            $data['version'],
            $data['fragmentDigest'],
            $data['namespacePrefix'],
            array_map(static fn ($item) => CompositionMapping::fromArray($item), $data['mappings']),
            array_map(static fn ($item) => $item, $data['excluded']),
            array_map(static fn ($item) => $item, $data['narrowed'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
