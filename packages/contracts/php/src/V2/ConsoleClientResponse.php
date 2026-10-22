<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Requester-scoped device state and optional verified tenant UUID. Errors carry no installation or device metadata. The service enforces the error-only response invariant before serialization. */
final readonly class ConsoleClientResponse implements \JsonSerializable {
    public function __construct(
        public string $requestId,
        public ?string $tenantId = null,
        public ?\OloLabs\ToolGate\Contracts\ClientHealth $health = null,
        public ?\OloLabs\ToolGate\Contracts\EndpointEnrollmentPrompt $challenge = null,
        public ?\OloLabs\ToolGate\Contracts\ErrorCode $error = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['requestId', 'tenantId', 'health', 'challenge', 'error']) || array_diff(['requestId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['requestId'],
            array_key_exists('tenantId', $data) ? $data['tenantId'] : null,
            array_key_exists('health', $data) ? \OloLabs\ToolGate\Contracts\ClientHealth::fromArray($data['health']) : null,
            array_key_exists('challenge', $data) ? \OloLabs\ToolGate\Contracts\EndpointEnrollmentPrompt::fromArray($data['challenge']) : null,
            array_key_exists('error', $data) ? \OloLabs\ToolGate\Contracts\ErrorCode::from($data['error']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
