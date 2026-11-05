<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Scoped operator result for one read-only target lookup. Effect status does not imply whole-operation completion; no target key, receipt or credential is exposed. */
final readonly class TargetEffectLookupResult implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $effectId,
        public TargetEffectStatus $state,
        public int $outcomeRevision,
        public ?string $evidenceDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'effectId', 'state', 'outcomeRevision', 'evidenceDigest']) || array_diff(['invocationId', 'effectId', 'state', 'outcomeRevision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['effectId'],
            TargetEffectStatus::from($data['state']),
            $data['outcomeRevision'],
            array_key_exists('evidenceDigest', $data) ? $data['evidenceDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
