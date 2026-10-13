<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Error that names what the caller must resolve; IDs only, never payloads. */
final readonly class ControlErrorDetail implements \JsonSerializable {
    public function __construct(
        public ControlErrorDetailCode $error,
        public ?array $ids = null,
        public ?string $requestId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['error', 'ids', 'requestId']) || array_diff(['error'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ControlErrorDetailCode::from($data['error']),
            array_key_exists('ids', $data) ? array_map(static fn ($item) => $item, $data['ids']) : null,
            array_key_exists('requestId', $data) ? $data['requestId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
