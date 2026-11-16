<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Bounded tool model proposal; never execution authority. */
final readonly class WorkflowToolDecision implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $toolId,
        public \stdClass $arguments
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'toolId', 'arguments']) || array_diff(['kind', 'toolId', 'arguments'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'tool') {
            throw new \InvalidArgumentException('kind must be tool');
        }
        return new self(
            $data['kind'],
            $data['toolId'],
            (object) $data['arguments']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
