<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority. */
final readonly class WorkflowStackModelEngine implements \JsonSerializable {
    public function __construct(
        public string $deploymentId,
        public string $engine,
        public string $image,
        public string $modelFile,
        public int $contextSize,
        public int $threads
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deploymentId', 'engine', 'image', 'modelFile', 'contextSize', 'threads']) || array_diff(['deploymentId', 'engine', 'image', 'modelFile', 'contextSize', 'threads'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['engine'] !== 'LLAMA_CPP') {
            throw new \InvalidArgumentException('engine must be LLAMA_CPP');
        }
        return new self(
            $data['deploymentId'],
            $data['engine'],
            $data['image'],
            $data['modelFile'],
            $data['contextSize'],
            $data['threads']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
