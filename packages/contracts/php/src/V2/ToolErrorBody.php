<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Error per tool-sdk/spec/authoring/errors.md. */
final readonly class ToolErrorBody implements \JsonSerializable {
    public function __construct(
        public string $code,
        public string $message,
        public bool $retryable,
        public ?\stdClass $data = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['code', 'message', 'retryable', 'data']) || array_diff(['code', 'message', 'retryable'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['code'],
            $data['message'],
            $data['retryable'],
            array_key_exists('data', $data) ? (object) $data['data'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
