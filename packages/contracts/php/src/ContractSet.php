<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Identifies the shared contract set, independently of product versions. */
final readonly class ContractSet implements \JsonSerializable {
    public function __construct(
        public string $name,
        public string $version
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['name', 'version']) || array_diff(['name', 'version'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['name'],
            $data['version']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
    public const NAME = 'olo-toolgate-contracts';
    public const VERSION = '0.10.0-dev';
    /** Current canonical contract-set identity. */
    public static function current(): self { return new self(self::NAME, self::VERSION); }
}
