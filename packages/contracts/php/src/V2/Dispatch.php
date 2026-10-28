<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Gateway to Tool Host. */
final readonly class Dispatch implements \JsonSerializable {
    public function __construct(
        public SignedInvocationPermit $permit,
        public \stdClass $arguments,
        public ?string $traceparent = null,
        public ?ExecutionContext $executionContext = null,
        public ?bool $collectSpans = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['permit', 'arguments', 'traceparent', 'executionContext', 'collectSpans']) || array_diff(['permit', 'arguments'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            SignedInvocationPermit::fromArray($data['permit']),
            (object) $data['arguments'],
            array_key_exists('traceparent', $data) ? $data['traceparent'] : null,
            array_key_exists('executionContext', $data) ? ExecutionContext::fromArray($data['executionContext']) : null,
            array_key_exists('collectSpans', $data) ? $data['collectSpans'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
