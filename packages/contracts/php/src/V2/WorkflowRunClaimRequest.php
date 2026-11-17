<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Authenticated worker capabilities. Control only leases runs pinned to this compiler and an installed graph digest; this grants no model or tool authority. */
final readonly class WorkflowRunClaimRequest implements \JsonSerializable {
    public function __construct(
        public string $compilerDigest,
        public array $graphDigests
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['compilerDigest', 'graphDigests']) || array_diff(['compilerDigest', 'graphDigests'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['compilerDigest'],
            array_map(static fn ($item) => $item, $data['graphDigests'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
