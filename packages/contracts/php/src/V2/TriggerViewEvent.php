<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A routed event as one reader may see it (plan 12.2 rule 5): the envelope always; data and subject only when the reader's clearance covers the event's label. */
final readonly class TriggerViewEvent implements \JsonSerializable {
    public function __construct(
        public string $specversion,
        public string $id,
        public string $source,
        public string $type,
        public TriggerClassification $tgclassification,
        public bool $tgredacted,
        public ?string $subject = null,
        public ?string $time = null,
        public ?\stdClass $data = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['specversion', 'id', 'source', 'type', 'subject', 'time', 'data', 'tgclassification', 'tgredacted']) || array_diff(['specversion', 'id', 'source', 'type', 'tgclassification', 'tgredacted'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['specversion'] !== '1.0') {
            throw new \InvalidArgumentException('specversion must be 1.0');
        }
        return new self(
            $data['specversion'],
            $data['id'],
            $data['source'],
            $data['type'],
            TriggerClassification::from($data['tgclassification']),
            $data['tgredacted'],
            array_key_exists('subject', $data) ? $data['subject'] : null,
            array_key_exists('time', $data) ? $data['time'] : null,
            array_key_exists('data', $data) ? (object) $data['data'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
