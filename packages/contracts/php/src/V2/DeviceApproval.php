<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Device approval settings. */
final readonly class DeviceApproval implements \JsonSerializable {
    public function __construct(
        public bool $autoApprove,
        public ?int $durationMs = null,
        public ?string $ownerUserId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['autoApprove', 'durationMs', 'ownerUserId']) || array_diff(['autoApprove'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['autoApprove'],
            array_key_exists('durationMs', $data) ? $data['durationMs'] : null,
            array_key_exists('ownerUserId', $data) ? $data['ownerUserId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
