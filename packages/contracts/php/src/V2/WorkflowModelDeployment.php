<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** An explicitly assigned model deployment. Only server-owned TLS origin, model name and vault reference are configuration; no provider key reaches a runner. */
final readonly class WorkflowModelDeployment implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $origin,
        public string $modelId,
        public string $secretName,
        public string $toolGroupId,
        public string $deviceGroupId
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'origin', 'modelId', 'secretName', 'toolGroupId', 'deviceGroupId']) || array_diff(['id', 'origin', 'modelId', 'secretName', 'toolGroupId', 'deviceGroupId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['origin'],
            $data['modelId'],
            $data['secretName'],
            $data['toolGroupId'],
            $data['deviceGroupId']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
