<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** CloudEvents 1.0 JSON envelope written by the supervisor; authors never set source, type, dataschema or tg* attributes (plan 6.4). */
final readonly class TriggerCloudEvent implements \JsonSerializable {
    public function __construct(
        public string $specversion,
        public string $id,
        public string $source,
        public string $type,
        public string $datacontenttype,
        public mixed $data,
        public int $tgleaseepoch,
        public int $tgsequence,
        public TriggerEventKind $tgkind,
        public ?string $subject = null,
        public ?string $time = null,
        public ?string $dataschema = null,
        public ?string $traceparent = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['specversion', 'id', 'source', 'type', 'subject', 'time', 'datacontenttype', 'dataschema', 'data', 'tgleaseepoch', 'tgsequence', 'tgkind', 'traceparent']) || array_diff(['specversion', 'id', 'source', 'type', 'datacontenttype', 'data', 'tgleaseepoch', 'tgsequence', 'tgkind'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['specversion'] !== '1.0') {
            throw new \InvalidArgumentException('specversion must be 1.0');
        }
        if ($data['datacontenttype'] !== 'application/json') {
            throw new \InvalidArgumentException('datacontenttype must be application/json');
        }
        return new self(
            $data['specversion'],
            $data['id'],
            $data['source'],
            $data['type'],
            $data['datacontenttype'],
            $data['data'],
            $data['tgleaseepoch'],
            $data['tgsequence'],
            TriggerEventKind::from($data['tgkind']),
            array_key_exists('subject', $data) ? $data['subject'] : null,
            array_key_exists('time', $data) ? $data['time'] : null,
            array_key_exists('dataschema', $data) ? $data['dataschema'] : null,
            array_key_exists('traceparent', $data) ? $data['traceparent'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v, $k) => $v !== null || in_array($k, ['data'], true), ARRAY_FILTER_USE_BOTH);
    }
}
