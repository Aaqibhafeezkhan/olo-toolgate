<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority. */
final readonly class WorkflowStackTriggerConfig implements \JsonSerializable {
    public function __construct(
        public string $folder,
        public string $extension
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['folder', 'extension']) || array_diff(['folder', 'extension'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['folder'] !== 'inbox') {
            throw new \InvalidArgumentException('folder must be inbox');
        }
        if ($data['extension'] !== '.txt') {
            throw new \InvalidArgumentException('extension must be .txt');
        }
        return new self(
            $data['folder'],
            $data['extension']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
