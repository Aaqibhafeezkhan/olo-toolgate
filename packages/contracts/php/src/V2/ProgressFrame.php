<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Progress frame. */
final readonly class ProgressFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public float $fraction,
        public ?string $message = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'fraction', 'message']) || array_diff(['kind', 'fraction'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'PROGRESS') {
            throw new \InvalidArgumentException('kind must be PROGRESS');
        }
        return new self(
            $data['kind'],
            $data['fraction'],
            array_key_exists('message', $data) ? $data['message'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
