<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Signed destination-ownership challenge. Return HMAC-SHA256(secret, verify.subscriptionId.challenge) using the supplied subscription ID and challenge as proof. */
final readonly class ApplicationWebhookChallenge implements \JsonSerializable {
    public function __construct(
        public string $type,
        public string $subscriptionId,
        public string $challenge
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['type', 'subscriptionId', 'challenge']) || array_diff(['type', 'subscriptionId', 'challenge'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['type'],
            $data['subscriptionId'],
            $data['challenge']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
