<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Reviewed effect class per tool. */
final readonly class EffectApproval implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public EffectSafety $effectSafety,
        public KeyNamespace $businessKeyNamespace,
        public MissingKeyPolicy $missingKeyPolicy,
        public string $approvalId
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'effectSafety', 'businessKeyNamespace', 'missingKeyPolicy', 'approvalId']) || array_diff(['toolId', 'effectSafety', 'businessKeyNamespace', 'missingKeyPolicy', 'approvalId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            EffectSafety::from($data['effectSafety']),
            KeyNamespace::from($data['businessKeyNamespace']),
            MissingKeyPolicy::from($data['missingKeyPolicy']),
            $data['approvalId']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
