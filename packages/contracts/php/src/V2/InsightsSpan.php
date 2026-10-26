<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One allowlisted span. Metadata only: never arguments, outputs, URLs, headers or secrets. */
final readonly class InsightsSpan implements \JsonSerializable {
    public function __construct(
        public string $traceId,
        public string $spanId,
        public InsightsSpanName $name,
        public string $invocationId,
        public int $startUnixMs,
        public int $durationMs,
        public InsightsSpanStatus $status,
        public ?string $parentSpanId = null,
        public ?InsightsSpanSource $source = null,
        public ?string $executionId = null,
        public ?string $routeId = null,
        public ?int $httpStatus = null,
        public ?string $errorCode = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['traceId', 'spanId', 'parentSpanId', 'name', 'source', 'invocationId', 'executionId', 'startUnixMs', 'durationMs', 'status', 'routeId', 'httpStatus', 'errorCode']) || array_diff(['traceId', 'spanId', 'name', 'invocationId', 'startUnixMs', 'durationMs', 'status'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['traceId'],
            $data['spanId'],
            InsightsSpanName::from($data['name']),
            $data['invocationId'],
            $data['startUnixMs'],
            $data['durationMs'],
            InsightsSpanStatus::from($data['status']),
            array_key_exists('parentSpanId', $data) ? $data['parentSpanId'] : null,
            array_key_exists('source', $data) ? InsightsSpanSource::from($data['source']) : null,
            array_key_exists('executionId', $data) ? $data['executionId'] : null,
            array_key_exists('routeId', $data) ? $data['routeId'] : null,
            array_key_exists('httpStatus', $data) ? $data['httpStatus'] : null,
            array_key_exists('errorCode', $data) ? $data['errorCode'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
