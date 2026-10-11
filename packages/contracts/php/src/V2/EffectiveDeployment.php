<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** GET /api/control/v1/deployments/{bindingId}/effective. */
final readonly class EffectiveDeployment implements \JsonSerializable {
    public function __construct(
        public string $bindingId,
        public int $revision,
        public array $values
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['bindingId', 'revision', 'values']) || array_diff(['bindingId', 'revision', 'values'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['bindingId'],
            $data['revision'],
            array_map(static fn ($item) => EffectiveValue::fromArray($item), $data['values'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
