<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One redacted non-terminal SDK frame (LOG, PROGRESS or INPUT_REQUEST) as the executor forwards it. sequence is monotonic per invocation attempt; the executor never renumbers a frame it resends. */
final readonly class RunEventFrame implements \JsonSerializable {
    public function __construct(
        public int $sequence,
        public int $emittedAtUnixMs,
        public RunEventKind $kind,
        public ?LogLevel $level = null,
        public ?string $message = null,
        public ?float $fraction = null,
        public ?string $requestId = null,
        public ?\stdClass $request = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sequence', 'emittedAtUnixMs', 'kind', 'level', 'message', 'fraction', 'requestId', 'request']) || array_diff(['sequence', 'emittedAtUnixMs', 'kind'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['sequence'],
            $data['emittedAtUnixMs'],
            RunEventKind::from($data['kind']),
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
