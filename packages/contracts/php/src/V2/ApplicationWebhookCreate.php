<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Caller-owned HTTPS notification subscription. Source ID is a run ID or trigger assignment name. Verification and current source permissions are required before delivery. */
final readonly class ApplicationWebhookCreate implements \JsonSerializable {
    public function __construct(
        public ApplicationEventSource $sourceType,
        public string $sourceId,
        public array $eventKinds,
        public string $callbackUrl,
        public int $expiresAtUnixMs,
        public int $after
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sourceType', 'sourceId', 'eventKinds', 'callbackUrl', 'expiresAtUnixMs', 'after']) || array_diff(['sourceType', 'sourceId', 'eventKinds', 'callbackUrl', 'expiresAtUnixMs', 'after'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ApplicationEventSource::from($data['sourceType']),
            $data['sourceId'],
            array_map(static fn ($item) => ApplicationEventKind::from($item), $data['eventKinds']),
            $data['callbackUrl'],
            $data['expiresAtUnixMs'],
            $data['after']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
