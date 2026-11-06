<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Bounded current-only preflight. Opaque cursors fence a changed tenant directory; observed facts never authorize a resend. */
final readonly class RecoveryPreflightPage implements \JsonSerializable {
    public function __construct(
        public array $items,
        public int $observedAtUnixMs,
        public string $buildMode,
        public ?string $nextCursor = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['items', 'nextCursor', 'observedAtUnixMs', 'buildMode']) || array_diff(['items', 'observedAtUnixMs', 'buildMode'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['buildMode'] !== 'CURRENT_ONLY') {
            throw new \InvalidArgumentException('buildMode must be CURRENT_ONLY');
        }
        return new self(
            array_map(static fn ($item) => ToolRecoveryReadiness::fromArray($item), $data['items']),
            $data['observedAtUnixMs'],
            $data['buildMode'],
            array_key_exists('nextCursor', $data) ? $data['nextCursor'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
