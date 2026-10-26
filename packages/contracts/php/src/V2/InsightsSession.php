<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One caller-declared session in a range: its calls by acceptance time. Session IDs are declared by the caller and not verified. */
final readonly class InsightsSession implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $sessionId,
        public int $firstAcceptedAtUnixMs,
        public int $lastActivityUnixMs,
        public int $calls,
        public int $succeeded,
        public int $failed,
        public int $unfinished,
        public int $agentRuns,
        public int $tools
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'sessionId', 'firstAcceptedAtUnixMs', 'lastActivityUnixMs', 'calls', 'succeeded', 'failed', 'unfinished', 'agentRuns', 'tools']) || array_diff(['tenantId', 'sessionId', 'firstAcceptedAtUnixMs', 'lastActivityUnixMs', 'calls', 'succeeded', 'failed', 'unfinished', 'agentRuns', 'tools'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['sessionId'],
            $data['firstAcceptedAtUnixMs'],
            $data['lastActivityUnixMs'],
            $data['calls'],
            $data['succeeded'],
            $data['failed'],
            $data['unfinished'],
            $data['agentRuns'],
            $data['tools']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
