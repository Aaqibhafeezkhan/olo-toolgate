<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Reviewed admission key policy. Missing policy retains conservative legacy explicit-key behavior. */
final readonly class BusinessKeyPolicy implements \JsonSerializable {
    public function __construct(
        public bool $required,
        public BusinessKeyNamespace $namespace,
        public ?string $argument = null,
        public ?int $dedupeWindowMs = null,
        public ?int $retentionMs = null,
        public ?int $upstreamIdempotencyWindowMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['required', 'namespace', 'argument', 'dedupeWindowMs', 'retentionMs', 'upstreamIdempotencyWindowMs']) || array_diff(['required', 'namespace'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['required'],
            BusinessKeyNamespace::from($data['namespace']),
            array_key_exists('argument', $data) ? $data['argument'] : null,
            array_key_exists('dedupeWindowMs', $data) ? $data['dedupeWindowMs'] : null,
            array_key_exists('retentionMs', $data) ? $data['retentionMs'] : null,
            array_key_exists('upstreamIdempotencyWindowMs', $data) ? $data['upstreamIdempotencyWindowMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
