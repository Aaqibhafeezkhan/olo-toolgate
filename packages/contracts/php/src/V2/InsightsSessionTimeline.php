<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Every retained call of one session, oldest first, plus calls that declared one of them as parent (nested work), to a bounded depth. */
final readonly class InsightsSessionTimeline implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $sessionId,
        public array $invocations,
        public bool $truncated
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'sessionId', 'invocations', 'truncated']) || array_diff(['tenantId', 'sessionId', 'invocations', 'truncated'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['sessionId'],
            array_map(static fn ($item) => InsightsInvocation::fromArray($item), $data['invocations']),
            $data['truncated']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
