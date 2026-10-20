<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** OS-authenticated native request scoped to a console origin and optional expected UUID. The service validates protected local tenant/origin binding before reading health or starting enrollment. */
final readonly class ConsoleClientRequest implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $requestId,
        public ConsoleClientOperation $operation,
        public string $consoleUrl,
        public ?string $expectedTenantId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'requestId', 'operation', 'consoleUrl', 'expectedTenantId']) || array_diff(['kind', 'requestId', 'operation', 'consoleUrl'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'console-tenant-state') {
            throw new \InvalidArgumentException('kind must be console-tenant-state');
        }
        return new self(
            $data['kind'],
            $data['requestId'],
            ConsoleClientOperation::from($data['operation']),
            $data['consoleUrl'],
            array_key_exists('expectedTenantId', $data) ? $data['expectedTenantId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
