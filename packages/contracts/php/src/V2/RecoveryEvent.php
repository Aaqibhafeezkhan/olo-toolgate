<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Supersedes kills after two-person review. Issuer is always control. */
final readonly class RecoveryEvent implements \JsonSerializable {
    public function __construct(
        public string $eventId,
        public string $issuer,
        public array $supersedes,
        public array $reviewers,
        public int $issuedAtUnixMs,
        public string $signature
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['eventId', 'issuer', 'supersedes', 'reviewers', 'issuedAtUnixMs', 'signature']) || array_diff(['eventId', 'issuer', 'supersedes', 'reviewers', 'issuedAtUnixMs', 'signature'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['issuer'] !== 'control') {
            throw new \InvalidArgumentException('issuer must be control');
        }
        return new self(
            $data['eventId'],
            $data['issuer'],
            array_map(static fn ($item) => $item, $data['supersedes']),
            array_map(static fn ($item) => $item, $data['reviewers']),
            $data['issuedAtUnixMs'],
            $data['signature']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
