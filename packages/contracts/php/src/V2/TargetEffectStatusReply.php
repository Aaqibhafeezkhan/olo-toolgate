<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Current target lookup response. Terminal evidence must match the stored key/body and include a stable business receipt and result digest; unknown/pending never authorize execution. FAILED means a durable target witness that the effect was not applied; an ordinary failed HTTP/business response is insufficient. */
final readonly class TargetEffectStatusReply implements \JsonSerializable {
    public function __construct(
        public string $key,
        public TargetEffectStatus $state,
        public ?string $bodyDigest = null,
        public ?string $businessReceipt = null,
        public ?string $resultDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['key', 'state', 'bodyDigest', 'businessReceipt', 'resultDigest']) || array_diff(['key', 'state'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['key'],
            TargetEffectStatus::from($data['state']),
            array_key_exists('bodyDigest', $data) ? $data['bodyDigest'] : null,
            array_key_exists('businessReceipt', $data) ? $data['businessReceipt'] : null,
            array_key_exists('resultDigest', $data) ? $data['resultDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
