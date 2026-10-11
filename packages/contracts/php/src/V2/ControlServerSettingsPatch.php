<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** application/merge-patch+json body. Env-owned clientCheckIn is not patchable. */
final readonly class ControlServerSettingsPatch implements \JsonSerializable {
    public function __construct(
        public ?GatewaySettings $gateway = null,
        public ?DeviceApproval $deviceApproval = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['gateway', 'deviceApproval']) || array_diff([], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_key_exists('gateway', $data) ? GatewaySettings::fromArray($data['gateway']) : null,
            array_key_exists('deviceApproval', $data) ? DeviceApproval::fromArray($data['deviceApproval']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
