<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** First stdin frame. */
final readonly class ToolInput implements \JsonSerializable {
    public function __construct(
        public int $protocolVersion,
        public string $invocationId,
        public string $toolId,
        public string $entryPoint,
        public \stdClass $arguments,
        public \stdClass $globals,
        public \stdClass $claims,
        public array $secretHandles,
        public int $deadlineUnixMs,
        public string $catalogRevision,
        public ?string $taskId = null,
        public ?string $idempotencyKey = null,
        public ?string $traceparent = null,
        public ?string $fencingToken = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['protocolVersion', 'invocationId', 'taskId', 'toolId', 'entryPoint', 'arguments', 'globals', 'claims', 'secretHandles', 'deadlineUnixMs', 'idempotencyKey', 'catalogRevision', 'traceparent', 'fencingToken']) || array_diff(['protocolVersion', 'invocationId', 'toolId', 'entryPoint', 'arguments', 'globals', 'claims', 'secretHandles', 'deadlineUnixMs', 'catalogRevision'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['protocolVersion'] !== 2) {
            throw new \InvalidArgumentException('protocolVersion must be 2');
        }
        return new self(
            $data['protocolVersion'],
            $data['invocationId'],
            $data['toolId'],
            $data['entryPoint'],
            (object) $data['arguments'],
            (object) $data['globals'],
            (object) $data['claims'],
            array_map(static fn ($item) => SecretHandle::fromArray($item), $data['secretHandles']),
            $data['deadlineUnixMs'],
            $data['catalogRevision'],
            array_key_exists('taskId', $data) ? $data['taskId'] : null,
            array_key_exists('idempotencyKey', $data) ? $data['idempotencyKey'] : null,
            array_key_exists('traceparent', $data) ? $data['traceparent'] : null,
            array_key_exists('fencingToken', $data) ? $data['fencingToken'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
