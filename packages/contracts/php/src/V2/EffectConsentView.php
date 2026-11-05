<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Scoped exact-effect review presentation, with a revision that changes on approval or revocation. */
final readonly class EffectConsentView implements \JsonSerializable {
    public function __construct(
        public EffectConsentPreview $preview,
        public string $previewDigest,
        public EffectConsentState $state,
        public int $revision,
        public ?string $reviewedBy = null,
        public ?int $approvedAtUnixMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['preview', 'previewDigest', 'state', 'revision', 'reviewedBy', 'approvedAtUnixMs']) || array_diff(['preview', 'previewDigest', 'state', 'revision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            EffectConsentPreview::fromArray($data['preview']),
            $data['previewDigest'],
            EffectConsentState::from($data['state']),
            $data['revision'],
            array_key_exists('reviewedBy', $data) ? $data['reviewedBy'] : null,
            array_key_exists('approvedAtUnixMs', $data) ? $data['approvedAtUnixMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
