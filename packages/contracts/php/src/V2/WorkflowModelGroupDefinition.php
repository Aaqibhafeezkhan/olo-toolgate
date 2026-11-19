<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Ordered model candidates; selection intersects this order with the pinned workflow ceiling. Only provider inference failures permit fallback; effects are never retried. */
final readonly class WorkflowModelGroupDefinition implements \JsonSerializable {
    public function __construct(
        public string $id,
        public array $deploymentIds,
        public ?bool $standby = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'deploymentIds', 'standby']) || array_diff(['id', 'deploymentIds'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            array_map(static fn ($item) => $item, $data['deploymentIds']),
            array_key_exists('standby', $data) ? $data['standby'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
