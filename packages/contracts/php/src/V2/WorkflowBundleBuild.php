<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** WorkflowBundleBuild portable metadata. */
final readonly class WorkflowBundleBuild implements \JsonSerializable {
    public function __construct(
        public string $version,
        public string $sourceCommit,
        public string $build
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['version', 'sourceCommit', 'build']) || array_diff(['version', 'sourceCommit', 'build'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['version'],
            $data['sourceCommit'],
            $data['build']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
