<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Authenticated explicit approval of one immutable preview and revision. No worker-provided replacement intent or validity window is accepted. */
final readonly class EffectConsentApprovalRequest implements \JsonSerializable {
    public function __construct(
        public string $previewDigest,
        public int $expectedRevision
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['previewDigest', 'expectedRevision']) || array_diff(['previewDigest', 'expectedRevision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['previewDigest'],
            $data['expectedRevision']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
