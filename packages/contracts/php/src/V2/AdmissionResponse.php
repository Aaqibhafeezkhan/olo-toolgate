<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool Host to Gateway, sent before any sandbox starts. */
final readonly class AdmissionResponse implements \JsonSerializable {
    public function __construct(
        public AdmissionKind $kind,
        public string $invocationId,
        public ?string $fencingToken = null,
        public ?RefusalReason $reason = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'invocationId', 'fencingToken', 'reason']) || array_diff(['kind', 'invocationId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            AdmissionKind::from($data['kind']),
            $data['invocationId'],
            array_key_exists('fencingToken', $data) ? $data['fencingToken'] : null,
            array_key_exists('reason', $data) ? RefusalReason::from($data['reason']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
