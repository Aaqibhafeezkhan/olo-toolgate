<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declared destination; the host is a reviewed deployment setting. */
final readonly class DestinationDeclaration implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $hostSetting,
        public int $port,
        public array $spkiPins,
        public ?string $caBundle = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'hostSetting', 'port', 'spkiPins', 'caBundle']) || array_diff(['id', 'hostSetting', 'port', 'spkiPins'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['hostSetting'],
            $data['port'],
            array_map(static fn ($item) => $item, $data['spkiPins']),
            array_key_exists('caBundle', $data) ? $data['caBundle'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
