<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Pinned operator-installed native LangGraph factory. Configuration grants no runtime authority. */
final readonly class WorkflowGraphSelection implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public string $version,
        public string $digest,
        public string $entryPoint,
        public \stdClass $config
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'version', 'digest', 'entryPoint', 'config']) || array_diff(['packageId', 'version', 'digest', 'entryPoint', 'config'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            $data['version'],
            $data['digest'],
            $data['entryPoint'],
            (object) $data['config']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
