<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A delivered subscription cursor for server-side workflow admission. Caller/event fields cannot select tenant, workflow, version, identity or prompts. */
final readonly class WorkflowDispatchRequest implements \JsonSerializable {
    public function __construct(
        public string $subscriptionUid,
        public string $cursor
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['subscriptionUid', 'cursor']) || array_diff(['subscriptionUid', 'cursor'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['subscriptionUid'],
            $data['cursor']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
