<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Signed desired trigger state for one executor; the payload of a compact JWS by the organization deployment key. */
final readonly class TriggerDesiredDocument implements \JsonSerializable {
    public function __construct(
        public int $formatVersion,
        public string $tenantId,
        public string $executorId,
        public TriggerExecutor $executor,
        public int $generation,
        public int $issuedAtUnixMs,
        public int $expiresAtUnixMs,
        public array $instances
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['formatVersion', 'tenantId', 'executorId', 'executor', 'generation', 'issuedAtUnixMs', 'expiresAtUnixMs', 'instances']) || array_diff(['formatVersion', 'tenantId', 'executorId', 'executor', 'generation', 'issuedAtUnixMs', 'expiresAtUnixMs', 'instances'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['formatVersion'] !== 1) {
            throw new \InvalidArgumentException('formatVersion must be 1');
        }
        return new self(
            $data['formatVersion'],
            $data['tenantId'],
            $data['executorId'],
            TriggerExecutor::from($data['executor']),
            $data['generation'],
            $data['issuedAtUnixMs'],
            $data['expiresAtUnixMs'],
            array_map(static fn ($item) => TriggerDesiredInstance::fromArray($item), $data['instances'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
