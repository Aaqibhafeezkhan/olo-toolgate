<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A call the deployment makes through the Gateway, as the named agent, to declare the profile ready. Arguments are explicit; expected names fields the structured result must contain with these exact values. */
final readonly class ProfileReadinessCase implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public string $agentId,
        public \stdClass $arguments,
        public ?\stdClass $expected = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'agentId', 'arguments', 'expected']) || array_diff(['toolId', 'agentId', 'arguments'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            $data['agentId'],
            (object) $data['arguments'],
            array_key_exists('expected', $data) ? (object) $data['expected'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
