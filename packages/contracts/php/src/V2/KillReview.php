<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One authenticated independent review of retained kill or recovery intent. */
final readonly class KillReview implements \JsonSerializable {
    public function __construct(
        public string $reviewerUserId,
        public bool $approved,
        public int $reviewedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['reviewerUserId', 'approved', 'reviewedAtUnixMs']) || array_diff(['reviewerUserId', 'approved', 'reviewedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['reviewerUserId'],
            $data['approved'],
            $data['reviewedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
