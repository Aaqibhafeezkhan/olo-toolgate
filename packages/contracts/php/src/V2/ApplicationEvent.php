<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Durable structural notification. RESULT announces an available result; read the separately authorized result endpoint for private output. */
final readonly class ApplicationEvent implements \JsonSerializable {
    public function __construct(
        public string $eventId,
        public string $tenantId,
        public ApplicationEventSource $sourceType,
        public string $sourceId,
        public int $sequence,
        public ApplicationEventKind $kind,
        public int $createdAtUnixMs,
        public \stdClass $data
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['eventId', 'tenantId', 'sourceType', 'sourceId', 'sequence', 'kind', 'createdAtUnixMs', 'data']) || array_diff(['eventId', 'tenantId', 'sourceType', 'sourceId', 'sequence', 'kind', 'createdAtUnixMs', 'data'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['eventId'],
            $data['tenantId'],
            ApplicationEventSource::from($data['sourceType']),
            $data['sourceId'],
            $data['sequence'],
            ApplicationEventKind::from($data['kind']),
            $data['createdAtUnixMs'],
            (object) $data['data']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
