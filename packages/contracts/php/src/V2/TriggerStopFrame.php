<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Graceful stop. */
final readonly class TriggerStopFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $reason,
        public int $graceMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'reason', 'graceMs']) || array_diff(['kind', 'reason', 'graceMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'STOP') {
            throw new \InvalidArgumentException('kind must be STOP');
        }
        return new self(
            $data['kind'],
            $data['reason'],
            $data['graceMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
