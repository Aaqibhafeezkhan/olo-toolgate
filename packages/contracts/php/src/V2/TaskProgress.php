<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Latest progress. */
final readonly class TaskProgress implements \JsonSerializable {
    public function __construct(
        public float $fraction,
        public int $atUnixMs,
        public ?string $message = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['fraction', 'message', 'atUnixMs']) || array_diff(['fraction', 'atUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['fraction'],
            $data['atUnixMs'],
            array_key_exists('message', $data) ? $data['message'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
