<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Every emit of this process up to emitSeq is resolved at Control. */
final readonly class TriggerAcceptedUpToFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public int $emitSeq
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'emitSeq']) || array_diff(['kind', 'emitSeq'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'ACCEPTED_UPTO') {
            throw new \InvalidArgumentException('kind must be ACCEPTED_UPTO');
        }
        return new self(
            $data['kind'],
            $data['emitSeq']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
