<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Installs a new static token hash; delegated bindings are rebound to their user's current session epoch. */
final readonly class ControlWorkloadCredentialRotation implements \JsonSerializable {
    public function __construct(
        public string $credentialSha256,
        public bool $enable,
        public ?string $bindingId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['credentialSha256', 'enable', 'bindingId']) || array_diff(['credentialSha256', 'enable'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['credentialSha256'],
            $data['enable'],
            array_key_exists('bindingId', $data) ? $data['bindingId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
