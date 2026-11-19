<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Private Gateway response; credentials must never be forwarded to runtime workers or logged. */
final readonly class WorkflowModelResolveResponse implements \JsonSerializable {
    public function __construct(
        public string $origin,
        public string $modelId,
        public string $credential,
        public WorkflowModelGeneration $generation,
        public string $deploymentId,
        public int $candidateIndex,
        public int $candidateCount,
        public \stdClass $resultSchema
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['origin', 'modelId', 'credential', 'generation', 'deploymentId', 'candidateIndex', 'candidateCount', 'resultSchema']) || array_diff(['origin', 'modelId', 'credential', 'generation', 'deploymentId', 'candidateIndex', 'candidateCount', 'resultSchema'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['origin'],
            $data['modelId'],
            $data['credential'],
            WorkflowModelGeneration::fromArray($data['generation']),
            $data['deploymentId'],
            $data['candidateIndex'],
            $data['candidateCount'],
            (object) $data['resultSchema']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
