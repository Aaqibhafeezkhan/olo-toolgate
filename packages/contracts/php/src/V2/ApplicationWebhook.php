<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Caller-owned registration metadata; no signing key or user token is exposed. */
final readonly class ApplicationWebhook implements \JsonSerializable {
    public function __construct(
        public string $id,
        public ApplicationEventSource $sourceType,
        public string $sourceId,
        public array $eventKinds,
        public string $callbackUrl,
        public int $expiresAtUnixMs,
        public ApplicationWebhookState $state,
        public int $nextCursor,
        public int $attempts
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'sourceType', 'sourceId', 'eventKinds', 'callbackUrl', 'expiresAtUnixMs', 'state', 'nextCursor', 'attempts']) || array_diff(['id', 'sourceType', 'sourceId', 'eventKinds', 'callbackUrl', 'expiresAtUnixMs', 'state', 'nextCursor', 'attempts'], array_keys($data))) {
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
            $data['attempts']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
