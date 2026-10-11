<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Admin kill request; reviewed when the enterprise review workflow is on. */
final readonly class KillRequest implements \JsonSerializable {
    public function __construct(
        public KillScope $scope,
        public KillMode $mode,
        public string $reason
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['scope', 'mode', 'reason']) || array_diff(['scope', 'mode', 'reason'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            KillScope::fromArray($data['scope']),
            KillMode::from($data['mode']),
            $data['reason']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
