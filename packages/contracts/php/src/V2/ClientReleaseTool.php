<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One export and whether a client device can run it; an unsupported export is never weakened to fit. */
final readonly class ClientReleaseTool implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public bool $supported,
        public ?string $reason = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'supported', 'reason']) || array_diff(['toolId', 'supported'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            $data['supported'],
            array_key_exists('reason', $data) ? $data['reason'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
