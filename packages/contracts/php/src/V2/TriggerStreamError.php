<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** An error on the stream, with the status and code an HTTP call would get. */
final readonly class TriggerStreamError implements \JsonSerializable {
    public function __construct(
        public int $status,
        public string $code,
        public ?string $detail = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['status', 'code', 'detail']) || array_diff(['status', 'code'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['status'],
            $data['code'],
            array_key_exists('detail', $data) ? $data['detail'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
