<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool definition v2: the semantic descriptor plus packaging members outside the digest. */
final readonly class ToolDefinition implements \JsonSerializable {
    public function __construct(
        public ToolDescriptor $descriptor,
        public string $toolDescriptorDigest,
        public array $artifactIds,
        public ?string $runtimeId = null,
        public ?string $entryPoint = null,
        public ?ToolLimits $limits = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['descriptor', 'toolDescriptorDigest', 'runtimeId', 'entryPoint', 'artifactIds', 'limits']) || array_diff(['descriptor', 'toolDescriptorDigest', 'artifactIds'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ToolDescriptor::fromArray($data['descriptor']),
            $data['toolDescriptorDigest'],
            array_map(static fn ($item) => $item, $data['artifactIds']),
            array_key_exists('runtimeId', $data) ? $data['runtimeId'] : null,
            array_key_exists('entryPoint', $data) ? $data['entryPoint'] : null,
            array_key_exists('limits', $data) ? ToolLimits::fromArray($data['limits']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
