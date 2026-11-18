<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** ADR 075 NativeFileEffectReceipt; only protected qualified filesystem broker evidence. */
final readonly class NativeFileEffectReceipt implements \JsonSerializable {
    public function __construct(
        public string $requestId,
        public string $leaseId,
        public string $fencingToken,
        public string $effectId,
        public string $afterSha256,
        public ?string $beforeSha256 = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['requestId', 'leaseId', 'fencingToken', 'effectId', 'beforeSha256', 'afterSha256']) || array_diff(['requestId', 'leaseId', 'fencingToken', 'effectId', 'afterSha256'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['requestId'],
            $data['leaseId'],
            $data['fencingToken'],
            $data['effectId'],
            $data['afterSha256'],
            array_key_exists('beforeSha256', $data) ? $data['beforeSha256'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
