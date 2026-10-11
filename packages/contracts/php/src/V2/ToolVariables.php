<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declared configuration and identity inputs. */
final readonly class ToolVariables implements \JsonSerializable {
    public function __construct(
        public array $globals,
        public array $secrets,
        public array $claims
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['globals', 'secrets', 'claims']) || array_diff(['globals', 'secrets', 'claims'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => GlobalDeclaration::fromArray($item), $data['globals']),
            array_map(static fn ($item) => SecretDeclaration::fromArray($item), $data['secrets']),
            array_map(static fn ($item) => ClaimDeclaration::fromArray($item), $data['claims'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
