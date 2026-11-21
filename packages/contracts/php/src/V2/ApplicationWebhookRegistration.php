<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Registration response, including a private per-subscription signing secret. Exact authenticated retries may recover the same secret; it is encrypted at rest and absent from list/read responses. */
final readonly class ApplicationWebhookRegistration implements \JsonSerializable {
    public function __construct(
        public string $id,
        public ApplicationEventSource $sourceType,
        public string $sourceId,
        public array $eventKinds,
        public string $callbackUrl,
        public int $expiresAtUnixMs,
        public ApplicationWebhookState $state,
        public int $nextCursor,
        public int $attempts,
        public string $signingSecret
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'sourceType', 'sourceId', 'eventKinds', 'callbackUrl', 'expiresAtUnixMs', 'state', 'nextCursor', 'attempts', 'signingSecret']) || array_diff(['id', 'sourceType', 'sourceId', 'eventKinds', 'callbackUrl', 'expiresAtUnixMs', 'state', 'nextCursor', 'attempts', 'signingSecret'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            ApplicationEventSource::from($data['sourceType']),
            $data['sourceId'],
            array_map(static fn ($item) => ApplicationEventKind::from($item), $data['eventKinds']),
            $data['callbackUrl'],
            $data['expiresAtUnixMs'],
            ApplicationWebhookState::from($data['state']),
            $data['nextCursor'],
            $data['attempts'],
            $data['signingSecret']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
