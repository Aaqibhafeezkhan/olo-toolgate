<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Bounded finish model proposal; never execution authority. */
final readonly class WorkflowFinishDecision implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public mixed $result
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'result']) || array_diff(['kind', 'result'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'finish') {
            throw new \InvalidArgumentException('kind must be finish');
        }
        return new self(
            $data['kind'],
            $data['result']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v, $k) => $v !== null || in_array($k, ['result'], true), ARRAY_FILTER_USE_BOTH);
    }
}
