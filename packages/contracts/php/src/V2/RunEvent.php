<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One retained run-response event. cursor is assigned by Control and orders replay; timestamps are display data only. */
final readonly class RunEvent implements \JsonSerializable {
    public function __construct(
        public int $cursor,
        public RunEventSource $source,
        public RunEventKind $kind,
        public int $recordedAtUnixMs,
        public ?int $attempt = null,
        public ?int $sequence = null,
        public ?string $executorId = null,
        public ?int $emittedAtUnixMs = null,
        public ?string $state = null,
        public ?ResultFrame $result = null,
        public ?LogLevel $level = null,
        public ?string $message = null,
        public ?float $fraction = null,
        public ?string $requestId = null,
        public ?\stdClass $request = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['cursor', 'source', 'kind', 'attempt', 'sequence', 'executorId', 'emittedAtUnixMs', 'recordedAtUnixMs', 'state', 'result', 'level', 'message', 'fraction', 'requestId', 'request']) || array_diff(['cursor', 'source', 'kind', 'recordedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['cursor'],
            RunEventSource::from($data['source']),
            RunEventKind::from($data['kind']),
            $data['recordedAtUnixMs'],
            array_key_exists('attempt', $data) ? $data['attempt'] : null,
            array_key_exists('sequence', $data) ? $data['sequence'] : null,
            array_key_exists('executorId', $data) ? $data['executorId'] : null,
            array_key_exists('emittedAtUnixMs', $data) ? $data['emittedAtUnixMs'] : null,
            array_key_exists('state', $data) ? $data['state'] : null,
            array_key_exists('result', $data) ? ResultFrame::fromArray($data['result']) : null,
            array_key_exists('level', $data) ? LogLevel::from($data['level']) : null,
            array_key_exists('message', $data) ? $data['message'] : null,
            array_key_exists('fraction', $data) ? $data['fraction'] : null,
            array_key_exists('requestId', $data) ? $data['requestId'] : null,
            array_key_exists('request', $data) ? (object) $data['request'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
