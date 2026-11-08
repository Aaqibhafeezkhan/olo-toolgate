<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Bounded tenant export of current derived operation outcomes with grade, coverage and fencing counts. Ordered by operation id; never execution authority. */
final readonly class EnterpriseOperationEvidenceExportPage implements \JsonSerializable {
    public function __construct(
        public array $items,
        public int $exportedAtUnixMs,
        public ?string $next = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['items', 'next', 'exportedAtUnixMs']) || array_diff(['items', 'exportedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => EnterpriseOperationOutcome::fromArray($item), $data['items']),
            $data['exportedAtUnixMs'],
            array_key_exists('next', $data) ? $data['next'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
