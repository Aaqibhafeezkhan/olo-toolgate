<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority. */
final readonly class WorkflowStackFolderAgent implements \JsonSerializable {
    public function __construct(
        public string $agentId,
        public string $inbox,
        public string $processed,
        public string $output,
        public bool $archive,
        public string $projection
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['agentId', 'inbox', 'processed', 'output', 'archive', 'projection']) || array_diff(['agentId', 'inbox', 'processed', 'output', 'archive', 'projection'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['agentId'],
            $data['inbox'],
            $data['processed'],
            $data['output'],
            $data['archive'],
            $data['projection']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
