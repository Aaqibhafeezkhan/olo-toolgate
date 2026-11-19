<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Gateway-only resolution of one authorized inference candidate, with current authority checked on every attempt. */
final readonly class WorkflowModelResolveRequest implements \JsonSerializable {
    public function __construct(
        public string $runId,
        public string $stepToken,
        public int $candidateIndex
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['runId', 'stepToken', 'candidateIndex']) || array_diff(['runId', 'stepToken', 'candidateIndex'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['runId'],
            $data['stepToken'],
            $data['candidateIndex']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
