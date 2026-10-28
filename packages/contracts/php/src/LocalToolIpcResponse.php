<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Local tool boundary for signed managed registrations; validate schema before use. */
final readonly class LocalToolIpcResponse implements \JsonSerializable {
    public function __construct(
        public string $requestId,
        public ?array $tools = null,
        public ?\stdClass $output = null,
        public ?ErrorCode $error = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['requestId', 'tools', 'output', 'error']) || array_diff(['requestId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['requestId'],
            array_key_exists('tools', $data) ? array_map(static fn ($item) => LocalToolInfo::fromArray($item), $data['tools']) : null,
            array_key_exists('output', $data) ? (object) $data['output'] : null,
            array_key_exists('error', $data) ? ErrorCode::from($data['error']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
