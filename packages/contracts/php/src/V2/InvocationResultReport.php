<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool Host result to Control (effects/report, v2 body), keyed by invocation and fencing token. notStarted, with outcome FAILED, reports a claim the host abandoned before any sandbox started (invocation-state-v2.md T10). */
final readonly class InvocationResultReport implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken,
        public InvocationOutcome $outcome,
        public ?ResultFrame $result = null,
        public ?RefusalReason $notStarted = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'outcome', 'result', 'notStarted']) || array_diff(['invocationId', 'fencingToken', 'outcome'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken'],
            InvocationOutcome::from($data['outcome']),
            array_key_exists('result', $data) ? ResultFrame::fromArray($data['result']) : null,
            array_key_exists('notStarted', $data) ? RefusalReason::from($data['notStarted']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
