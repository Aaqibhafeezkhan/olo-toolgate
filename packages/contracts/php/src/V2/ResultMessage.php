<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool Host to Gateway. */
final readonly class ResultMessage implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public InvocationOutcome $outcome,
        public ?string $fencingToken = null,
        public ?ResultFrame $result = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'outcome', 'result']) || array_diff(['invocationId', 'outcome'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            InvocationOutcome::from($data['outcome']),
            array_key_exists('fencingToken', $data) ? $data['fencingToken'] : null,
            array_key_exists('result', $data) ? ResultFrame::fromArray($data['result']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
